// RumersBot — cTrader Algo C# cBot (M15, US Nasdaq).
//
// Previous-day-range fade. Mark yesterday's RTH high/low (RH/RL) and the
// extreme high/low of the last `LookbackDays` RTH days BEFORE yesterday
// (SH/SL). During today's RTH:
//   SHORT when bar.high > SH (rejection wick) AND close in [entryFloor, SH]
//     stop = SH + Buffer, tp = RH - TpFrac × (RH-RL)
//   LONG  when bar.low < SL (rejection wick) AND close in [SL, entryCeil]
//     stop = SL - Buffer, tp = RL + TpFrac × (RH-RL)
//   entryFloor / entryCeil enforce a worst-case R:R floor (MinRR).
// Force close at session end.
//
// ─── Rejection-wick gate ────────────────────────────────────────────────
// RequireRejection=true demands the entry bar pierce SH (shorts) or SL
// (longs) intra-bar and close back inside the zone. This filters passive
// grind-into-zone entries and keeps only stop-hunt rejections. Backtest:
// trade count drops ~50% but PF roughly doubles and peak DD drops to ~36%
// of the no-rejection variant — which is the main reason the bot can be
// sized up while still fitting funded eval rules.
//
// ─── Pyramiding ─────────────────────────────────────────────────────────
// Up to PyramidingLimit same-direction concurrent tiers can stack within
// one RTH day, all sharing the day's SL/TP. New tier only opens when
// existing tiers are in profit (RequireProfitToPyramid). For this regime
// (rejection ON, NQ M15) pyramiding past 1 doubled the worst-day without
// improving annualized return — keep Pyramid=1 unless you re-optimize.
//
// ─── Defaults (eval-tuned: the5ers $100k cTrader on NAS100 CFD) ─────────
//   lookback=30, buffer=10, minRR=2.0, tpFrac=0.75, pyramid=1, rejection=on
//   Volume=50 lots (NAS100 CFD on the5ers' Pepperstone broker = $1/pt/lot,
//   so 50 lots → $50/pt, which matches a 2.5 NQ-futures position size).
//
//   Backtest is on NQ futures (CME, $20/pt) at 1 contract:
//     88 trades · PF 2.17 · peakDD $3,110 · worst day -$2,925
//
//   Bootstrap monte-carlo (20k iter, 6-mo eval window, $50/pt sizing):
//     P(pass $8k target) = 47%   P(bust $4k daily / $10k DD) = 8%
//     Median pass time   = 2.7 mo (when passing)
//
//   Pushing to $60/pt (Volume=60): P(pass)=50%, P(bust)=14% — faster, riskier.
//   Dropping to $40/pt (Volume=40): P(pass)=39%, P(bust)=8% — same risk, slower.
//   $50/pt is the risk-adjusted sweet spot. See .lavish/rumers-bootstrap.html
//   for the full sweep.
//
// ─── WARNING: VOLUME IS BROKER-SPECIFIC ────────────────────────────────
// Volume=50 assumes the broker quotes NAS100 (or US100 / USTEC / NDX) as a
// CFD where 1 lot = $1 per index point. This is the standard cTrader spec
// on Pepperstone, IC Markets, and the5ers' broker integration.
//
// On a different broker, REASSESS Volume before going live:
//   1. Open the symbol in cTrader → Symbol Info → check "Pip value" at 1 lot.
//   2. Target $/pt = $50 (the backtest's eval-tuned size).
//   3. Set Volume = $50 ÷ (broker's $ per pt per lot).
// Examples: NQ futures at $20/pt → Volume = 2.5. MNQ micros at $2/pt → 25.
//
// All knobs exposed for the cAlgo optimizer.
//
// ─── Session timing ─────────────────────────────────────────────────────
// Server-time → ET via TimeZoneInfo so the RTH gate stays correct across
// EU and US DST shifts. No manual hour edits required for typical EET/EEST
// brokers.
//
// Symbol: any NQ / NAS100 / US100 CFD on M15. Buffer is in the symbol's
// price unit (NQ index points). On a 0.1-tick CFD, Buffer=10.0 = 100 ticks
// ≈ 10 index points.
//
// Indexing: cTrader 0 = OLDEST. All bar reads use Last(1) (the just-closed
// bar).

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
        // Default 50 = the5ers $100k on NAS100 CFD ($1/pt/lot brokers).
        // For NQ futures use 2.5; for other brokers reassess (see header).
        [Parameter("Volume (lots)", DefaultValue = 50, MinValue = 0.01, Step = 0.01, Group = "Sizing")]
        public double VolumeLots { get; set; }

        [Parameter("Label", DefaultValue = "Rumers", Group = "Sizing")]
        public string Label { get; set; }

        // ────── Strategy knobs (eval-tuned on 5y NQ M15) ──────
        [Parameter("Lookback days", DefaultValue = 30, MinValue = 5, MaxValue = 250, Step = 5, Group = "Strategy")]
        public int LookbackDays { get; set; }

        [Parameter("Buffer (price)", DefaultValue = 10.0, MinValue = 0.1, Step = 0.5, Group = "Strategy")]
        public double Buffer { get; set; }

        [Parameter("Min R:R", DefaultValue = 2.0, MinValue = 0.1, MaxValue = 5.0, Step = 0.1, Group = "Strategy")]
        public double MinRR { get; set; }

        [Parameter("TP fraction of range", DefaultValue = 0.75, MinValue = 0.05, MaxValue = 1.0, Step = 0.05, Group = "Strategy")]
        public double TpFrac { get; set; }

        [Parameter("Skip band", DefaultValue = 0.0, MinValue = 0.0, MaxValue = 0.49, Step = 0.05, Group = "Strategy")]
        public double SkipBand { get; set; }

        [Parameter("Require rejection wick", DefaultValue = true, Group = "Strategy")]
        public bool RequireRejection { get; set; }

        // ────── Pyramiding ──────
        [Parameter("Pyramid limit", DefaultValue = 1, MinValue = 1, MaxValue = 10, Step = 1, Group = "Pyramid")]
        public int PyramidingLimit { get; set; }

        [Parameter("Require profit to pyramid", DefaultValue = true, Group = "Pyramid")]
        public bool RequireProfitToPyramid { get; set; }

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

        // Day's shared SL/TP (set on first entry, reused by every pyramid tier).
        private bool _haveSharedLevels = false;
        private double _sharedSL = double.NaN;
        private double _sharedTP = double.NaN;
        private int _entryCountToday = 0;

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

            Print("Rumers ready. lb={0} buf={1} minRR={2:F1} tpFrac={3:F2} skipBand={4:F2} rej={5} pyramid={6}/{7} | vol={8}lots | RTH {9:D2}:{10:D2}-{11} ET",
                  LookbackDays, Buffer, MinRR, TpFrac, SkipBand,
                  RequireRejection ? "on" : "off",
                  PyramidingLimit, RequireProfitToPyramid ? "profit-gated" : "no-gate",
                  VolumeLots,
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

            // Day rollover: snapshot yesterday's RTH H/L/C and prepare today's setup.
            if (isFirstRthBarOfDay)
            {
                FinalizeYesterdayIfPending();
                _currentRthDay = dayKey;
                _rthHighToday = double.NaN;
                _rthLowToday = double.NaN;
                _rthLastCloseToday = double.NaN;
                _entryCountToday = 0;
                _haveSharedLevels = false;
                _sharedSL = double.NaN;
                _sharedTP = double.NaN;
                SetupTodayLevels();
                CloseAllOpenPositions("day-rollover");
            }

            // Update today's RTH H/L on every RTH bar.
            if (isRthBar)
            {
                _rthHighToday = double.IsNaN(_rthHighToday) ? h : Math.Max(_rthHighToday, h);
                _rthLowToday  = double.IsNaN(_rthLowToday)  ? l : Math.Min(_rthLowToday, l);
                _rthLastCloseToday = c;
            }

            // EOD force close on the last RTH bar.
            if (isRthBar && openMin >= RthLastBarOpenMin)
            {
                CloseAllOpenPositions("EOD");
                return;
            }

            // Entries: in RTH, before LastEntry minute, pyramid room remaining.
            if (!isRthBar) return;
            if (openMin > LastEntryOpenMin) return;
            if (_entryCountToday >= PyramidingLimit) return;
            if (CountOpenPositions() >= PyramidingLimit) return;
            if (double.IsNaN(_todayRH) || double.IsNaN(_todayRL)) return;

            double range = _todayRH - _todayRL;
            if (range <= 0) return;

            // Trend-day filter using yesterday's close position in its range.
            double closeFrac = 0.5;
            if (!double.IsNaN(_todayPrevClose))
                closeFrac = Clamp((_todayPrevClose - _todayRL) / range, 0.0, 1.0);
            bool allowShort = _hasSH && closeFrac <= 1.0 - SkipBand;
            bool allowLong  = _hasSL && closeFrac >= SkipBand;

            int currentSide = CurrentPositionSide();   // +1 long, -1 short, 0 none
            double lastEntry = LastEntryPrice();

            // ── SHORT ──
            if (allowShort && currentSide >= 0)
            {
                double stop = _todaySH + Buffer;
                double tp = _todayRH - TpFrac * range;
                if (stop > tp)
                {
                    double entryFloor = (MinRR * stop + tp) / (1.0 + MinRR);
                    // Profit gate: shorts profit when price drops, so new tier needs close < lastEntry.
                    bool profitOk = currentSide == 0 || !RequireProfitToPyramid || c < lastEntry;
                    // Rejection gate: this bar must have pierced SH and closed back inside.
                    bool rejectionOk = !RequireRejection || h > _todaySH;
                    if (profitOk && rejectionOk && c >= entryFloor && c <= _todaySH)
                    {
                        OpenAbs(TradeType.Sell, c, stop, tp);
                        _sharedSL = stop;
                        _sharedTP = tp;
                        _haveSharedLevels = true;
                        _entryCountToday++;
                        return;
                    }
                }
            }

            // ── LONG ── (skip if a short just opened; also re-read side)
            currentSide = CurrentPositionSide();
            lastEntry = LastEntryPrice();
            if (allowLong && currentSide <= 0)
            {
                double stop = _todaySL - Buffer;
                double tp = _todayRL + TpFrac * range;
                if (tp > stop)
                {
                    double entryCeil = (MinRR * stop + tp) / (1.0 + MinRR);
                    bool profitOk = currentSide == 0 || !RequireProfitToPyramid || c > lastEntry;
                    bool rejectionOk = !RequireRejection || l < _todaySL;
                    if (profitOk && rejectionOk && c <= entryCeil && c >= _todaySL)
                    {
                        OpenAbs(TradeType.Buy, c, stop, tp);
                        _sharedSL = stop;
                        _sharedTP = tp;
                        _haveSharedLevels = true;
                        _entryCountToday++;
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

        private int CountOpenPositions()
        {
            int n = 0;
            foreach (var p in Positions)
                if (p.Label == Label && p.SymbolName == SymbolName) n++;
            return n;
        }

        private int CurrentPositionSide()
        {
            foreach (var p in Positions)
                if (p.Label == Label && p.SymbolName == SymbolName)
                    return p.TradeType == TradeType.Buy ? +1 : -1;
            return 0;
        }

        // Most recent entry price across our open positions; NaN if none.
        private double LastEntryPrice()
        {
            DateTime latest = DateTime.MinValue;
            double price = double.NaN;
            foreach (var p in Positions)
            {
                if (p.Label != Label || p.SymbolName != SymbolName) continue;
                if (p.EntryTime > latest)
                {
                    latest = p.EntryTime;
                    price = p.EntryPrice;
                }
            }
            return price;
        }

        private void CloseAllOpenPositions(string reason)
        {
            // Snapshot first — closing mutates Positions.
            var toClose = new List<Position>();
            foreach (var p in Positions)
                if (p.Label == Label && p.SymbolName == SymbolName) toClose.Add(p);
            foreach (var p in toClose)
            {
                var r = ClosePosition(p);
                if (!r.IsSuccessful) Print("Close ({0}) failed: {1}", reason, r.Error);
            }
        }

        private DateTime ToEt(DateTime serverTime)
        {
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
            try { return TimeZoneInfo.FindSystemTimeZoneById("America/New_York"); }
            catch { return TimeZoneInfo.FindSystemTimeZoneById("Eastern Standard Time"); }
        }
    }
}
