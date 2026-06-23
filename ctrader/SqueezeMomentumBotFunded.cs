// SqueezeMomentumBotFunded — cTrader Algo C# cBot (FUNDED variant)
//
// Port of LazyBear's Squeeze Momentum indicator into a trading bot,
// configured for the5ers / FTMO **funded accounts** (or personal
// capital) where the strict $5k max-daily-loss rule of the evaluation
// phase has been relaxed.
//
// **Use only after passing both evaluation phases.** The structural SL
// roughly doubles absolute profit ($27k → $59k over 3yr OS at 5 ETH per
// entry) but lets daily floating loss reach $5,800 — which busts the
// eval daily rule. On a funded account where that rule is loosened (or
// personal capital with no DD ceiling), it's the right trade-off.
// Use SqueezeMomentumBotEval.cs while evaluating.
//
// Defaults (Kotlin backtest winners for funded / personal):
//   Squeeze params      : BB 11/2.3, KC 22/1.4
//   HTF filter          : H1 SMA(47)
//   SL                  : 47 pips static FLOOR; widens to N-bar swing
//                         low/high + buffer when farther
//   SL lookback         : 80 bars (~3.3 days on H1) — empirical winner
//   SL buffer           : 5 pips past the swing
//   TP multiplier       : 1.0
//   Max positions       : 4
//   Volume              : 0.5 lots (= 5 ETH on a 1lot=10ETH broker)
//
// Funded-phase backtest (3yr ETHUSD H1 OS at 5 ETH per entry, lb=80):
//   trades 2,734 / win 39.2% / PF 1.21
//   profit +$59,400 / peak DD $26,862 / DailyMax $5,837
//
// CHECK YOUR FUNDED PROGRAM'S RULES BEFORE GOING LIVE.
// If the funded account keeps the same $5k daily-loss rule as the eval,
// drop VolumeInLots to 0.3 (3 ETH per entry) — that caps daily floating
// loss around $3.5k while still capturing most of the structural-SL
// upside (~$36k profit over 3yr).

using System;
using cAlgo.API;
using cAlgo.API.Indicators;
using cAlgo.API.Internals;

namespace cAlgo.Robots
{
    [Robot(TimeZone = TimeZones.UTC, AccessRights = AccessRights.None)]
    public class SqueezeMomentumBotFunded : Robot
    {
        // ────── Squeeze (LazyBear formula) ──────
        [Parameter("BB Length", Group = "Squeeze", DefaultValue = 11, MinValue = 5, MaxValue = 50, Step = 1)]
        public int BbLength { get; set; }

        [Parameter("BB MultFactor", Group = "Squeeze", DefaultValue = 2.3, MinValue = 0.1, Step = 0.1)]
        public double BbMult { get; set; }

        [Parameter("KC Length", Group = "Squeeze", DefaultValue = 22, MinValue = 2)]
        public int KcLength { get; set; }

        [Parameter("KC MultFactor", Group = "Squeeze", DefaultValue = 1.4, MinValue = 0.1, Step = 0.1)]
        public double KcMult { get; set; }

        // ────── HTF Trend Filter (pinned to H1 SMA 47 — empirical winner) ──────
        // Exposed if you want to optimize a different asset/TF.
        public HtfOption HigherTimeframe = HtfOption.H1;
        public int HtfSmaPeriod = 47;

        // ────── Trade Settings ──────
        [Parameter("Trade Volume (Lots)", Group = "Trade", DefaultValue = 0.5, MinValue = 0.001, Step = 0.001)]
        public double VolumeInLots { get; set; }

        [Parameter("Stop Loss (pips, floor)", Group = "Trade", DefaultValue = 47, MinValue = 1)]
        public int StopLossPips { get; set; }

        // ─────────────────────────────────────────────────────────────────
        // Structural SL — ENABLED on the funded bot (lb=80 default).
        // ─────────────────────────────────────────────────────────────────
        // At entry the SL is placed at the lower of (entry − StopLossPips)
        // and (recent N-bar low − SlBufferPips). For shorts, mirror with
        // recent N-bar high. StopLossPips acts as a floor — SL never
        // tighter than 47 pips. Set SlLookbackBars=0 to fall back to
        // pure static SL (the eval bot's behavior).
        [Parameter("SL Lookback Bars", Group = "Trade", DefaultValue = 80, MinValue = 0, MaxValue = 500, Step = 10)]
        public int SlLookbackBars { get; set; }

        [Parameter("SL Buffer (pips)", Group = "Trade", DefaultValue = 5, MinValue = 0, MaxValue = 50, Step = 1)]
        public int SlBufferPips { get; set; }

        [Parameter("TP Multiplier (× SL, 0 = off)", Group = "Trade", DefaultValue = 1.0, MinValue = 0.0, MaxValue = 5, Step = 0.1)]
        public double TpMultiplier { get; set; }

        [Parameter("Max Open Positions", Group = "Trade", DefaultValue = 4, MinValue = 1, MaxValue = 10, Step = 1)]
        public int MaxPositions { get; set; }

        // ────── Pyramiding gates ──────
        // Backtest: MinEntryDistance, SlStagger, TpStagger all hurt risk-adjusted
        // return. Hidden from the optimizer (kept as const 0); only the profit
        // gate is exposed as it's the one improvement worth keeping ON.
        // [Parameter("Min Distance Between Entries (pips)", Group = "Pyramiding", DefaultValue = 0, MinValue = 0)]
        // public int MinEntryDistancePips { get; set; }
        private int MinEntryDistancePips = 0;

        [Parameter("Require Profit to Pyramid", Group = "Pyramiding", DefaultValue = true)]
        public bool RequireProfitToPyramid { get; set; }

        // [Parameter("SL Stagger per Position (pips)", Group = "Pyramiding", DefaultValue = 0, MinValue = -50, MaxValue = 50)]
        // public int SlStaggerPips { get; set; }
        private int SlStaggerPips = 0;

        // [Parameter("TP Multiplier Stagger per Pos", Group = "Pyramiding", DefaultValue = 0.0, MinValue = -5, MaxValue = 5)]
        // public double TpStaggerMultiplier { get; set; }
        private double TpStaggerMultiplier = 0.0;

        // ────── Trailing Stop (off by default) ──────
        public bool UseTrailingStop = false;
        public int TrailingStopPips { get; set; }
        public int TrailingTriggerPips { get; set; }

        public enum HtfOption { H1, H4, D1 }

        // ────── Internal state ──────
        private double _prevVal = double.NaN;
        private Bars _htfBars;

        protected override void OnStart()
        {
            TimeFrame htfTimeFrame = HtfTimeFrame();
            _htfBars = MarketData.GetBars(htfTimeFrame, SymbolName);

            double? tpPips = TpMultiplier > 0 ? (double?)(StopLossPips * TpMultiplier) : null;
            Print("SqueezeMomentumBotFunded started – {0} | HTF: {1} SMA({2}) | SL: {3} pips | TP: {4} | maxPos {5} | requireProfit {6}",
                  Symbol.Name, htfTimeFrame, HtfSmaPeriod, StopLossPips,
                  tpPips.HasValue ? tpPips.Value.ToString("F1") + " pips" : "off",
                  MaxPositions, RequireProfitToPyramid);
        }

        protected override void OnBar()
        {
            if (Bars.Count < KcLength * 2 + 2) return;
            if (_htfBars.Count < HtfSmaPeriod + 2) return;

            // ── 1. Bollinger Bands ──
            double bbBasis = Sma(Bars.ClosePrices, BbLength, 0);
            double bbStdDev = StdDev(Bars.ClosePrices, BbLength, 0);
            double upperBB = bbBasis + BbMult * bbStdDev;
            double lowerBB = bbBasis - BbMult * bbStdDev;

            // ── 2. Keltner Channels ──
            double kcMa = Sma(Bars.ClosePrices, KcLength, 0);
            double kcRange = SmaArr(TrueRanges(Bars), KcLength, 0);
            double upperKC = kcMa + kcRange * KcMult;
            double lowerKC = kcMa - kcRange * KcMult;

            // ── 3. Squeeze state ──
            bool sqzOff = lowerBB < lowerKC && upperBB > upperKC;

            // ── 4. Momentum value ──
            double[] deltaArr = new double[KcLength];
            for (int i = 0; i < KcLength; i++)
            {
                double highestHigh = HighestHigh(KcLength, i, Bars);
                double lowestLow = LowestLow(KcLength, i, Bars);
                double midHL = (highestHigh + lowestLow) / 2.0;
                double smaCl = Sma(Bars.ClosePrices, KcLength, i);
                double midpoint = (midHL + smaCl) / 2.0;
                deltaArr[i] = Bars.ClosePrices[Bars.Count - 1 - i] - midpoint;
            }
            double val = LinReg(deltaArr, KcLength);

            // ── 5. Momentum colour logic ──
            bool valPositive = val > 0;
            bool valRising = !double.IsNaN(_prevVal) && val > _prevVal;
            bool valFalling = !double.IsNaN(_prevVal) && val < _prevVal;

            // ── 6. HTF Trend Filter ──
            bool htfBullish = false, htfBearish = false;
            int htfLast = _htfBars.Count - 2;
            if (htfLast >= HtfSmaPeriod)
            {
                double htfClose = _htfBars.ClosePrices[htfLast];
                double htfSma = HtfSma(htfLast, HtfSmaPeriod);
                htfBullish = htfClose > htfSma;
                htfBearish = htfClose < htfSma;
            }

            int openBuys = CountPositions(TradeType.Buy);
            int openSells = CountPositions(TradeType.Sell);

            // ── 7. Entries (net mode: skip if opposite side has positions) ──
            if (sqzOff)
            {
                // Long
                if (valPositive && valRising && htfBullish && openBuys < MaxPositions && openSells == 0)
                {
                    if (IsDistanceValid(TradeType.Buy) && IsPyramidProfitable(TradeType.Buy))
                    {
                        int baseSlPips = Math.Max(1, StopLossPips + (openBuys * SlStaggerPips));
                        int dynamicSlPips = ResolveStructuralSlPips(TradeType.Buy, baseSlPips);
                        double dynamicTpMult = Math.Max(0, TpMultiplier + (openBuys * TpStaggerMultiplier));
                        double? dynamicTpPips = dynamicTpMult > 0 ? (double?)(dynamicSlPips * dynamicTpMult) : null;

                        double volume = Symbol.QuantityToVolumeInUnits(VolumeInLots);
                        var result = ExecuteMarketOrder(TradeType.Buy, SymbolName, volume, "SQZF_LONG",
                                                       dynamicSlPips, dynamicTpPips);
                        if (result.IsSuccessful && UseTrailingStop)
                            result.Position.ModifyTrailingStop(true);
                    }
                }

                // Short
                if (!valPositive && valFalling && htfBearish && openSells < MaxPositions && openBuys == 0)
                {
                    if (IsDistanceValid(TradeType.Sell) && IsPyramidProfitable(TradeType.Sell))
                    {
                        int baseSlPips = Math.Max(1, StopLossPips + (openSells * SlStaggerPips));
                        int dynamicSlPips = ResolveStructuralSlPips(TradeType.Sell, baseSlPips);
                        double dynamicTpMult = Math.Max(0, TpMultiplier + (openSells * TpStaggerMultiplier));
                        double? dynamicTpPips = dynamicTpMult > 0 ? (double?)(dynamicSlPips * dynamicTpMult) : null;

                        double volume = Symbol.QuantityToVolumeInUnits(VolumeInLots);
                        var result = ExecuteMarketOrder(TradeType.Sell, SymbolName, volume, "SQZF_SHORT",
                                                       dynamicSlPips, dynamicTpPips);
                        if (result.IsSuccessful && UseTrailingStop)
                            result.Position.ModifyTrailingStop(true);
                    }
                }
            }

            // ── 8. Trailing stop management ──
            if (UseTrailingStop) ManageTrailingStops();

            // ── 9. Momentum-reversal exits ──
            if (openBuys > 0 && !double.IsNaN(_prevVal) && val < _prevVal && !valPositive)
                CloseAllPositions(TradeType.Buy);

            if (openSells > 0 && !double.IsNaN(_prevVal) && val > _prevVal && valPositive)
                CloseAllPositions(TradeType.Sell);

            _prevVal = val;
        }

        protected override void OnTick()
        {
            if (UseTrailingStop) ManageTrailingStops();
        }

        // ────── Helpers ──────

        /// <summary>
        /// Returns SL distance in pips: max(baseSlPips, distance from current
        /// price to the lookback low (long) / high (short) + buffer). The
        /// static base acts as a floor — SL never tighter than baseSlPips.
        /// </summary>
        private int ResolveStructuralSlPips(TradeType type, int baseSlPips)
        {
            if (SlLookbackBars <= 0) return baseSlPips;

            int last = Bars.Count - 1;                       // current (forming) bar
            int from = Math.Max(0, last - SlLookbackBars);   // window start
            // window is [from .. last-1] (exclude the current bar)
            if (from >= last) return baseSlPips;

            double currentPrice = (type == TradeType.Buy) ? Symbol.Ask : Symbol.Bid;
            double structuralPrice;
            if (type == TradeType.Buy)
            {
                double minLow = double.MaxValue;
                for (int i = from; i < last; i++)
                    if (Bars.LowPrices[i] < minLow) minLow = Bars.LowPrices[i];
                structuralPrice = minLow;
                double distPips = (currentPrice - structuralPrice) / Symbol.PipSize + SlBufferPips;
                return Math.Max(baseSlPips, (int)Math.Round(distPips));
            }
            else
            {
                double maxHigh = double.MinValue;
                for (int i = from; i < last; i++)
                    if (Bars.HighPrices[i] > maxHigh) maxHigh = Bars.HighPrices[i];
                structuralPrice = maxHigh;
                double distPips = (structuralPrice - currentPrice) / Symbol.PipSize + SlBufferPips;
                return Math.Max(baseSlPips, (int)Math.Round(distPips));
            }
        }

        private bool IsDistanceValid(TradeType type)
        {
            if (MinEntryDistancePips <= 0) return true;
            double currentPrice = (type == TradeType.Buy) ? Symbol.Ask : Symbol.Bid;
            foreach (var pos in Positions)
            {
                if (pos.SymbolName == SymbolName && pos.TradeType == type)
                {
                    double distance = Math.Abs(currentPrice - pos.EntryPrice) / Symbol.PipSize;
                    if (distance < MinEntryDistancePips) return false;
                }
            }
            return true;
        }

        private bool IsPyramidProfitable(TradeType type)
        {
            if (!RequireProfitToPyramid) return true;
            double totalNetProfit = 0;
            int count = 0;
            foreach (var pos in Positions)
            {
                if (pos.SymbolName == SymbolName && pos.TradeType == type)
                {
                    totalNetProfit += pos.NetProfit;
                    count++;
                }
            }
            if (count == 0) return true;
            return totalNetProfit > 0;
        }

        private void ManageTrailingStops()
        {
            double triggerDistance = TrailingTriggerPips * Symbol.PipSize;
            double trailDistance = TrailingStopPips * Symbol.PipSize;

            foreach (var pos in Positions)
            {
                if (pos.SymbolName != SymbolName) continue;
                if (!pos.Label.StartsWith("SQZF_")) continue;

                if (pos.TradeType == TradeType.Buy)
                {
                    double bestPrice = Symbol.Bid;
                    if (bestPrice - pos.EntryPrice < triggerDistance) continue;
                    double idealSl = bestPrice - trailDistance;
                    if (pos.StopLoss == null || idealSl > pos.StopLoss.Value)
                        pos.ModifyStopLossPips(TrailingStopPips);
                }
                else
                {
                    double bestPrice = Symbol.Ask;
                    if (pos.EntryPrice - bestPrice < triggerDistance) continue;
                    double idealSl = bestPrice + trailDistance;
                    if (pos.StopLoss == null || idealSl < pos.StopLoss.Value)
                        pos.ModifyStopLossPips(TrailingStopPips);
                }
            }
        }

        private TimeFrame HtfTimeFrame()
        {
            switch (HigherTimeframe)
            {
                case HtfOption.H1: return TimeFrame.Hour;
                case HtfOption.H4: return TimeFrame.Hour4;
                case HtfOption.D1: return TimeFrame.Daily;
                default: return TimeFrame.Hour4;
            }
        }

        private double HtfSma(int endIndex, int period)
        {
            double sum = 0;
            for (int i = endIndex; i > endIndex - period; i--)
                sum += _htfBars.ClosePrices[i];
            return sum / period;
        }

        private double Sma(DataSeries series, int period, int offset)
        {
            double sum = 0;
            int startIndex = Bars.Count - 1 - offset;
            for (int i = startIndex; i > startIndex - period; i--)
                sum += series[i];
            return sum / period;
        }

        private double StdDev(DataSeries series, int period, int offset)
        {
            double mean = Sma(series, period, offset);
            double sumSq = 0;
            int startIndex = Bars.Count - 1 - offset;
            for (int i = startIndex; i > startIndex - period; i--)
            {
                double diff = series[i] - mean;
                sumSq += diff * diff;
            }
            return Math.Sqrt(sumSq / period);
        }

        private double[] TrueRanges(Bars bars)
        {
            int len = KcLength;
            double[] tr = new double[len];
            int last = bars.Count - 1;
            for (int i = 0; i < len; i++)
            {
                int idx = last - i;
                double high = bars.HighPrices[idx];
                double low = bars.LowPrices[idx];
                double prevClose = idx > 0 ? bars.ClosePrices[idx - 1] : bars.ClosePrices[idx];
                tr[i] = Math.Max(high - low,
                         Math.Max(Math.Abs(high - prevClose),
                                  Math.Abs(low - prevClose)));
            }
            return tr;
        }

        private double SmaArr(double[] arr, int period, int offset)
        {
            double sum = 0;
            for (int i = offset; i < offset + period; i++)
                sum += arr[i];
            return sum / period;
        }

        private double HighestHigh(int period, int offset, Bars bars)
        {
            double max = double.MinValue;
            int startIndex = bars.Count - 1 - offset;
            for (int i = startIndex; i > startIndex - period; i--)
                if (bars.HighPrices[i] > max) max = bars.HighPrices[i];
            return max;
        }

        private double LowestLow(int period, int offset, Bars bars)
        {
            double min = double.MaxValue;
            int startIndex = bars.Count - 1 - offset;
            for (int i = startIndex; i > startIndex - period; i--)
                if (bars.LowPrices[i] < min) min = bars.LowPrices[i];
            return min;
        }

        private double LinReg(double[] data, int period)
        {
            double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;
            for (int i = 0; i < period; i++)
            {
                double x = i;
                double y = data[period - 1 - i];
                sumX += x;
                sumY += y;
                sumXY += x * y;
                sumX2 += x * x;
            }
            double n = period;
            double slope = (n * sumXY - sumX * sumY) / (n * sumX2 - sumX * sumX);
            double intercept = (sumY - slope * sumX) / n;
            return intercept + slope * (period - 1);
        }

        private int CountPositions(TradeType type)
        {
            int count = 0;
            foreach (var pos in Positions)
                if (pos.SymbolName == SymbolName && pos.TradeType == type)
                    count++;
            return count;
        }

        private void CloseAllPositions(TradeType type)
        {
            foreach (var pos in Positions)
                if (pos.SymbolName == SymbolName && pos.TradeType == type)
                    ClosePosition(pos);
        }

        protected override void OnStop()
        {
            Print("SqueezeMomentumBotFunded stopped.");
        }
    }
}
