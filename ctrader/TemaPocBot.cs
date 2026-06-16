// TemaPocBot — cTrader Algo C# cBot.
//
// Port of the Pine v5 "TEMA & Session Volume POC" idea, intended for 30M
// intraday on XAUUSD (or any liquid CFD).
//
// Long  : close crosses up through TEMA, close > sessionPOC, TEMA rising.
// Short : close crosses down through TEMA, close < sessionPOC, TEMA falling.
// Stop  : N × ATR(14) below/above entry. Target = ratio × stop distance.
//
// Session POC = rolling volume-weighted anchor within the current calendar
// day. Resets at the first bar of a new day. Anchor jumps to hl2 of the
// current bar whenever bar volume exceeds the 20-bar SMA of volume.
//
// Indexing: cTrader index 0 = OLDEST, Last(0) is the still-forming bar.
// All signal reads use Last(1) (most recent closed bar).

using System;
using cAlgo.API;
using cAlgo.API.Indicators;
using cAlgo.API.Internals;

namespace cAlgo.Robots
{
    [Robot(AccessRights = AccessRights.None)]
    public class TemaPocBot : Robot
    {
        // ────── Sizing ──────
        [Parameter("Volume (lots)", DefaultValue = 0.05, MinValue = 0.01, Step = 0.01, Group = "Sizing")]
        public double VolumeLots { get; set; }

        [Parameter("Label", DefaultValue = "TEMA_POC", Group = "Sizing")]
        public string Label { get; set; }

        // ponytail: pinned long-only off → both sides. Flip to true to force long-only.
        private const bool LongOnly = false;

        // ────── Entry ──────
        [Parameter("TEMA length", DefaultValue = 14, MinValue = 3, Step = 1, Group = "Entry")]
        public int TemaLength { get; set; }

        [Parameter("Volume SMA length", DefaultValue = 20, MinValue = 5, Step = 1, Group = "Entry")]
        public int VolSmaLength { get; set; }

        // ────── Risk ──────
        [Parameter("SL × ATR(14)", DefaultValue = 2.0, MinValue = 0.5, Step = 0.5, Group = "Risk")]
        public double SlAtrMult { get; set; }

        [Parameter("TP : SL ratio", DefaultValue = 2.0, MinValue = 0.5, Step = 0.5, Group = "Risk")]
        public double TpSlRatio { get; set; }

        // ────── Indicators ──────
        private ExponentialMovingAverage _ema1;
        private ExponentialMovingAverage _ema2;
        private ExponentialMovingAverage _ema3;
        private SimpleMovingAverage _volSma;
        private AverageTrueRange _atr;

        // ────── Session POC state ──────
        private double _sessionPoc = double.NaN;
        private int _sessionDay = -1;

        protected override void OnStart()
        {
            _ema1 = Indicators.ExponentialMovingAverage(Bars.ClosePrices, TemaLength);
            _ema2 = Indicators.ExponentialMovingAverage(_ema1.Result, TemaLength);
            _ema3 = Indicators.ExponentialMovingAverage(_ema2.Result, TemaLength);
            _volSma = Indicators.SimpleMovingAverage(Bars.TickVolumes, VolSmaLength);
            _atr = Indicators.AverageTrueRange(14, MovingAverageType.Simple);

            Print("TemaPocBot ready. vol={0} lot, TEMA={1}, volSMA={2}, SL={3}×ATR, TP:SL={4}, longOnly={5}",
                  VolumeLots, TemaLength, VolSmaLength, SlAtrMult, TpSlRatio, LongOnly);
        }

        protected override void OnBar()
        {
            if (Bars.Count < Math.Max(TemaLength * 3, VolSmaLength) + 5) return;

            double close = Bars.ClosePrices.Last(1);
            double closePrev = Bars.ClosePrices.Last(2);
            double high = Bars.HighPrices.Last(1);
            double low = Bars.LowPrices.Last(1);
            double hl2 = (high + low) / 2.0;
            double vol = Bars.TickVolumes.Last(1);
            double volAvg = _volSma.Result.Last(1);
            double atr = _atr.Result.Last(1);

            double tema = Tema(1);
            double temaPrev = Tema(2);

            // --- Update session POC on the most recent closed bar ---
            int day = Bars.OpenTimes.Last(1).Day;
            if (day != _sessionDay)
            {
                _sessionDay = day;
                _sessionPoc = close;
            }
            else if (vol > volAvg)
            {
                _sessionPoc = hl2;
            }
            double poc = _sessionPoc;

            // --- Signals ---
            bool crossUp = closePrev <= temaPrev && close > tema;
            bool crossDn = closePrev >= temaPrev && close < tema;
            bool longEntry = crossUp && close > poc && tema > temaPrev;
            bool shortEntry = !LongOnly && crossDn && close < poc && tema < temaPrev;

            // --- Position management ---
            var open = FindOpenPosition();
            if (open != null)
            {
                bool isLong = open.TradeType == TradeType.Buy;
                bool flip = (isLong && shortEntry) || (!isLong && longEntry);
                if (flip)
                {
                    ClosePosition(open);
                    return; // wait one bar before re-entering
                }
                return; // hold; stops/targets are platform-managed
            }

            if (longEntry) OpenPosition(TradeType.Buy, atr);
            else if (shortEntry) OpenPosition(TradeType.Sell, atr);
        }

        // TEMA = 3·EMA1 − 3·EMA2 + EMA3, evaluated at Last(offset).
        private double Tema(int offset)
        {
            return 3.0 * _ema1.Result.Last(offset)
                 - 3.0 * _ema2.Result.Last(offset)
                 +       _ema3.Result.Last(offset);
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

        protected override void OnStop()
        {
            Print("TemaPocBot stopped.");
        }
    }
}
