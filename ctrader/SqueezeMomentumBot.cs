// SqueezeMomentumBot — cTrader Algo C# cBot
//
// Port of LazyBear's Squeeze Momentum indicator into a trading bot.
// Designed for ETHUSD H1.
//
// Defaults reflect the Kotlin backtest findings on ETHUSD H1 OS (3yr):
//
//   Squeeze params      : BB 11/2.3, KC 22/1.4 (kcMult 1.4 makes the
//                         sqzOff gate meaningfully filter compressed phases)
//   HTF filter          : H1 SMA(47) (same TF, acts as a fast trend bias)
//   SL                  : 47 pips (floor); structural SL extends to recent
//                         swing-low/high + buffer when farther — see below
//   SL lookback         : 80 bars (~3.3 days on H1) — empirical winner
//   TP multiplier       : 1.0  (TP=0.5 was unprofitable in OS testing)
//   Max positions       : 4    (6 also works; 4 is safer)
//   Stagger             : all OFF except RequireProfitToPyramid
//                         (only gate that improved risk-adjusted return)
//   Volume              : 0.5 lots (= 5 ETH on a 1lot=10ETH broker;
//                         adjust per broker — FTMO uses 0.05, the5ers 5.0)
//
// Empirical winner (Kotlin backtest, 8 ETH per entry):
//   V1 baseline + RequireProfitToPyramid on + structural SL lb=80:
//   3yr OS profit $95k / peak DD $43k / PF 1.21 (vs $44k/$21k for static SL)
//
// Structural SL: at entry, look back N bars (default 80). For longs, find
// the lowest low in that window; SL is placed at min(lowestLow - buffer,
// entry - StopLossPips). For shorts, mirror with highest high. The static
// StopLossPips acts as a floor — SL is never tighter than 47 pips.
// Intuition: the static 47 pips was getting tagged by ETH H1 noise right
// before reversals turned favorable. Widening to structural gives the
// trade room to breathe through pullbacks without losing the safety net.
//
// Lot-size scaling notes (see ctrader/SqueezeMomentumBot.md for full table):
//   * Personal $100k+ account                  → use 0.08 lots (FTMO) or 8 lots (the5ers) — ~$48k profit / 29% DD on $100k
//   * FTMO 100k eval (10% DD ceiling)          → use ~0.03 lots (= 3 ETH) to fit under ceiling
//   * Smaller accounts (< $30k)                → don't use this strategy as-is; too risky

using System;
using cAlgo.API;
using cAlgo.API.Indicators;
using cAlgo.API.Internals;

namespace cAlgo.Robots
{
    [Robot(TimeZone = TimeZones.UTC, AccessRights = AccessRights.None)]
    public class SqueezeMomentumBot : Robot
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
        [Parameter("Trade Volume (Lots)", Group = "Trade", DefaultValue = 0.6, MinValue = 0.001, Step = 0.001)]
        public double VolumeInLots { get; set; }

        [Parameter("Stop Loss (pips)", Group = "Trade", DefaultValue = 47, MinValue = 1)]
        public int StopLossPips { get; set; }

        // ─────────────────────────────────────────────────────────────────
        // Structural SL — DISABLED by default for the5ers/FTMO evaluation.
        // ─────────────────────────────────────────────────────────────────
        // The structural SL widens the stop to the N-bar prior swing low/high
        // when farther than the static 47-pip floor. It catches more reversals
        // (profits double on Kotlin 3yr OS: $27k → $59k at 5 ETH per entry)
        // but pays for it with deeper retracements: daily floating losses
        // reach $5,800 at 5 ETH, which busts the5ers / FTMO $5k max-daily-loss
        // rule. Static is the only viable choice for evaluations.
        //
        // FUNDED-ACCOUNT VARIANT: once you've passed the evaluation and the
        // daily-loss rule is relaxed (or you're trading personal capital with
        // no DD ceiling), re-enable structural SL with these empirical winners:
        //
        //   SlLookbackBars = 80   // 80 bars ≈ 3.3 days on H1
        //   SlBufferPips   = 5    // 5 pips past the swing for spread tolerance
        //   VolumeInLots   = 0.5  // 5 ETH per entry on 1lot=10ETH broker
        //
        // Backtest 3yr OS at funded-variant config: +$59,400 profit / $26,862
        // peak DD / PF 1.21 (vs +$27,572 / $13,410 / PF 1.15 for static).
        //
        // To enable: uncomment the [Parameter] lines and DELETE the matching
        // private const lines below them. Test daily loss against your funded
        // account's specific rule before going live.
        // [Parameter("SL Lookback Bars (0 = static SL)", Group = "Trade", DefaultValue = 0, MinValue = 0, MaxValue = 500, Step = 10)]
        // public int SlLookbackBars { get; set; }
        private int SlLookbackBars = 0;
        // [Parameter("SL Buffer (pips)", Group = "Trade", DefaultValue = 5, MinValue = 0, MaxValue = 50, Step = 1)]
        // public int SlBufferPips { get; set; }
        private int SlBufferPips = 5;

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
            Print("SqueezeMomentumBot started – {0} | HTF: {1} SMA({2}) | SL: {3} pips | TP: {4} | maxPos {5} | requireProfit {6}",
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
                        var result = ExecuteMarketOrder(TradeType.Buy, SymbolName, volume, "SQZ_LONG",
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
                        var result = ExecuteMarketOrder(TradeType.Sell, SymbolName, volume, "SQZ_SHORT",
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
                if (!pos.Label.StartsWith("SQZ_")) continue;

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
            Print("SqueezeMomentumBot stopped.");
        }
    }
}
