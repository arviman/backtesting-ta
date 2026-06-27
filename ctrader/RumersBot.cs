// RumersBot — cTrader Algo C# cBot (M15, US indices).
//
// Previous-day-range fade. Mark yesterday's RTH high/low (RH/RL) and the
// extreme high/low of the last `LookbackDays` RTH days BEFORE yesterday
// (SH/SL). During today's RTH only:
//   SHORT when close is in [entryFloor, SH]
//     stop = SH + Buffer, tp = RH - TpFrac*(RH-RL)
//   LONG  when close is in [SL, entryCeil]
//     stop = SL - Buffer, tp = RL + TpFrac*(RH-RL)
//   entryFloor/entryCeil enforce a worst-case R:R floor (MinRR).
// One trade per RTH day. Force close at session end.
//
// Defaults are sweep B's best on NQ M15 5-year backtest (see RumersTest.kt):
//   lb=90, buffer=10, minRR=1.5, tpFrac=1.0  →  PF 1.47, +$54k, $15k maxYrDD
//   (221 trades). Tune with the cAlgo optimizer.
//
// Symbol: any NQ/NAS100/US100 CFD on M15. Point/pip handling assumes the
// instrument's PipSize matches its native tick; on NQ CFDs that's typically
// 0.1 (so Buffer=10.0 = 100 pips ≈ 10 index points).
//
// Session timing: uses TimeZoneInfo to convert server time → ET so the RTH
// gate stays correct across both EU and US DST shifts. No manual hour edits
// required for typical EET/EEST brokers.
//
// Indexing convention: cTrader 0 = OLDEST. All bar reads use Last(1)
// (the just-closed bar).

using System;
using System.Collections.Generic;
using cAlgo.API;
using cAlgo.API.Internals;

namespace cAlgo.Robots
{
    [Robot(TimeZone = TimeZones.UTC, AccessRights = AccessRights.None)]
    public class RumersBot : Robot
    {
        // ────── Sizing ──────
        [Parameter("Volume (lots)", DefaultValue = 1.0, MinValue = 0.01, Step = 0.01, Group = "Sizing")]
        public double VolumeLots { get; set; }

        [Parameter("Label", DefaultValue = "Rumers", Group = "Sizing")]
        public string Label { get; set; }

        // ────── Strategy knobs ──────
        // Range of historical RTH days scanned for SH/SL. The window is
        // [today - LookbackDays - 1, yesterday - 1] (i.e. excludes yesterday
        // and today). SH = max(highs) in window if > RH, else no shorts today.
        [Parameter("Lookback days", DefaultValue = 90, MinValue = 5, MaxValue = 250, Step = 5, Group = "Strategy")]
        public int LookbackDays { get; set; }

        // Buffer added past SH/SL when placing the stop, in the symbol's
        // price unit (NQ index points). On a 0.1-pip NQ CFD, 10.0 = 100 pips.
        [Parameter("Buffer (price)", DefaultValue = 10.0, MinValue = 0.1, Step = 0.1, Group = "Strategy")]
        public double Buffer { get; set; }

        // Worst-case R:R floor at entry. 1.0 = mid-zone. 1.5 = entry must
        // be in the tighter 40% closest to SH/SL (the lower-stop side).
        [Parameter("Min R:R", DefaultValue = 1.5, MinValue = 0.1, MaxValue = 5.0, Step = 0.1, Group = "Strategy")]
        public double MinRR { get; set; }

        // Fraction of yesterday's range to use as the TP target. 1.0 = full
        // revert to opposite edge (RL for shorts, RH for longs). 0.5 = mid.
        [Parameter("TP fraction of range", DefaultValue = 1.0, MinValue = 0.05, MaxValue = 1.0, Step = 0.05, Group = "Strategy")]
        public double TpFrac { get; set; }

        // Trend-day filter. Skip shorts if yesterday's close finished in the
        // top SkipBand of its range (uptrend bias), longs symmetric. 0 = off.
        [Parameter("Skip band", DefaultValue = 0.0, MinValue = 0.0, MaxValue = 0.49, Step = 0.05, Group = "Strategy")]
        public double SkipBand { get; set; }

        // ────── Session (ET, fixed for US RTH) ──────
        [Parameter("RTH open hour (ET)", DefaultValue = 9, MinValue = 0, MaxValue = 23, Step = 1, Group = "Session")]
        public int RthOpenHourEt { get; set; }

        [Parameter("RTH open minute (ET)", DefaultValue = 30, MinValue = 0, MaxValue = 59, Step = 5, Group = "Session")]
        public int RthOpenMinEt { get; set; }

        [Parameter("RTH duration (min)", DefaultValue = 390, MinValue = 60, MaxValue = 480, Step = 15, Group = "Session")]
        public int RthDurationMin { get; set; }

        // ────── Internal state ──────
        private TimeZoneInfo _et;
        private TimeSpan _serverUtcOffset;

        // Per-RTH-day stats: dayKey → (rthHigh, rthLow, rthClose).
        private readonly Dictionary<int, (double H, double L, double C)> _rthHistory = new();
        private readonly List<int> _rthHistoryOrder = new();

        // Today's running RTH H/L (built up as RTH bars arrive).
        private int _currentRthDay = -1;
        private double _rthHighToday = double.NaN;
        private double _rthLowToday = double.NaN;
        private double _rthLastCloseToday = double.NaN;

        // Today's setup levels (computed at first RTH bar of the day).
        private double _todayRH = double.NaN, _todayRL = double.NaN;
        private bool _hasSH = false, _hasSL = false;
        private double _todaySH = double.NaN, _todaySL = double.NaN;
        private double _todayPrevClose = double.NaN;
        private bool _tradedToday = false;

        // Derived window helpers (RTH open-time minute-of-day in ET).
        private int RthOpenMin => RthOpenHourEt * 60 + RthOpenMinEt;
        private int RthLastBarOpenMin => RthOpenMin + RthDurationMin - 15;
        private int LastEntryOpenMin => RthOpenMin + RthDurationMin - 30;

        protected override void OnStart()
        {
            if (TimeFrame != TimeFrame.Minute15)
                Print("WARNING: RumersBot is tuned on M15. Current TF: {0}", TimeFrame);

            _et = ResolveEasternTimeZone();
            _serverUtcOffset = Server.Time - Server.TimeInUtc;

            Print("Rumers ready. lb={0} buf={1} minRR={2:F1} tpFrac={3:F2} skipBand={4:F2} | RTH {5:D2}:{6:D2}-{7} ET",
                  LookbackDays, Buffer, MinRR, TpFrac, SkipBand,
                  RthOpenHourEt, RthOpenMinEt, FmtEtClock(RthOpenMin + RthDurationMin));
        }

        protected override void OnBar()
        {
            if (Bars.Count < 2) return;

            var serverOpen = Bars.OpenTimes.Last(1);
            var etOpen = ToEt(serverOpen);
            int openMin = etOpen.Hour * 60 + etOpen.Minute;
            int dayKey = DayKey(etOpen);

            double h = Bars.HighPrices.Last(1);
            double l = Bars.LowPrices.Last(1);
            double c = Bars.ClosePrices.Last(1);

            bool isRthBar = openMin >= RthOpenMin && openMin <= RthLastBarOpenMin;
            bool isFirstRthBarOfDay = isRthBar && dayKey != _currentRthDay;

            // Day rollover (at first RTH bar we see for a new ET date).
            if (isFirstRthBarOfDay)
            {
                FinalizeYesterdayIfPending();
                _currentRthDay = dayKey;
                _rthHighToday = double.NaN;
                _rthLowToday = double.NaN;
                _rthLastCloseToday = double.NaN;
                _tradedToday = false;
                SetupTodayLevels();
                CloseOpenPositionIfAny("day-rollover");
            }

            // Update today's RTH H/L on every RTH bar.
            if (isRthBar)
            {
                _rthHighToday = double.IsNaN(_rthHighToday) ? h : Math.Max(_rthHighToday, h);
                _rthLowToday  = double.IsNaN(_rthLowToday)  ? l : Math.Min(_rthLowToday, l);
                _rthLastCloseToday = c;
            }

            // EOD force close at the last RTH bar (no further entries).
            if (isRthBar && openMin >= RthLastBarOpenMin)
            {
                CloseOpenPositionIfAny("EOD");
                return;
            }

            // Entries: in RTH, before LastEntry minute, no position, not traded yet.
            if (!isRthBar) return;
            if (openMin > LastEntryOpenMin) return;
            if (_tradedToday) return;
            if (FindOpenPosition() != null) return;
            if (double.IsNaN(_todayRH) || double.IsNaN(_todayRL)) return;

            double range = _todayRH - _todayRL;
            if (range <= 0) return;

            // Trend-day filter using yesterday's close position in its range.
            double closeFrac = 0.5;
            if (!double.IsNaN(_todayPrevClose))
                closeFrac = Clamp((_todayPrevClose - _todayRL) / range, 0.0, 1.0);
            bool allowShort = _hasSH && closeFrac <= 1.0 - SkipBand;
            bool allowLong  = _hasSL && closeFrac >= SkipBand;

            // ── SHORT ──
            if (allowShort)
            {
                double stop = _todaySH + Buffer;
                double tp = _todayRH - TpFrac * range;
                if (stop > tp)
                {
                    double entryFloor = (MinRR * stop + tp) / (1.0 + MinRR);
                    if (c >= entryFloor && c <= _todaySH)
                    {
                        OpenAbs(TradeType.Sell, c, stop, tp);
                        _tradedToday = true;
                        return;
                    }
                }
            }

            // ── LONG ──
            if (allowLong)
            {
                double stop = _todaySL - Buffer;
                double tp = _todayRL + TpFrac * range;
                if (tp > stop)
                {
                    double entryCeil = (MinRR * stop + tp) / (1.0 + MinRR);
                    if (c <= entryCeil && c >= _todaySL)
                    {
                        OpenAbs(TradeType.Buy, c, stop, tp);
                        _tradedToday = true;
                    }
                }
            }
        }

        protected override void OnStop() => Print("Rumers stopped.");

        // ──────────────────────────────────────────────────────────────────
        // Helpers
        // ──────────────────────────────────────────────────────────────────

        private void FinalizeYesterdayIfPending()
        {
            if (_currentRthDay < 0 || double.IsNaN(_rthHighToday)) return;
            _rthHistory[_currentRthDay] = (_rthHighToday, _rthLowToday, _rthLastCloseToday);
            _rthHistoryOrder.Add(_currentRthDay);
        }

        private void SetupTodayLevels()
        {
            _todayRH = double.NaN; _todayRL = double.NaN;
            _hasSH = false; _hasSL = false;
            _todaySH = double.NaN; _todaySL = double.NaN;
            _todayPrevClose = double.NaN;

            int n = _rthHistoryOrder.Count;
            if (n < 1) return;

            int yesterdayKey = _rthHistoryOrder[n - 1];
            var (rh, rl, rc) = _rthHistory[yesterdayKey];
            _todayRH = rh; _todayRL = rl; _todayPrevClose = rc;

            if (n < 2) return;

            int start = Math.Max(0, n - 1 - LookbackDays);
            int endExcl = n - 1;
            double maxH = double.NegativeInfinity;
            double minL = double.PositiveInfinity;
            for (int k = start; k < endExcl; k++)
            {
                var (h, l, _) = _rthHistory[_rthHistoryOrder[k]];
                if (h > maxH) maxH = h;
                if (l < minL) minL = l;
            }
            if (maxH > rh) { _todaySH = maxH; _hasSH = true; }
            if (minL < rl) { _todaySL = minL; _hasSL = true; }
        }

        private void OpenAbs(TradeType type, double entry, double sl, double tp)
        {
            double slPips = Math.Abs(entry - sl) / Symbol.PipSize;
            double tpPips = Math.Abs(entry - tp) / Symbol.PipSize;
            double volume = Symbol.QuantityToVolumeInUnits(VolumeLots);
            volume = Symbol.NormalizeVolumeInUnits(volume, RoundingMode.ToNearest);
            var result = ExecuteMarketOrder(type, SymbolName, volume, Label, slPips, tpPips);
            if (!result.IsSuccessful)
                Print("Entry failed ({0}): {1}", type, result.Error);
        }

        private Position FindOpenPosition()
        {
            foreach (var p in Positions)
                if (p.Label == Label && p.SymbolName == SymbolName) return p;
            return null;
        }

        private void CloseOpenPositionIfAny(string reason)
        {
            var p = FindOpenPosition();
            if (p == null) return;
            var r = ClosePosition(p);
            if (!r.IsSuccessful) Print("Close ({0}) failed: {1}", reason, r.Error);
        }

        private DateTime ToEt(DateTime serverTime)
        {
            // Bars timestamps are server-local; convert via known server offset.
            var asUtc = DateTime.SpecifyKind(serverTime - _serverUtcOffset, DateTimeKind.Utc);
            return TimeZoneInfo.ConvertTimeFromUtc(asUtc, _et);
        }

        private static int DayKey(DateTime t) => t.Year * 10000 + t.Month * 100 + t.Day;

        private static double Clamp(double v, double lo, double hi) => Math.Max(lo, Math.Min(hi, v));

        private static string FmtEtClock(int minutesOfDay)
        {
            int h = minutesOfDay / 60, m = minutesOfDay % 60;
            return string.Format("{0:D2}:{1:D2} ET", h, m);
        }

        private static TimeZoneInfo ResolveEasternTimeZone()
        {
            // IANA on Linux/Mac, Windows id elsewhere.
            try { return TimeZoneInfo.FindSystemTimeZoneById("America/New_York"); }
            catch { return TimeZoneInfo.FindSystemTimeZoneById("Eastern Standard Time"); }
        }
    }
}
