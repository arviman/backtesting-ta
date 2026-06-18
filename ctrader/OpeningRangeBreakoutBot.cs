// OpeningRangeBreakoutBot — cTrader Algo C# cBot (H4).
//
// Mark the high/low of the first N H4 bars of the broker's trading day,
// then trade the first close that breaks above (long) or below (short)
// that opening range. One trade per day. Optional HTF SMA bias gate
// and optional active-session window.
//
// Why this works on H4 forex: Asian-session range tends to be tight;
// London/NY opens drive a directional break out of that range. Trading
// only the FIRST break of the day catches the impulse and ignores
// later chop. The HTF gate filters days when the bigger trend disagrees.
//
// Indexing: cTrader 0 = OLDEST. All signals read Last(1) (closed bar).

using System;
using cAlgo.API;
using cAlgo.API.Indicators;
using cAlgo.API.Internals;

namespace cAlgo.Robots
{
    [Robot(AccessRights = AccessRights.None)]
    public class OpeningRangeBreakoutBot : Robot
    {
        // ────── Sizing ──────
        [Parameter("Volume (lots)", DefaultValue = 1, MinValue = 0.01, Step = 0.01, Group = "Sizing")]
        public double VolumeLots { get; set; }

        [Parameter("Label", DefaultValue = "ORB", Group = "Sizing")]
        public string Label { get; set; }

        // ────── Opening range ──────
        // Day rolls at this hour (broker server time). 0 = midnight.
        // Set to e.g. 22 to anchor the "day" to NY session close.
        [Parameter("Day start hour", DefaultValue = 0, MinValue = 0, MaxValue = 23, Step = 1, Group = "Range")]
        public int DayStartHour { get; set; }

        // Number of bars that define the opening range (counted from day
        // start). On H4 this is each 4-hour bar, so 2 ≈ first 8 hours
        // = roughly the Asian session.
        [Parameter("Opening bars", DefaultValue = 2, MinValue = 1, MaxValue = 6, Step = 1, Group = "Range")]
        public int OpeningBars { get; set; }

        // ────── Filters ──────
        [Parameter("Use HTF trend filter", DefaultValue = true, Group = "Filters")]
        public bool UseHtfFilter { get; set; }

        [Parameter("HTF MA length (Daily SMA)", DefaultValue = 50, MinValue = 5, Step = 5, Group = "Filters")]
        public int HtfMaLength { get; set; }

        // ────── Liquidity sweep / false-break reversal ──────
        // Instead of trading the breakout, take the *failed* break: a bar
        // wicks beyond the OR but closes back inside (stop hunt). Same
        // signature as V2's sweep entry, scoped to the OR levels.
        //   Sweep long  : low(1)  < orLow  && close(1) > orLow  + wick
        //   Sweep short : high(1) > orHigh && close(1) < orHigh + wick
        // Breakout and sweep are independently toggleable so the optimizer
        // can choose either, both, or neither.
        [Parameter("Use breakout entries", DefaultValue = true, Group = "Entries")]
        public bool UseBreakoutEntries { get; set; }

        [Parameter("Use liquidity sweep entries", DefaultValue = false, Group = "Entries")]
        public bool UseSweepEntries { get; set; }

        [Parameter("Sweep wick × ATR(14)", DefaultValue = 0.4, MinValue = 0.05, Step = 0.05, Group = "Entries")]
        public double WickAtrMult { get; set; }

        // ────── Time-of-day filter ──────
        // Hours are in BROKER SERVER time (read from bar OpenTime).
        // Wraparound supported: end < start crosses midnight.
        [Parameter("Use time-of-day filter", DefaultValue = false, Group = "Time")]
        public bool UseTimeFilter { get; set; }

        [Parameter("Active start hour", DefaultValue = 7, MinValue = 0, MaxValue = 23, Step = 1, Group = "Time")]
        public int StartHour { get; set; }

        [Parameter("Active end hour (exclusive)", DefaultValue = 21, MinValue = 0, MaxValue = 23, Step = 1, Group = "Time")]
        public int EndHour { get; set; }

        // ────── Risk ──────
        [Parameter("SL × ATR(14)", DefaultValue = 2.0, MinValue = 0.5, Step = 0.5, Group = "Risk")]
        public double SlAtrMult { get; set; }

        [Parameter("TP : SL ratio", DefaultValue = 2.0, MinValue = 0.5, Step = 0.5, Group = "Risk")]
        public double TpSlRatio { get; set; }

        private AverageTrueRange _atr;
        private Bars _htfBars;
        private SimpleMovingAverage _htfSma;

        // Per-day opening-range state.
        private int _currentDay = -1;
        private int _barsThisDay = 0;
        private double _orHigh = double.NaN;
        private double _orLow = double.NaN;
        private bool _orComplete = false;
        private bool _tradedToday = false;

        protected override void OnStart()
        {
            _atr = Indicators.AverageTrueRange(14, MovingAverageType.Simple);
            if (UseHtfFilter)
            {
                _htfBars = MarketData.GetBars(TimeFrame.Daily);
                _htfSma = Indicators.SimpleMovingAverage(_htfBars.ClosePrices, HtfMaLength);
            }
            Print("ORB ready. dayStart={0}, ORbars={1}, HTF={2}(D1, sma{3}), breakout={4}, sweep={5}, time={6} ({7}→{8}), SL={9}×ATR, TP:SL={10}",
                  DayStartHour, OpeningBars, UseHtfFilter, HtfMaLength,
                  UseBreakoutEntries, UseSweepEntries,
                  UseTimeFilter, StartHour, EndHour, SlAtrMult, TpSlRatio);
        }

        protected override void OnBar()
        {
            if (Bars.Count < 5) return;

            var openTime = Bars.OpenTimes.Last(1);
            int dayKey = DayKey(openTime);

            // Day rollover: reset opening-range tracking.
            if (dayKey != _currentDay)
            {
                _currentDay = dayKey;
                _barsThisDay = 0;
                _orHigh = double.NaN;
                _orLow = double.NaN;
                _orComplete = false;
                _tradedToday = false;
            }

            double high = Bars.HighPrices.Last(1);
            double low = Bars.LowPrices.Last(1);
            double close = Bars.ClosePrices.Last(1);
            double atr = _atr.Result.Last(1);

            _barsThisDay++;

            // Accumulate the opening range over the first N bars.
            if (_barsThisDay <= OpeningBars)
            {
                _orHigh = double.IsNaN(_orHigh) ? high : Math.Max(_orHigh, high);
                _orLow = double.IsNaN(_orLow) ? low : Math.Min(_orLow, low);
                if (_barsThisDay == OpeningBars) _orComplete = true;
                return;
            }

            if (!_orComplete || _tradedToday) return;

            if (UseTimeFilter && !InActiveSession(openTime.Hour)) return;

            bool htfBullish = !UseHtfFilter || close > _htfSma.Result.LastValue;
            bool htfBearish = !UseHtfFilter || close < _htfSma.Result.LastValue;

            // Breakout: close clears the OR in the trend direction.
            bool brkLong = UseBreakoutEntries && close > _orHigh;
            bool brkShort = UseBreakoutEntries && close < _orLow;

            // Sweep: bar wicks beyond the OR but closes back inside.
            bool sweepLong = UseSweepEntries
                && low < _orLow && close > _orLow
                && (close - low) >= WickAtrMult * atr;
            bool sweepShort = UseSweepEntries
                && high > _orHigh && close < _orHigh
                && (high - close) >= WickAtrMult * atr;

            bool longEntry = (brkLong || sweepLong) && htfBullish;
            bool shortEntry = (brkShort || sweepShort) && htfBearish;

            if (FindOpenPosition() != null) return;

            if (longEntry) { OpenPosition(TradeType.Buy, atr); _tradedToday = true; }
            else if (shortEntry) { OpenPosition(TradeType.Sell, atr); _tradedToday = true; }
        }

        private bool InActiveSession(int hour)
        {
            return StartHour < EndHour
                ? hour >= StartHour && hour < EndHour
                : hour >= StartHour || hour < EndHour;
        }

        // Day key based on DayStartHour: bars before DayStartHour belong to "yesterday".
        private int DayKey(DateTime t)
        {
            var anchored = t.AddHours(-DayStartHour);
            return anchored.Year * 10000 + anchored.Month * 100 + anchored.Day;
        }

        private void OpenPosition(TradeType type, double atr)
        {
            double slDistance = SlAtrMult * atr;
            double tpDistance = slDistance * TpSlRatio;
            double slPips = slDistance / Symbol.PipSize;
            double tpPips = tpDistance / Symbol.PipSize;

            double volume = Symbol.QuantityToVolumeInUnits(VolumeLots);
            volume = Symbol.NormalizeVolumeInUnits(volume, RoundingMode.ToNearest);

            var result = ExecuteMarketOrder(type, SymbolName, volume, Label, slPips, tpPips);
            if (!result.IsSuccessful) Print("Entry failed ({0}): {1}", type, result.Error);
        }

        private Position FindOpenPosition()
        {
            foreach (var p in Positions)
                if (p.Label == Label && p.SymbolName == SymbolName) return p;
            return null;
        }

        protected override void OnStop() { Print("ORB stopped."); }
    }
}
