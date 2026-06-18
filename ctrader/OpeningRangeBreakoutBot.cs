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

        [Parameter("Use breakout-window filter", DefaultValue = false, Group = "Filters")]
        public bool UseBreakoutWindow { get; set; }

        // Only allow entries this many hours after day-start (i.e. skip
        // the opening-range bars themselves). Set higher to skip stale
        // late-day breaks.
        [Parameter("Breakout window end hour", DefaultValue = 20, MinValue = 0, MaxValue = 23, Step = 1, Group = "Filters")]
        public int BreakoutWindowEnd { get; set; }

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
            Print("ORB ready. dayStart={0}, ORbars={1}, HTF={2}(D1, sma{3}), winEnd={4}, SL={5}×ATR, TP:SL={6}",
                  DayStartHour, OpeningBars, UseHtfFilter, HtfMaLength, BreakoutWindowEnd, SlAtrMult, TpSlRatio);
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

            // Optional breakout window: stop trading new breaks after a cutoff hour.
            if (UseBreakoutWindow && openTime.Hour >= BreakoutWindowEnd) return;

            bool htfBullish = !UseHtfFilter || close > _htfSma.Result.LastValue;
            bool htfBearish = !UseHtfFilter || close < _htfSma.Result.LastValue;

            bool longEntry = close > _orHigh && htfBullish;
            bool shortEntry = close < _orLow && htfBearish;

            if (FindOpenPosition() != null) return;

            if (longEntry) { OpenPosition(TradeType.Buy, atr); _tradedToday = true; }
            else if (shortEntry) { OpenPosition(TradeType.Sell, atr); _tradedToday = true; }
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
