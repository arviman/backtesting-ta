// JamaEarlyBot — cTrader Algo C# cBot.
//
// Port of the JAMA early-entry strategy from
// src/main/kotlin/com/pschlup/ta/strategy/JamaStrategy.kt — stripped to the
// jarvis (smoothed-RSI delta) entry only. MA-cross was removed after H8
// XAUUSD optimization: MaCrossOnly and Both consistently underperformed
// JarvisOnly, so the extra parameters were noise.
//
// Defaults reflect the H8 XAUUSD optimization winner:
//   slAtrMult        = 4.0                  // SL = entry − 4 × ATR(14)
//   tpSlRatio        = 5.0                  // TP = 5 × SL distance
//   mmCap            = 1.1                  // long when MM < 1.1, short when MM > 1/1.1
//   changeEmaThreshold = 0.08
//   closeDelta       = 0.05                 // soft exit at threshold − delta
//   disableSoftExit  = false                // soft exit (smoothed-RSI rollover) ON
//   pyramiding       = 1                    // single open position at a time
//   volume           = 0.05 lot
//
// Signal logic:
//   Long  jarvis  : changeEma > +Threshold
//   Long  exit    : changeEma < +(Threshold − Δ)
//   Long  trend   : close / SMA200 < MmCap         (not overvalued)
//
//   Short jarvis  : changeEma < −Threshold
//   Short exit    : changeEma > −(Threshold − Δ)
//   Short trend   : close / SMA200 > 1 / MmCap     (not undervalued)
//
// `LongOnly = false` enables the mirrored short side. Defaults to true.
//
// Recommended environment:
//   Symbol     : XAUUSD
//   Timeframe  : H8 / Daily / H12
//   Account    : $100K prop or personal, ~$5K/$10K DD ceilings
//
// Indexing note: cTrader's DataSeries uses index 0 = OLDEST, Count−1 = LATEST.
// `Last(0)` is the latest (still-forming) bar; `Last(1)` is the most recent
// closed bar. Throughout this file we evaluate signals on `Last(1)`.

using System;
using cAlgo.API;
using cAlgo.API.Indicators;
using cAlgo.API.Internals;

namespace cAlgo.Robots
{
    [Robot(AccessRights = AccessRights.None)]
    public class JamaEarlyBot : Robot
    {
        // ────── Sizing ──────
        [Parameter("Volume (lots)", DefaultValue = 0.05, MinValue = 0.01, Step = 0.01, Group = "Sizing")]
        public double VolumeLots { get; set; }

        [Parameter("Label", DefaultValue = "JAMA_Early", Group = "Sizing")]
        public string Label { get; set; }

        [Parameter("Long only", DefaultValue = true, Group = "Sizing")]
        public bool LongOnly { get; set; }

        // ────── Risk / exits ──────
        [Parameter("SL × ATR(14)", DefaultValue = 4.0, MinValue = 1.0, Step = 0.5, Group = "Risk")]
        public double SlAtrMult { get; set; }

        [Parameter("TP : SL ratio", DefaultValue = 5.0, MinValue = 0.5, Step = 0.5, Group = "Risk")]
        public double TpSlRatio { get; set; }

        // ────── Trend gate ──────
        [Parameter("Mayer Multiple cap", DefaultValue = 1.1, MinValue = 0.5, Step = 0.1, Group = "Trend")]
        public double MmCap { get; set; }

        // ────── Entry / exit ──────
        [Parameter("changeEma threshold", DefaultValue = 0.08, MinValue = 0.0, Step = 0.01, Group = "Entry")]
        public double ChangeEmaThreshold { get; set; }

        [Parameter("changeEma close-delta", DefaultValue = 0.05, MinValue = 0.0, Step = 0.01, Group = "Exit")]
        public double CloseDelta { get; set; }

        // ────── Indicators ──────
        private RelativeStrengthIndex _rsi;          // RSI(close, 18)
        private ExponentialMovingAverage _rsiSm1;    // EMA(rsi, 3)
        private ExponentialMovingAverage _rsiSm2;    // EMA(rsiSm1, 12)
        private SimpleMovingAverage _sma200;
        private AverageTrueRange _atr;

        protected override void OnStart()
        {
            Print("JamaEarlyBot starting on {0} {1}", SymbolName, TimeFrame);

            _rsi = Indicators.RelativeStrengthIndex(Bars.ClosePrices, 18);
            _rsiSm1 = Indicators.ExponentialMovingAverage(_rsi.Result, 3);
            _rsiSm2 = Indicators.ExponentialMovingAverage(_rsiSm1.Result, 12);
            _sma200 = Indicators.SimpleMovingAverage(Bars.ClosePrices, 200);
            _atr = Indicators.AverageTrueRange(14, MovingAverageType.Simple);

            Print("JamaEarlyBot ready. vol={0} lot, SL={1}×ATR, TP:SL={2}, MmCap={3}, thresh={4}, Δ={5}, longOnly={6}",
                  VolumeLots, SlAtrMult, TpSlRatio, MmCap, ChangeEmaThreshold, CloseDelta, LongOnly);
        }

        protected override void OnBar()
        {
            // Need enough warmup for the slowest indicator (SMA200).
            if (Bars.Count < 200) return;

            // --- Read values at the last closed bar (Last(1)) ---
            double close = Bars.ClosePrices.Last(1);
            double rsiSm2 = _rsiSm2.Result.Last(1);
            double rsiSm2Prev = _rsiSm2.Result.Last(2);
            double changeEma = rsiSm2 - rsiSm2Prev;
            double sma200 = _sma200.Result.Last(1);
            double atr = _atr.Result.Last(1);
            double mm = close / sma200;

            // --- Long-side signals ---
            bool longJarvis = changeEma > ChangeEmaThreshold;
            bool longExit = changeEma < (ChangeEmaThreshold - CloseDelta);
            bool longTrendOk = mm < MmCap;                       // not overvalued

            // --- Short-side signals (mirrored; only when !LongOnly) ---
            bool shortJarvis = false, shortExit = false, shortTrendOk = false;
            if (!LongOnly)
            {
                shortJarvis = changeEma < -ChangeEmaThreshold;
                shortExit = changeEma > -(ChangeEmaThreshold - CloseDelta);
                shortTrendOk = mm > (1.0 / MmCap);                // not undervalued
            }

            // --- Position management ---
            var open = FindOpenPosition();
            if (open != null)
            {
                bool isLong = open.TradeType == TradeType.Buy;
                bool shouldClose =
                    (isLong && (longExit || (shortJarvis && shortTrendOk))) ||
                    (!isLong && (shortExit || (longJarvis && longTrendOk)));
                if (shouldClose)
                {
                    ClosePosition(open);
                    return; // wait one bar before re-entering
                }
            }

            // --- Entry (no open position) ---
            if (open == null)
            {
                if (longJarvis && longTrendOk)
                    OpenPosition(TradeType.Buy, atr);
                else if (!LongOnly && shortJarvis && shortTrendOk)
                    OpenPosition(TradeType.Sell, atr);
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
            {
                if (p.Label == Label && p.SymbolName == SymbolName) return p;
            }
            return null;
        }

        protected override void OnStop()
        {
            Print("JamaEarlyBot stopped.");
        }
    }
}
