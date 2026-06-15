// JamaEarlyBot — cTrader Algo C# cBot.
//
// Port of the JAMA early-entry strategy from
// src/main/kotlin/com/pschlup/ta/strategy/JamaStrategy.kt. Defaults reflect
// H8 XAUUSD optimization winner (changeEmaThreshold=0.08, closeDelta=0.04,
// TP:SL=5.5, JarvisOnly entry mode).
//
//   slAtrMult        = 5.0                  // SL = entry − 5 × ATR(14)
//   tpSlRatio        = 5.5                  // TP = 5.5 × SL distance
//   earlyEntry       = true                 // (built in: MM cap is the only trend gate)
//   changeEmaThreshold = 0.08
//   closeDelta       = 0.04                 // soft exit at threshold − delta
//   disableSoftExit  = false                // soft exit (smoothed-RSI rollover) ON
//   pyramiding       = 1                    // single open position at a time
//   volume           = 0.05 lot
//
// Optimizer-friendly parameters: entry mode is a 3-way enum
// (JarvisOnly / MaCrossOnly / Both) so "neither enabled" is unrepresentable;
// MA-cross memory window is a 4-value enum (Small/Medium/Large/Forever ->
// 5/10/20/50 bars). SMA50-rising gate was dropped after H8 sweep showed top
// performers don't use it.
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
    public enum EntryMode
    {
        JarvisOnly,     // smoothed-RSI delta only
        MaCrossOnly,    // close > zema5 > sma21 only
        Both,           // jarvis OR (MA-cross gated by jarvis memory)
    }

    public enum MemoryWindow
    {
        Small,          // 5 bars
        Medium,         // 10 bars
        Large,          // 20 bars
        Forever,        // 50 bars (effectively legacy OR)
    }

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
        [Parameter("SL × ATR(14)", DefaultValue = 5.0, MinValue = 1.0, Step = 0.5, Group = "Risk")]
        public double SlAtrMult { get; set; }

        [Parameter("TP : SL ratio", DefaultValue = 5.5, MinValue = 0.5, Step = 0.5, Group = "Risk")]
        public double TpSlRatio { get; set; }

        // ────── Trend gate ──────
        [Parameter("Mayer Multiple cap", DefaultValue = 2.4, MinValue = 1.0, Step = 0.1, Group = "Trend")]
        public double MmCap { get; set; }

        // ────── Entry ──────
        [Parameter("Entry mode", DefaultValue = EntryMode.JarvisOnly, Group = "Entry")]
        public EntryMode Mode { get; set; }

        [Parameter("changeEma threshold", DefaultValue = 0.08, MinValue = 0.0, Step = 0.01, Group = "Entry")]
        public double ChangeEmaThreshold { get; set; }

        [Parameter("MA-cross memory window", DefaultValue = MemoryWindow.Medium, Group = "Entry")]
        public MemoryWindow JarvisMemory { get; set; }

        // ────── Exit ──────
        [Parameter("changeEma close-delta", DefaultValue = 0.04, MinValue = 0.0, Step = 0.01, Group = "Exit")]
        public double CloseDelta { get; set; }

        // ────── Indicators ──────
        private RelativeStrengthIndex _rsi;          // RSI(close, 18)
        private ExponentialMovingAverage _rsiSm1;    // EMA(rsi, 3)
        private ExponentialMovingAverage _rsiSm2;    // EMA(rsiSm1, 12)
        private SimpleMovingAverage _sma21;
        private SimpleMovingAverage _sma200;
        private AverageTrueRange _atr;

        // Per-direction jarvis recency counters. MA-cross only counts as
        // continuation if the same-direction jarvis fired within the memory
        // window — a long-side jarvis does not validate a short MA-cross.
        private int _barsSinceJarvisLong = int.MaxValue;
        private int _barsSinceJarvisShort = int.MaxValue;

        protected override void OnStart()
        {
            // JAMA was originally tuned for 12-hour crypto bars; the gold backtest
            // in this repo locked the Daily config. Both are valid — anything
            // finer than H4 just shrinks ATR and will need parameter retuning.
            Print("JamaEarlyBot starting on {0}", TimeFrame);

            _rsi = Indicators.RelativeStrengthIndex(Bars.ClosePrices, 18);
            _rsiSm1 = Indicators.ExponentialMovingAverage(_rsi.Result, 3);
            _rsiSm2 = Indicators.ExponentialMovingAverage(_rsiSm1.Result, 12);

            _sma21 = Indicators.SimpleMovingAverage(Bars.ClosePrices, 21);
            _sma200 = Indicators.SimpleMovingAverage(Bars.ClosePrices, 200);
            _atr = Indicators.AverageTrueRange(14, MovingAverageType.Simple);

            Print("JamaEarlyBot started on {0} {1}. volume={2} lot, SL={3}×ATR, TP:SL={4}",
                  SymbolName, TimeFrame, VolumeLots, SlAtrMult, TpSlRatio);
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

            double sma21 = _sma21.Result.Last(1);
            double sma200 = _sma200.Result.Last(1);
            double atr = _atr.Result.Last(1);

            // ZEMA(close, 5) computed inline (cTrader has no built-in ZEMA).
            double zema5 = ComputeZema(closedOffset: 1, length: 5);

            // --- Long-side signals ---
            bool jarvisLong = changeEma > ChangeEmaThreshold;
            bool maLongRaw = close > zema5 && zema5 > sma21;
            if (jarvisLong) _barsSinceJarvisLong = 0;
            else if (_barsSinceJarvisLong < int.MaxValue) _barsSinceJarvisLong++;
            bool longEntry = ComputeEntry(jarvisLong, maLongRaw, _barsSinceJarvisLong);
            bool longTrendOk = (close / sma200) < MmCap;            // not overvalued
            bool longExit = changeEma < (ChangeEmaThreshold - CloseDelta);

            // --- Short-side signals (mirrored; computed only if !LongOnly) ---
            bool jarvisShort = false, maShortRaw = false;
            bool shortEntry = false, shortTrendOk = false, shortExit = false;
            if (!LongOnly)
            {
                jarvisShort = changeEma < -ChangeEmaThreshold;
                maShortRaw = close < zema5 && zema5 < sma21;
                if (jarvisShort) _barsSinceJarvisShort = 0;
                else if (_barsSinceJarvisShort < int.MaxValue) _barsSinceJarvisShort++;
                shortEntry = ComputeEntry(jarvisShort, maShortRaw, _barsSinceJarvisShort);
                shortTrendOk = (close / sma200) > (1.0 / MmCap);    // not undervalued
                shortExit = changeEma > -(ChangeEmaThreshold - CloseDelta);
            }

            // --- Position management ---
            var open = FindOpenPosition();
            if (open != null)
            {
                bool isLong = open.TradeType == TradeType.Buy;
                bool shouldClose =
                    (isLong && (longExit || (shortEntry && shortTrendOk))) ||
                    (!isLong && (shortExit || (longEntry && longTrendOk)));
                if (shouldClose)
                {
                    ClosePosition(open);
                    return; // wait one bar before re-entering
                }
            }

            // --- Entry (no open position) ---
            if (open == null)
            {
                if (longEntry && longTrendOk)
                    OpenPosition(TradeType.Buy, atr);
                else if (!LongOnly && shortEntry && shortTrendOk)
                    OpenPosition(TradeType.Sell, atr);
            }
        }

        private bool ComputeEntry(bool jarvis, bool maRaw, int barsSinceJarvis)
        {
            switch (Mode)
            {
                case EntryMode.JarvisOnly: return jarvis;
                case EntryMode.MaCrossOnly: return maRaw;
                case EntryMode.Both:
                default:
                    bool maContinuation = maRaw && barsSinceJarvis <= MemoryBars();
                    return jarvis || maContinuation;
            }
        }

        private void OpenPosition(TradeType type, double atr)
        {
            double slDistance = SlAtrMult * atr;
            double tpDistance = slDistance * TpSlRatio;

            // cTrader takes SL/TP as pip distances (positive on both sides;
            // the platform places them on the correct side of entry).
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

        // ZEMA(close, length): EMA of (2·close − close[lag]) where lag = (length−1)/2.
        // Recomputes fresh over a ~50-bar window each call — cheap on daily.
        private double ComputeZema(int closedOffset, int length)
        {
            int lag = (length - 1) / 2;
            double alpha = 2.0 / (length + 1);

            // Absolute index of the closed bar we want the value at.
            int targetIdx = Bars.Count - 1 - closedOffset;
            if (targetIdx < length + lag)
                return Bars.ClosePrices[Math.Max(0, targetIdx)];

            // Walk forward from ~50 bars back so the EMA has settled.
            int startIdx = Math.Max(lag, targetIdx - 50);

            // Seed with the SMA of the first `length` adjusted values.
            double sum = 0;
            for (int i = startIdx; i < startIdx + length; i++)
                sum += Adjusted(i, lag);
            double ema = sum / length;

            for (int i = startIdx + length; i <= targetIdx; i++)
                ema = alpha * Adjusted(i, lag) + (1 - alpha) * ema;

            return ema;
        }

        private double Adjusted(int i, int lag)
        {
            double c = Bars.ClosePrices[i];
            double cLag = i - lag >= 0 ? Bars.ClosePrices[i - lag] : c;
            return 2 * c - cLag;
        }

        // Discrete memory window — friendlier to cTrader's optimizer than a
        // free integer. Forever (50) is effectively legacy OR for MA-cross.
        private int MemoryBars()
        {
            switch (JarvisMemory)
            {
                case MemoryWindow.Small: return 5;
                case MemoryWindow.Medium: return 10;
                case MemoryWindow.Large: return 20;
                case MemoryWindow.Forever: return 50;
                default: return 10;
            }
        }

        protected override void OnStop()
        {
            Print("JamaEarlyBot stopped.");
        }
    }
}
