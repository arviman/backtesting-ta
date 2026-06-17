// SwingRejectionBotV2 — SwingRejectionBot + BOS/CHoCH gate + HTF regime gate.
//
// Both gates are individually toggleable so the optimizer can tell us
// whether they add edge.
//
// CHoCH gate (Change of Character):
//   When a rejection setup fires, instead of entering immediately we mark a
//   pending entry and wait up to N bars for price to break the opposing
//   swing (short: close < lastSwingLow; long: close > lastSwingHigh). That
//   break = structure broken = trend likely flipping. Enter on the break.
//   If the timer expires without a break, the setup is cancelled.
//
// HTF regime gate:
//   Pull a single SMA on a higher timeframe (default H4). Long only if
//   close > HTF SMA; short only if close < HTF SMA. Filters trades against
//   the bigger trend.
//
// Indexing: cTrader index 0 = OLDEST. Signals read Last(1) (closed bar).

using System;
using cAlgo.API;
using cAlgo.API.Indicators;
using cAlgo.API.Internals;

namespace cAlgo.Robots
{
    public enum HtfChoice
    {
        H1,
        H4,
        D1,
    }

    [Robot(AccessRights = AccessRights.None)]
    public class SwingRejectionBotV2 : Robot
    {
        // ────── Sizing ──────
        [Parameter("Volume (lots)", DefaultValue = 1, MinValue = 0.01, Step = 0.01, Group = "Sizing")]
        public double VolumeLots { get; set; }

        [Parameter("Label", DefaultValue = "SwingRejV2", Group = "Sizing")]
        public string Label { get; set; }

        // ────── Structure ──────
        [Parameter("Pivot window (bars each side)", DefaultValue = 3, MinValue = 2, Step = 1, Group = "Structure")]
        public int PivotWindow { get; set; }

        [Parameter("Tag tolerance × ATR(14)", DefaultValue = 0.45, MinValue = 0.05, Step = 0.05, Group = "Structure")]
        public double TagAtrMult { get; set; }

        [Parameter("Wick × ATR(14)", DefaultValue = 0.25, MinValue = 0.05, Step = 0.05, Group = "Structure")]
        public double WickAtrMult { get; set; }

        // ────── CHoCH gate ──────
        [Parameter("Require CHoCH confirm", DefaultValue = true, Group = "CHoCH")]
        public bool RequireChoch { get; set; }

        [Parameter("CHoCH lookback (bars)", DefaultValue = 8, MinValue = 1, Step = 1, Group = "CHoCH")]
        public int ChochLookback { get; set; }

        // ────── HTF gate ──────
        [Parameter("Use HTF trend filter", DefaultValue = true, Group = "HTF")]
        public bool UseHtfFilter { get; set; }

        [Parameter("HTF timeframe", DefaultValue = HtfChoice.H4, Group = "HTF")]
        public HtfChoice HtfTf { get; set; }

        [Parameter("HTF MA length", DefaultValue = 60, MinValue = 5, Step = 5, Group = "HTF")]
        public int HtfMaLength { get; set; }

        // ────── Risk ──────
        [Parameter("SL × ATR(14)", DefaultValue = 2, MinValue = 0.5, Step = 0.5, Group = "Risk")]
        public double SlAtrMult { get; set; }

        [Parameter("TP : SL ratio", DefaultValue = 2.5, MinValue = 0.5, Step = 0.5, Group = "Risk")]
        public double TpSlRatio { get; set; }

        private AverageTrueRange _atr;
        private Bars _htfBars;
        private SimpleMovingAverage _htfSma;

        // Swing state
        private double _lastSwingHigh = double.NaN, _prevSwingHigh = double.NaN;
        private double _lastSwingLow = double.NaN, _prevSwingLow = double.NaN;

        // Pending CHoCH state
        private int _pendingShortBars = 0;
        private double _pendingShortLevel = double.NaN;
        private int _pendingLongBars = 0;
        private double _pendingLongLevel = double.NaN;

        protected override void OnStart()
        {
            _atr = Indicators.AverageTrueRange(14, MovingAverageType.Simple);

            if (UseHtfFilter)
            {
                _htfBars = MarketData.GetBars(MapTimeFrame(HtfTf));
                _htfSma = Indicators.SimpleMovingAverage(_htfBars.ClosePrices, HtfMaLength);
            }

            Print("SwingRejectionBotV2 ready. pivot={0}, tag×ATR={1}, wick×ATR={2}, SL={3}×ATR, TP:SL={4}, CHoCH={5}(lb={6}), HTF={7}({8}, sma{9})",
                  PivotWindow, TagAtrMult, WickAtrMult, SlAtrMult, TpSlRatio,
                  RequireChoch, ChochLookback, UseHtfFilter, HtfTf, HtfMaLength);
        }

        protected override void OnBar()
        {
            int needed = 2 * PivotWindow + 3;
            if (Bars.Count < needed) return;

            UpdateSwings(PivotWindow + 1);

            if (double.IsNaN(_lastSwingHigh) || double.IsNaN(_prevSwingHigh)
             || double.IsNaN(_lastSwingLow) || double.IsNaN(_prevSwingLow))
                return;

            double high = Bars.HighPrices.Last(1);
            double low = Bars.LowPrices.Last(1);
            double close = Bars.ClosePrices.Last(1);
            double atr = _atr.Result.Last(1);

            bool uptrend = _lastSwingHigh > _prevSwingHigh && _lastSwingLow > _prevSwingLow;
            bool downtrend = _lastSwingHigh < _prevSwingHigh && _lastSwingLow < _prevSwingLow;

            bool tagsHigh = Math.Abs(high - _lastSwingHigh) <= TagAtrMult * atr;
            bool rejectedDown = (high - close) >= WickAtrMult * atr && close < _lastSwingHigh;
            bool shortSetup = uptrend && tagsHigh && rejectedDown;

            bool tagsLow = Math.Abs(low - _lastSwingLow) <= TagAtrMult * atr;
            bool rejectedUp = (close - low) >= WickAtrMult * atr && close > _lastSwingLow;
            bool longSetup = downtrend && tagsLow && rejectedUp;

            bool htfBullish = !UseHtfFilter || close > _htfSma.Result.LastValue;
            bool htfBearish = !UseHtfFilter || close < _htfSma.Result.LastValue;

            bool shortEntry, longEntry;
            if (RequireChoch)
            {
                // Arm / re-arm pending setups
                if (shortSetup) { _pendingShortBars = ChochLookback; _pendingShortLevel = _lastSwingLow; }
                if (longSetup) { _pendingLongBars = ChochLookback; _pendingLongLevel = _lastSwingHigh; }

                // Check for confirmation breaks
                shortEntry = _pendingShortBars > 0 && close < _pendingShortLevel;
                longEntry = _pendingLongBars > 0 && close > _pendingLongLevel;

                // Decrement timers; clear on fire
                if (shortEntry) { _pendingShortBars = 0; }
                else if (_pendingShortBars > 0) _pendingShortBars--;
                if (longEntry) { _pendingLongBars = 0; }
                else if (_pendingLongBars > 0) _pendingLongBars--;
            }
            else
            {
                shortEntry = shortSetup;
                longEntry = longSetup;
            }

            shortEntry = shortEntry && htfBearish;
            longEntry = longEntry && htfBullish;

            var open = FindOpenPosition();
            if (open != null)
            {
                bool isLong = open.TradeType == TradeType.Buy;
                bool flip = (isLong && shortEntry) || (!isLong && longEntry);
                if (flip) { ClosePosition(open); return; }
                return;
            }

            if (longEntry) OpenPosition(TradeType.Buy, atr);
            else if (shortEntry) OpenPosition(TradeType.Sell, atr);
        }

        private void UpdateSwings(int pivotOffset)
        {
            double pivHigh = Bars.HighPrices.Last(pivotOffset);
            double pivLow = Bars.LowPrices.Last(pivotOffset);
            bool isSwingHigh = true, isSwingLow = true;

            int last = 2 * PivotWindow + 1;
            for (int k = 1; k <= last; k++)
            {
                if (k == pivotOffset) continue;
                double h = Bars.HighPrices.Last(k);
                double l = Bars.LowPrices.Last(k);
                if (h >= pivHigh) isSwingHigh = false;
                if (l <= pivLow) isSwingLow = false;
                if (!isSwingHigh && !isSwingLow) break;
            }

            if (isSwingHigh && pivHigh != _lastSwingHigh)
            {
                _prevSwingHigh = _lastSwingHigh;
                _lastSwingHigh = pivHigh;
            }
            if (isSwingLow && pivLow != _lastSwingLow)
            {
                _prevSwingLow = _lastSwingLow;
                _lastSwingLow = pivLow;
            }
        }

        private static TimeFrame MapTimeFrame(HtfChoice c)
        {
            switch (c)
            {
                case HtfChoice.H1: return TimeFrame.Hour;
                case HtfChoice.H4: return TimeFrame.Hour4;
                case HtfChoice.D1: return TimeFrame.Daily;
                default: return TimeFrame.Hour4;
            }
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
            if (!result.IsSuccessful)
                Print("Entry failed ({0}): {1}", type, result.Error);
        }

        private Position FindOpenPosition()
        {
            foreach (var p in Positions)
                if (p.Label == Label && p.SymbolName == SymbolName) return p;
            return null;
        }

        protected override void OnStop() { Print("SwingRejectionBotV2 stopped."); }
    }
}
