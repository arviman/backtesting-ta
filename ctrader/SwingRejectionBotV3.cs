// SwingRejectionBotV3 — V2 + BOS continuation entries + FVG order-block entries.
//
// Three entry sources, each individually toggleable. The HTF trend gate
// from V2 applies to all of them when enabled.
//
//   1. Rejection (+ optional CHoCH confirm) — same as V2.
//   2. BOS continuation — in a confirmed HH/HL regime, fire LONG on the
//      bar that closes above the last swing high (break of structure).
//      Mirror for SHORT on close below last swing low in LH/LL.
//   3. FVG mitigation — detect a 3-bar Fair Value Gap (the "order
//      block" in light SMC terms). When price later returns into that
//      gap zone for the first time, fire in the gap's direction.
//        Bullish FVG: high(Last(3)) < low(Last(1)) — buyer impulse.
//        Bearish FVG: low(Last(3))  > high(Last(1)) — seller impulse.
//      An FVG is "mitigated" (consumed) once a bar's close enters its
//      zone; subsequent mitigations of the same zone are ignored.
//
// Defaults below carry V2's optimizer winners as the rejection-path base,
// and ship BOS + FVG as OFF so the optimizer A/B's them.
//
// Indexing: cTrader 0 = OLDEST. All signals read Last(1) (closed bar).

using System;
using cAlgo.API;
using cAlgo.API.Indicators;
using cAlgo.API.Internals;

namespace cAlgo.Robots
{
    [Robot(AccessRights = AccessRights.None)]
    public class SwingRejectionBotV3 : Robot
    {
        // ponytail: nested to dodge namespace clash when V2 + V3 are loaded together.
        public enum HtfChoice { H1, H4, D1 }

        // ────── Sizing ──────
        [Parameter("Volume (lots)", DefaultValue = 1, MinValue = 0.01, Step = 0.01, Group = "Sizing")]
        public double VolumeLots { get; set; }

        [Parameter("Label", DefaultValue = "SwingRejV3", Group = "Sizing")]
        public string Label { get; set; }

        // ────── Structure (V2 winners) ──────
        [Parameter("Pivot window (bars each side)", DefaultValue = 3, MinValue = 2, Step = 1, Group = "Structure")]
        public int PivotWindow { get; set; }

        [Parameter("Tag tolerance × ATR(14)", DefaultValue = 0.45, MinValue = 0.05, Step = 0.05, Group = "Structure")]
        public double TagAtrMult { get; set; }

        [Parameter("Wick × ATR(14)", DefaultValue = 0.25, MinValue = 0.05, Step = 0.05, Group = "Structure")]
        public double WickAtrMult { get; set; }

        // ────── CHoCH gate (V2 winner) ──────
        [Parameter("Use rejection entries", DefaultValue = true, Group = "CHoCH")]
        public bool UseRejectionEntries { get; set; }

        [Parameter("Require CHoCH confirm", DefaultValue = true, Group = "CHoCH")]
        public bool RequireChoch { get; set; }

        [Parameter("CHoCH lookback (bars)", DefaultValue = 8, MinValue = 1, Step = 1, Group = "CHoCH")]
        public int ChochLookback { get; set; }

        // ────── BOS continuation ──────
        [Parameter("Use BOS continuation", DefaultValue = false, Group = "BOS")]
        public bool UseBosEntries { get; set; }

        // ────── FVG / Order Block ──────
        [Parameter("Use FVG entries", DefaultValue = false, Group = "FVG")]
        public bool UseFvgEntries { get; set; }

        // Max bars before an unfilled FVG is considered stale.
        [Parameter("FVG max age (bars)", DefaultValue = 50, MinValue = 5, Step = 5, Group = "FVG")]
        public int FvgMaxAge { get; set; }

        // ────── HTF gate (V2 winner) ──────
        [Parameter("Use HTF trend filter", DefaultValue = true, Group = "HTF")]
        public bool UseHtfFilter { get; set; }

        [Parameter("HTF timeframe", DefaultValue = HtfChoice.H4, Group = "HTF")]
        public HtfChoice HtfTf { get; set; }

        [Parameter("HTF MA length", DefaultValue = 60, MinValue = 5, Step = 5, Group = "HTF")]
        public int HtfMaLength { get; set; }

        // ────── Risk (V2 winner) ──────
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

        // FVG state (one active per side; replaced when a fresher one forms).
        private struct FvgZone { public double Low; public double High; public int Age; public bool Mitigated; }

        private FvgZone _bullFvg = new FvgZone { Low = double.NaN, High = double.NaN, Age = 0, Mitigated = true };
        private FvgZone _bearFvg = new FvgZone { Low = double.NaN, High = double.NaN, Age = 0, Mitigated = true };

        protected override void OnStart()
        {
            _atr = Indicators.AverageTrueRange(14, MovingAverageType.Simple);
            if (UseHtfFilter)
            {
                _htfBars = MarketData.GetBars(MapTimeFrame(HtfTf));
                _htfSma = Indicators.SimpleMovingAverage(_htfBars.ClosePrices, HtfMaLength);
            }
            Print("SwingRejectionBotV3 ready. sources rej={0} bos={1} fvg={2}, choch={3}, htf={4}, SL={5}×ATR, TP:SL={6}",
                  UseRejectionEntries, UseBosEntries, UseFvgEntries, RequireChoch, UseHtfFilter, SlAtrMult, TpSlRatio);
        }

        protected override void OnBar()
        {
            int needed = 2 * PivotWindow + 4;
            if (Bars.Count < needed) return;

            UpdateSwings(PivotWindow + 1);
            UpdateFvgs();

            if (double.IsNaN(_lastSwingHigh) || double.IsNaN(_prevSwingHigh)
             || double.IsNaN(_lastSwingLow) || double.IsNaN(_prevSwingLow))
                return;

            double high = Bars.HighPrices.Last(1);
            double low = Bars.LowPrices.Last(1);
            double close = Bars.ClosePrices.Last(1);
            double closePrev = Bars.ClosePrices.Last(2);
            double atr = _atr.Result.Last(1);

            bool uptrend = _lastSwingHigh > _prevSwingHigh && _lastSwingLow > _prevSwingLow;
            bool downtrend = _lastSwingHigh < _prevSwingHigh && _lastSwingLow < _prevSwingLow;

            // ── Source 1: rejection (± CHoCH confirm) ──
            bool tagsHigh = Math.Abs(high - _lastSwingHigh) <= TagAtrMult * atr;
            bool rejectedDown = (high - close) >= WickAtrMult * atr && close < _lastSwingHigh;
            bool shortSetup = UseRejectionEntries && uptrend && tagsHigh && rejectedDown;

            bool tagsLow = Math.Abs(low - _lastSwingLow) <= TagAtrMult * atr;
            bool rejectedUp = (close - low) >= WickAtrMult * atr && close > _lastSwingLow;
            bool longSetup = UseRejectionEntries && downtrend && tagsLow && rejectedUp;

            bool rejShort, rejLong;
            if (RequireChoch && UseRejectionEntries)
            {
                if (shortSetup) { _pendingShortBars = ChochLookback; _pendingShortLevel = _lastSwingLow; }
                if (longSetup) { _pendingLongBars = ChochLookback; _pendingLongLevel = _lastSwingHigh; }

                rejShort = _pendingShortBars > 0 && close < _pendingShortLevel;
                rejLong = _pendingLongBars > 0 && close > _pendingLongLevel;

                if (rejShort) _pendingShortBars = 0;
                else if (_pendingShortBars > 0) _pendingShortBars--;
                if (rejLong) _pendingLongBars = 0;
                else if (_pendingLongBars > 0) _pendingLongBars--;
            }
            else { rejShort = shortSetup; rejLong = longSetup; }

            // ── Source 2: BOS continuation ──
            bool bosLong = UseBosEntries && uptrend
                && closePrev <= _lastSwingHigh && close > _lastSwingHigh;
            bool bosShort = UseBosEntries && downtrend
                && closePrev >= _lastSwingLow && close < _lastSwingLow;

            // ── Source 3: FVG mitigation (price closes back into the gap) ──
            bool fvgLong = UseFvgEntries && !_bullFvg.Mitigated && !double.IsNaN(_bullFvg.Low)
                && close >= _bullFvg.Low && close <= _bullFvg.High;
            bool fvgShort = UseFvgEntries && !_bearFvg.Mitigated && !double.IsNaN(_bearFvg.Low)
                && close >= _bearFvg.Low && close <= _bearFvg.High;
            if (fvgLong) _bullFvg.Mitigated = true;
            if (fvgShort) _bearFvg.Mitigated = true;

            // ── Combine + HTF gate ──
            bool htfBullish = !UseHtfFilter || close > _htfSma.Result.LastValue;
            bool htfBearish = !UseHtfFilter || close < _htfSma.Result.LastValue;

            bool longEntry = (rejLong || bosLong || fvgLong) && htfBullish;
            bool shortEntry = (rejShort || bosShort || fvgShort) && htfBearish;

            // ── Position management ──
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
            { _prevSwingHigh = _lastSwingHigh; _lastSwingHigh = pivHigh; }
            if (isSwingLow && pivLow != _lastSwingLow)
            { _prevSwingLow = _lastSwingLow; _lastSwingLow = pivLow; }
        }

        // Inspect the 3-bar window ending one bar back so its middle bar is
        // confirmed: highs/lows at Last(3), Last(2), Last(1).
        //   Bullish FVG: high(Last(3)) < low(Last(1)) → zone = [high(3), low(1)]
        //   Bearish FVG: low(Last(3)) > high(Last(1)) → zone = [high(1), low(3)]
        private void UpdateFvgs()
        {
            // Age existing zones and stale them out.
            if (!double.IsNaN(_bullFvg.Low)) { _bullFvg.Age++; if (_bullFvg.Age > FvgMaxAge) _bullFvg.Mitigated = true; }
            if (!double.IsNaN(_bearFvg.Low)) { _bearFvg.Age++; if (_bearFvg.Age > FvgMaxAge) _bearFvg.Mitigated = true; }

            double h3 = Bars.HighPrices.Last(3);
            double l3 = Bars.LowPrices.Last(3);
            double h1 = Bars.HighPrices.Last(1);
            double l1 = Bars.LowPrices.Last(1);

            if (h3 < l1)
                _bullFvg = new FvgZone { Low = h3, High = l1, Age = 0, Mitigated = false };
            else if (l3 > h1)
                _bearFvg = new FvgZone { Low = h1, High = l3, Age = 0, Mitigated = false };
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
            if (!result.IsSuccessful) Print("Entry failed ({0}): {1}", type, result.Error);
        }

        private Position FindOpenPosition()
        {
            foreach (var p in Positions)
                if (p.Label == Label && p.SymbolName == SymbolName) return p;
            return null;
        }

        protected override void OnStop() { Print("SwingRejectionBotV3 stopped."); }
    }
}
