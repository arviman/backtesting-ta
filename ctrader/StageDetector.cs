// StageDetector — cTrader Algo C# Indicator.
//
// Ted Zhang (ChartFanatics) four-stage market classifier on the 10/20/30/40
// SMA ribbon. Designed to be applied on a Weekly chart.
//
//   Stage 2 (markup)   : price > SMA10 > SMA20 > SMA30 > SMA40        → green shade
//   Stage 4 (markdown) : price < SMA10 < SMA20 < SMA30 < SMA40        → red shade
//   Stage 3 (top)      : neither stack AND last directional stage = 2 → yellow shade
//   Stage 1 (base)     : neither stack AND last directional stage ≠ 2 → white shade
//
// Outputs:
//   SMA10/SMA20/SMA30/SMA40 lines (the ribbon).
//   Stage : numeric 1..4 (NaN before warm-up). NOT plotted on the chart —
//           the [Output] attribute is omitted because a 1..4 line crushes
//           the price axis on low-priced symbols (e.g. CADCHF ≈ 0.6).
//           Still accessible from other indicators / cBots via
//           Indicators.GetIndicator<StageDetector>(...).Stage.
//
// Shade modes (Display → Shade mode):
//   Rectangle  : one ChartRectangle per stage cycle. Finalised runs persist
//                under "stage_<startIndex>"; the in-progress run is redrawn
//                under the same name as it grows. Autoscale-safe.
//   BarColor   : tint each bar via Chart.SetBarColor.
//   None       : no shading.
//
// Indexing: cTrader Indicators run forward. Calculate(int index) is invoked
// per bar oldest→newest, then re-invoked on the in-flight (last) bar each
// tick. The _lastDirectional latch only advances when a bar is genuinely in
// Stage 2 or Stage 4, so re-evaluating the live bar is idempotent.

using System;
using cAlgo.API;
using cAlgo.API.Indicators;
using cAlgo.API.Internals;

namespace cAlgo.Indicators
{
    public enum StageShadeMode
    {
        None,        // no shading
        Rectangle,   // one chart rectangle per stage cycle
        BarColor,    // tint each bar via Chart.SetBarColor
    }

    [Indicator(IsOverlay = true, AccessRights = AccessRights.None)]
    public class StageDetector : Indicator
    {
        [Parameter("SMA Fast",  DefaultValue = 10, MinValue = 2, Group = "Ribbon")] public int FastLen  { get; set; }
        [Parameter("SMA Mid 1", DefaultValue = 20, MinValue = 2, Group = "Ribbon")] public int Mid1Len  { get; set; }
        [Parameter("SMA Mid 2", DefaultValue = 30, MinValue = 2, Group = "Ribbon")] public int Mid2Len  { get; set; }
        [Parameter("SMA Slow",  DefaultValue = 40, MinValue = 2, Group = "Ribbon")] public int SlowLen  { get; set; }

        [Parameter("Shade mode", DefaultValue = StageShadeMode.Rectangle, Group = "Display")] public StageShadeMode ShadeMode { get; set; }
        [Parameter("Shade opacity (0-255)", DefaultValue = 40, MinValue = 0, MaxValue = 255, Group = "Display")] public int ShadeOpacity { get; set; }

        [Output("SMA 10", LineColor = "DodgerBlue",   PlotType = PlotType.Line, Thickness = 2)]
        public IndicatorDataSeries Sma10 { get; set; }
        [Output("SMA 20", LineColor = "Orange",       PlotType = PlotType.Line, Thickness = 2)]
        public IndicatorDataSeries Sma20 { get; set; }
        [Output("SMA 30", LineColor = "MediumPurple", PlotType = PlotType.Line, Thickness = 2)]
        public IndicatorDataSeries Sma30 { get; set; }
        [Output("SMA 40", LineColor = "Crimson",      PlotType = PlotType.Line, Thickness = 2)]
        public IndicatorDataSeries Sma40 { get; set; }

        // Not marked [Output] — see header comment.
        public IndicatorDataSeries Stage { get; private set; }

        private SimpleMovingAverage _f, _m1, _m2, _s;
        private int _lastDirectional; // 0 = none, 2 = Stage 2, 4 = Stage 4

        // Rectangle-mode run tracking. _runStart is the bar index where the
        // current stage run began. Finalised runs stay on the chart under
        // "stage_<startIndex>". The in-progress run is re-drawn under the
        // same name on every Calculate, so the rectangle grows with the run.
        private int _runStart  = -1;
        private int _lastSeenIndex = -1;

        protected override void Initialize()
        {
            Stage = CreateDataSeries();
            _f  = Indicators.SimpleMovingAverage(Bars.ClosePrices, FastLen);
            _m1 = Indicators.SimpleMovingAverage(Bars.ClosePrices, Mid1Len);
            _m2 = Indicators.SimpleMovingAverage(Bars.ClosePrices, Mid2Len);
            _s  = Indicators.SimpleMovingAverage(Bars.ClosePrices, SlowLen);
        }

        public override void Calculate(int index)
        {
            double sma10 = _f.Result[index];
            double sma20 = _m1.Result[index];
            double sma30 = _m2.Result[index];
            double sma40 = _s.Result[index];

            Sma10[index] = sma10;
            Sma20[index] = sma20;
            Sma30[index] = sma30;
            Sma40[index] = sma40;

            if (index < SlowLen) { Stage[index] = double.NaN; return; }

            double price = Bars.ClosePrices[index];
            bool stage2 = price > sma10 && sma10 > sma20 && sma20 > sma30 && sma30 > sma40;
            bool stage4 = price < sma10 && sma10 < sma20 && sma20 < sma30 && sma30 < sma40;

            int stage;
            if (stage2)      { stage = 2; _lastDirectional = 2; }
            else if (stage4) { stage = 4; _lastDirectional = 4; }
            else             { stage = (_lastDirectional == 2) ? 3 : 1; }

            Stage[index] = stage;

            switch (ShadeMode)
            {
                case StageShadeMode.Rectangle: DrawRunRectangle(index, stage); break;
                case StageShadeMode.BarColor:  Chart.SetBarColor(index, BarColorFor(stage)); break;
            }
        }

        // Translucent: blended with chart background — used for Rectangle mode.
        private Color RectColor(int stage) => stage switch
        {
            1 => Color.FromArgb(ShadeOpacity, 255, 255, 255), // white
            2 => Color.FromArgb(ShadeOpacity,   0, 200,   0), // green
            3 => Color.FromArgb(ShadeOpacity, 230, 200,   0), // yellow
            4 => Color.FromArgb(ShadeOpacity, 220,   0,   0), // red
            _ => Color.FromArgb(0, 0, 0, 0),
        };

        // Solid: replaces the bar's own color — Chart.SetBarColor with low
        // alpha is effectively invisible, so BarColor mode ignores
        // ShadeOpacity. Stage 1 uses grey instead of white because white
        // bars on a default white chart are invisible.
        private Color BarColorFor(int stage) => stage switch
        {
            1 => Color.FromArgb(255, 160, 160, 160), // grey
            2 => Color.FromArgb(255,   0, 180,   0), // green
            3 => Color.FromArgb(255, 220, 180,   0), // yellow
            4 => Color.FromArgb(255, 210,   0,   0), // red
            _ => Color.FromArgb(0, 0, 0, 0),
        };

        // One rectangle per consecutive same-stage run. Finalised runs persist
        // under "stage_<startIndex>". The in-progress run is redrawn under the
        // same name every Calculate, extending in time and price as it grows.
        // Y bounds are min(low)/max(high) within the run — autoscale-safe.
        private void DrawRunRectangle(int index, int stage)
        {
            if (_runStart < 0) _runStart = index;

            // On the first call for a brand new bar, check whether the
            // just-closed bar (index-1) sits at a stage transition; if so the
            // previously-running rect is now "finalised" (already drawn under
            // its own name) and a new run begins at index-1.
            if (index > _lastSeenIndex && index - 2 >= SlowLen)
            {
                int prevStage     = (int)Stage[index - 1];
                int prevPrevStage = (int)Stage[index - 2];
                if (prevStage != prevPrevStage) _runStart = index - 1;
            }
            _lastSeenIndex = Math.Max(_lastSeenIndex, index);

            int runStage = (int)Stage[_runStart];
            if (runStage < 1 || runStage > 4) return;

            double yLow  = double.MaxValue;
            double yHigh = double.MinValue;
            for (int i = _runStart; i <= index; i++)
            {
                if (Bars.LowPrices[i]  < yLow)  yLow  = Bars.LowPrices[i];
                if (Bars.HighPrices[i] > yHigh) yHigh = Bars.HighPrices[i];
            }

            DateTime t1 = Bars.OpenTimes[_runStart];
            DateTime t2 = index + 1 < Bars.Count
                ? Bars.OpenTimes[index + 1]
                : Bars.OpenTimes[index] + (Bars.OpenTimes[index] - Bars.OpenTimes[index - 1]);

            var rect = Chart.DrawRectangle("stage_" + _runStart, t1, yLow, t2, yHigh, RectColor(runStage));
            rect.IsFilled = true;
        }
    }
}
