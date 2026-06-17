# SwingRejectionBot — cTrader cBots

Two variants of a "smart-money / market-structure" rejection strategy.

- `SwingRejectionBot.cs` — V1, plain rejection setup.
- `SwingRejectionBotV2.cs` — V1 + CHoCH confirmation gate + HTF-trend gate.
- `SwingRejectionBotV3.cs` — V2 + two extra entry sources: BOS
  continuation and FVG (Fair Value Gap / order-block) mitigation, both
  toggleable. Defaults carry V2's optimizer winners.

## The idea (one paragraph)

The bot continuously detects swing highs and swing lows using a symmetric
N-bar fractal (a bar is a swing high if its high is the maximum of the
N bars on either side; mirror for swing lows). From the last two confirmed
swings on each side it labels the market state as **uptrend** (higher
highs and higher lows) or **downtrend** (lower highs and lower lows). It
then waits for price to "tag" the most recent swing in the *opposite*
direction of the dominant trend and reject it — i.e. in an uptrend it
looks for a bar that pokes the last swing high but closes well below it
(a lower-high rejection). V2 layers two filters on top: a CHoCH gate
(after the rejection, only enter if price actually breaks the opposing
swing within a few bars — confirming the trend has flipped) and an HTF
gate (only take longs above a higher-timeframe SMA and only take shorts
below it). Stops and targets are ATR-based; one position per label.

## Validated profile

### EURUSD H4 (V1)

```
PivotWindow          = 5
TagAtrMult           = 0.55
WickAtrMult          = 0.15
SlAtrMult            = 3.5
TpSlRatio            = 4.0
VolumeLots           = 1.0
```

Result on a ~3–4 year backtest: ~99 trades, ≈ +$10,000 on $10,000
starting balance (1.0 lot).

### EURUSD H4 (V2 — current defaults)

The V2 defaults reflect the top optimizer rows once the HTF + CHoCH gates
were enabled:

```
PivotWindow          = 3
TagAtrMult           = 0.45
WickAtrMult          = 0.25
RequireChoch         = true
ChochLookback        = 8
UseHtfFilter         = true
HtfTf                = H4*
HtfMaLength          = 60
SlAtrMult            = 2.0
TpSlRatio            = 2.5
VolumeLots           = 1.0
```

\* When running on H4 itself, pick D1 here for a true HTF filter. With
H4 the SMA is on the same timeframe and acts more like a long EMA bias
than a separate-TF filter.

## Parameter reference (V2)

| Group | Parameter | Type | Notes |
|---|---|---|---|
| Sizing | `Volume (lots)` | numeric | Per-trade lot size |
| Sizing | `Label` | string | Position identifier |
| Structure | `Pivot window` | numeric | Bars on each side for fractal swing |
| Structure | `Tag tolerance × ATR(14)` | numeric | How close bar high/low must come to the swing |
| Structure | `Wick × ATR(14)` | numeric | Min wick (rejection) size |
| CHoCH | `Require CHoCH confirm` | bool | Wait for opposing-swing break after setup |
| CHoCH | `CHoCH lookback (bars)` | numeric | Bars allowed for the break before setup expires |
| HTF | `Use HTF trend filter` | bool | Gate by HTF SMA bias |
| HTF | `HTF timeframe` | enum | H1, H4, D1 |
| HTF | `HTF MA length` | numeric | SMA length on the HTF |
| Risk | `SL × ATR(14)` | numeric | Stop distance |
| Risk | `TP : SL ratio` | numeric | Target distance as multiple of SL |

## V3 — BOS + FVG additions

V3 adds two more entry paths alongside the V2 rejection logic. All three
sources can be toggled independently so the optimizer can tell us which
contribute edge.

- **BOS continuation** (`UseBosEntries`): in a confirmed HH/HL regime,
  fire LONG the bar that closes above the last swing high. Mirror SHORT
  on close below the last swing low in LH/LL. Pure trend-follow break.
- **FVG mitigation** (`UseFvgEntries`, `FvgMaxAge`): detect a 3-bar Fair
  Value Gap on the most recent confirmed triplet (bars at offsets 3, 2,
  1). Bullish FVG zone = `[high(Last(3)), low(Last(1))]` when those
  bounds don't overlap. When a later bar closes back into the zone for
  the first time, fire in the gap's direction. Zone expires after
  `FvgMaxAge` bars to avoid trading stale levels.

All three sources still pass through the HTF SMA bias when
`UseHtfFilter` is on. SL/TP, pivot detection, and CHoCH gate logic are
unchanged from V2. Defaults ship BOS and FVG OFF — turn them on one at
a time in the optimizer.

## Optimization grid (V2 — start here)

| param | values |
|---|---|
| `PivotWindow` | 3, 5, 8 |
| `TagAtrMult` | 0.35, 0.45, 0.55 |
| `WickAtrMult` | 0.15, 0.25, 0.35 |
| `RequireChoch` | false, true |
| `ChochLookback` | 5, 8, 12 |
| `UseHtfFilter` | false, true |
| `HtfMaLength` | 40, 60, 100 |
| `SlAtrMult` | 2, 3, 4 |
| `TpSlRatio` | 2, 3, 4 |

## Notes & gotchas

- Pivot is a **lagging** detection: a swing is only confirmed
  `PivotWindow` bars after it forms. Tighter `PivotWindow` = less lag
  but more false swings.
- The bot evaluates signals on `Last(1)` (the just-closed bar). The
  forming bar is ignored. This is intentional — no intra-bar repaint.
- One position per `Label`. Run two instances with different labels for
  independent long and short books.
- ATR uses `MovingAverageType.Simple` and 14 periods; matches the H4
  optimization run.
