# SwingRejectionBot — cTrader cBots

Two variants of a "smart-money / market-structure" rejection strategy.

- `SwingRejectionBot.cs` — V1, plain rejection setup.
- `SwingRejectionBotV2.cs` — V1 + CHoCH confirmation gate + HTF-trend
  gate. Current production version.

V3 (with BOS continuation + FVG mitigation entries) was prototyped and
removed after EURUSD-H4 optimization showed BOS and FVG sources OFF in
every top run. Restore from git history if a different asset/TF
benefits from those entry paths.

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

From the second optimization sweep (this time on V3 with BOS/FVG
disabled — equivalent to V2):

```
PivotWindow          = 8
TagAtrMult           = 0.35
WickAtrMult          = 0.40
RequireChoch         = false       # CHoCH gate hurt on EURUSD H4
ChochLookback        = 5
UseHtfFilter         = true
HtfTf                = H1          # H1 SMA from H4 chart = LTF micro-trend gate
HtfMaLength          = 55
SlAtrMult            = 4.5         # wide stop pays off
TpSlRatio            = 3.5
VolumeLots           = 1.0
```

Key shifts vs the V1 winners: wider pivot window (fewer false swings),
tighter tag tolerance, fatter wick requirement, CHoCH gate dropped, an
H1 SMA(55) trend bias (acting as a fast micro-trend filter from H4),
and a wider SL paired with a moderate TP:SL ratio.

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
