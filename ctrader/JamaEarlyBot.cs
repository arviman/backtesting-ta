// JamaEarlyBot — cTrader Algo C# cBot.
//
// Port of the JAMA early-entry strategy from this repository
// (src/main/kotlin/com/pschlup/ta/strategy/JamaStrategy.kt) at the config
// that came out on top of the Gold-daily SL sweep:
//
//   slAtrMult        = 5.0                  // SL = entry − 5 × ATR(14)
//   tpSlRatio        = 3.0                  // TP = entry + 3 × (SL distance)
//   earlyEntry       = true                 // drops Hurst breakout + slope gates
//   dropTrendGates   = false                // keeps MM cap + SMA50-rising gates
//   changeEmaThreshold = 0.06
//   closeDelta       = 0.02                 // soft exit at threshold − delta
//   disableSoftExit  = false                // soft exit (smoothed-RSI rollover) ON
//   pyramiding       = 1                    // single open position at a time
//   volume           = 0.1 lot
//
// Recommended environment:
//   Symbol     : XAUUSD
//   Timeframe  : Daily
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

        // ────── Risk / exits ──────
        [Parameter("SL × ATR(14)", DefaultValue = 5.0, MinValue = 1.0, Step = 0.5, Group = "Risk")]
        public double SlAtrMult { get; set; }

        [Parameter("TP : SL ratio", DefaultValue = 3.0, MinValue = 0.5, Step = 0.5, Group = "Risk")]
        public double TpSlRatio { get; set; }

        // ────── Trend gates (earlyEntry mode) ──────
        [Parameter("Mayer Multiple cap", DefaultValue = 2.4, MinValue = 1.0, Step = 0.1, Group = "Trend")]
        public double MmCap { get; set; }

        // ────── Entry (jarvis + MA-cross) ──────
        [Parameter("changeEma threshold", DefaultValue = 0.06, MinValue = 0.0, Step = 0.01, Group = "Entry")]
        public double ChangeEmaThreshold { get; set; }

        [Parameter("Use jarvis entry", DefaultValue = true, Group = "Entry")]
        public bool UseJarvis { get; set; }

        [Parameter("Use MA-cross entry", DefaultValue = true, Group = "Entry")]
        public bool UseMaCross { get; set; }

        [Parameter("MA-cross valid for N bars after jarvis", DefaultValue = 20, MinValue = 1, MaxValue = 200, Group = "Entry")]
        public int JarvisMemoryBars { get; set; }

        [Parameter("Use SMA50-rising gate", DefaultValue = true, Group = "Entry")]
        public bool UseSma50RisingGate { get; set; }

        [Parameter("changeEma close-delta", DefaultValue = 0.02, MinValue = 0.0, Step = 0.01, Group = "Exit")]
        public double CloseDelta { get; set; }

        // ────── Indicators ──────
        private RelativeStrengthIndex _rsi;          // RSI(close, 18)
        private ExponentialMovingAverage _rsiSm1;    // EMA(rsi, 3)
        private ExponentialMovingAverage _rsiSm2;    // EMA(rsiSm1, 12)
        private SimpleMovingAverage _sma21;
        private SimpleMovingAverage _sma50;
        private SimpleMovingAverage _sma200;
        private AverageTrueRange _atr;

        // Tracks how many bars since jarvis last fired. MA-cross is treated as a
        // continuation signal — only valid while we're still inside that memory
        // window, so price drifting above short MAs in flat phases doesn't open.
        private int _barsSinceJarvis = int.MaxValue;

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
            _sma50 = Indicators.SimpleMovingAverage(Bars.ClosePrices, 50);
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
            double sma50 = _sma50.Result.Last(1);
            double sma50Prev = _sma50.Result.Last(2);
            double sma200 = _sma200.Result.Last(1);
            double atr = _atr.Result.Last(1);

            // ZEMA(close, 5) computed inline (cTrader has no built-in ZEMA).
            double zema5 = ComputeZema(closedOffset: 1, length: 5);

            // --- Entry signals ---
            bool jarvisLong = UseJarvis && changeEma > ChangeEmaThreshold;
            bool maLongRaw = UseMaCross && close > zema5 && zema5 > sma21;

            // Track jarvis recency. MA-cross only counts as continuation while
            // jarvis fired within the memory window — otherwise it's just price
            // drifting above short MAs in a flat phase and should not enter.
            if (jarvisLong) _barsSinceJarvis = 0;
            else if (_barsSinceJarvis < int.MaxValue) _barsSinceJarvis++;

            bool maContinuation = maLongRaw && _barsSinceJarvis <= JarvisMemoryBars;
            bool longEntry = jarvisLong || maContinuation;

            // --- Trend gate (earlyEntry mode) ---
            bool mmFilter = close / sma200 < MmCap;
            bool sma50Rising = !UseSma50RisingGate || sma50 > sma50Prev;
            bool trendOk = mmFilter && sma50Rising;

            // --- Exit signal (soft exit: smoothed-RSI rollover) ---
            bool longExit = changeEma < (ChangeEmaThreshold - CloseDelta);

            // --- Manage existing position on this label/symbol ---
            var open = FindOpenPosition();
            if (open != null && longExit)
            {
                ClosePosition(open);
                return; // wait one bar before re-entering
            }

            // --- New entry: only when fully gated AND no open position ---
            if (open == null && longEntry && trendOk)
            {
                OpenLong(atr);
            }
        }

        private void OpenLong(double atr)
        {
            double slDistance = SlAtrMult * atr;
            double tpDistance = slDistance * TpSlRatio;

            // cTrader takes SL/TP as pips (positive distance from entry).
            double slPips = slDistance / Symbol.PipSize;
            double tpPips = tpDistance / Symbol.PipSize;

            double volume = Symbol.QuantityToVolumeInUnits(VolumeLots);
            volume = Symbol.NormalizeVolumeInUnits(volume, RoundingMode.ToNearest);

            var result = ExecuteMarketOrder(TradeType.Buy, SymbolName, volume, Label, slPips, tpPips);
            if (!result.IsSuccessful)
                Print("Entry failed: {0}", result.Error);
        }

        private Position FindOpenPosition()
        {
            foreach (var p in Positions)
            {
                if (p.Label == Label && p.SymbolName == SymbolName && p.TradeType == TradeType.Buy)
                    return p;
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

        protected override void OnStop()
        {
            Print("JamaEarlyBot stopped.");
        }
    }
}
