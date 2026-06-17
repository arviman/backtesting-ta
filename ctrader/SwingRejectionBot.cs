// SwingRejectionBot — cTrader Algo C# cBot.
//
// "Smart-money" style swing rejection / change-of-character for 30M.
//
// Idea
//   1. Find swing highs / swing lows as N-bar fractals.
//   2. Classify regime from the last two swings on each side:
//        uptrend  = lastSwingHigh > prevSwingHigh && lastSwingLow > prevSwingLow
//        downtrend = inverse
//   3. SHORT continuation: regime is uptrend, current bar tags
//      lastSwingHigh from below (lower high) and closes back well under
//      its own wick → rejection.
//   4. LONG continuation: mirror at lastSwingLow during downtrend.
//
// Stop: N × ATR(14) above (short) / below (long) entry.
// Target: TP:SL ratio × stop distance. One open position by label.
//
// Indexing: cTrader index 0 = OLDEST; Last(0) = forming bar.
// Signals evaluate on Last(1) (most recent closed bar).

using System;
using cAlgo.API;
using cAlgo.API.Indicators;
using cAlgo.API.Internals;

namespace cAlgo.Robots
{
    [Robot(AccessRights = AccessRights.None)]
    public class SwingRejectionBot : Robot
    {
        // ────── Sizing ──────
        [Parameter("Volume (lots)", DefaultValue = 0.05, MinValue = 0.01, Step = 0.01, Group = "Sizing")]
        public double VolumeLots { get; set; }

        [Parameter("Label", DefaultValue = "SwingRej", Group = "Sizing")]
        public string Label { get; set; }

        // ────── Structure ──────
        [Parameter("Pivot window (bars each side)", DefaultValue = 5, MinValue = 2, Step = 1, Group = "Structure")]
        public int PivotWindow { get; set; }

        // How close the current bar must come to the prior swing level
        // before we call it a "tag" (in ATR units).
        [Parameter("Tag tolerance × ATR(14)", DefaultValue = 0.5, MinValue = 0.05, Step = 0.05, Group = "Structure")]
        public double TagAtrMult { get; set; }

        // Minimum wick (high − close for shorts, close − low for longs)
        // for the rejection to count, in ATR units.
        [Parameter("Wick × ATR(14)", DefaultValue = 0.5, MinValue = 0.1, Step = 0.05, Group = "Structure")]
        public double WickAtrMult { get; set; }

        // ────── Risk ──────
        [Parameter("SL × ATR(14)", DefaultValue = 2.0, MinValue = 0.5, Step = 0.5, Group = "Risk")]
        public double SlAtrMult { get; set; }

        [Parameter("TP : SL ratio", DefaultValue = 2.0, MinValue = 0.5, Step = 0.5, Group = "Risk")]
        public double TpSlRatio { get; set; }

        private AverageTrueRange _atr;

        // ────── Swing state ──────
        // Last two confirmed swings on each side (most recent = "last").
        private double _lastSwingHigh = double.NaN, _prevSwingHigh = double.NaN;
        private double _lastSwingLow = double.NaN, _prevSwingLow = double.NaN;

        protected override void OnStart()
        {
            _atr = Indicators.AverageTrueRange(14, MovingAverageType.Simple);
            Print("SwingRejectionBot ready. vol={0} lot, pivot={1}, tag×ATR={2}, wick×ATR={3}, SL={4}×ATR, TP:SL={5}",
                  VolumeLots, PivotWindow, TagAtrMult, WickAtrMult, SlAtrMult, TpSlRatio);
        }

        protected override void OnBar()
        {
            int needed = 2 * PivotWindow + 3;
            if (Bars.Count < needed) return;

            // --- Confirm a swing on the bar at Last(PivotWindow+1) ---
            int pivotOffset = PivotWindow + 1;
            UpdateSwings(pivotOffset);

            if (double.IsNaN(_lastSwingHigh) || double.IsNaN(_prevSwingHigh)
             || double.IsNaN(_lastSwingLow) || double.IsNaN(_prevSwingLow))
                return; // not enough history yet

            // --- Read the most recent closed bar ---
            double high = Bars.HighPrices.Last(1);
            double low = Bars.LowPrices.Last(1);
            double close = Bars.ClosePrices.Last(1);
            double atr = _atr.Result.Last(1);

            bool uptrend = _lastSwingHigh > _prevSwingHigh && _lastSwingLow > _prevSwingLow;
            bool downtrend = _lastSwingHigh < _prevSwingHigh && _lastSwingLow < _prevSwingLow;

            // SHORT: in uptrend, bar tags lastSwingHigh from below with a real wick.
            bool tagsHigh = high >= _lastSwingHigh - TagAtrMult * atr && high <= _lastSwingHigh + TagAtrMult * atr;
            bool rejectedDown = (high - close) >= WickAtrMult * atr && close < _lastSwingHigh;
            bool shortEntry = uptrend && tagsHigh && rejectedDown;

            // LONG: in downtrend, bar tags lastSwingLow from above with a real wick.
            bool tagsLow = low <= _lastSwingLow + TagAtrMult * atr && low >= _lastSwingLow - TagAtrMult * atr;
            bool rejectedUp = (close - low) >= WickAtrMult * atr && close > _lastSwingLow;
            bool longEntry = downtrend && tagsLow && rejectedUp;

            // --- Position management ---
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

        // Check whether the bar at Last(pivotOffset) is a fractal swing
        // (highest/lowest over [Last(1) .. Last(2*PivotWindow+1)]).
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

        protected override void OnStop() { Print("SwingRejectionBot stopped."); }
    }
}
