# SqueezeMomentumBot — cTrader cBot

C# port of LazyBear's "Squeeze Momentum Indicator" wrapped as a strategy.
Designed for ETHUSD H1. Defaults reflect the Kotlin backtest findings on
71k bars of ETHUSD H1 (3yr OS slice).

## Strategy in one paragraph

The bot computes Bollinger Bands and Keltner Channels on every bar. The
squeeze fires when the BB has expanded *outside* the KC (`sqzOff` =
volatility expansion). When that's true, it reads a linear-regression
momentum histogram (`val`) that's positive-rising for bullish acceleration
and negative-falling for bearish. Long entries fire on every bar that is
`sqzOff` + `val>0` + `val rising` + HTF SMA bullish. Short mirrors. Net
mode: long entry is blocked if shorts are open and vice versa. Exits
trigger on momentum reversal (val crossed zero AND still moving away).
Stops and targets are fixed in pips; one position per label.

## Pyramiding gates

The bot supports up to `MaxPositions` simultaneous same-direction trades
(pyramiding). Three optional gates control when a new pyramid position can
be added:

| Gate | Default | Why |
|---|---|---|
| `MinEntryDistancePips` | 0 (OFF) | Backtest: forcing pyramid spread costs more profit than it saves |
| `RequireProfitToPyramid` | true (ON) | Backtest: same profit, 30% lower DD — strict win |
| `SlStaggerPips` | 0 (OFF) | Backtest: no measurable benefit |
| `TpStaggerMultiplier` | 0 (OFF) | Backtest: no measurable benefit |

Only `RequireProfitToPyramid` survived backtest as a clean improvement.
Keep it ON; leave the others at 0 unless you re-optimize for a specific
asset / period.

## Default param reference

| Group | Parameter | Default | Notes |
|---|---|---|---|
| Squeeze | `BB Length` | 11 | LazyBear default 20; optimized for ETH H1 |
| Squeeze | `BB MultFactor` | 2.3 | LazyBear default 2.0 |
| Squeeze | `KC Length` | 22 | |
| Squeeze | `KC MultFactor` | 1.4 | LazyBear default 1.5; tighter for more sqzOff signals |
| HTF | `HigherTimeframe` | H1 | Same TF as trading; acts as a long-MA trend bias |
| HTF | `HtfSmaPeriod` | 47 | |
| Trade | `Trade Volume (Lots)` | 0.6 | Sweet spot for the5ers $10K total / $5k daily DD. Adjust per broker (e.g. 0.06 on FTMO 1lot=100ETH, 6.0 on the5ers 1lot=1ETH) |
| Trade | `Stop Loss (pips)` | 47 | Fixed pip distance |
| (hidden) | `SL Lookback Bars` | const 0 | Structural SL disabled. Re-enable in source if trading personal capital — adds ~50% profit but busts the5ers $5k daily-loss rule |
| (hidden) | `SL Buffer (pips)` | const 5 | Only used when SlLookbackBars > 0 |
| Trade | `TP Multiplier` | 1.0 | TP = SL × multiplier. **TP=0.5 was unprofitable** in OS backtest. |
| Trade | `Max Open Positions` | 4 | 6 also works; 4 is safer |
| Pyramiding | `MinEntryDistancePips` | 0 | Off — backtest showed no benefit |
| Pyramiding | `RequireProfitToPyramid` | true | **Keep ON** — 30% DD reduction |
| Pyramiding | `SlStaggerPips` | 0 | Off |
| Pyramiding | `TpStaggerMultiplier` | 0 | Off |

## Empirical results (3-year ETHUSD H1 OS slice)

### Prop-firm config: static SL at 6 ETH per entry

```
trades         : 3,322 (over 3 years)
win rate       : 48.0%
profit factor  : 1.15
3yr profit     : +$33,086
3yr peak DD    : $16,092
profit/DD      : 2.06
```

### Yearly breakdown at 6 ETH per entry (static SL)

```
Year  | P&L      | FromStartDD | MaxDailyLoss | the5ers verdict
2022  | +$19,415 | $10         | $1,435       | PASS BOTH targets ✓
2023  |  -$6,997 | $9,616      | $1,390       | Miss target but SURVIVE ($9.6k < $10k)
2024  | +$12,817 | $2,874      | $2,042       | PASS BOTH targets ✓
2025  |  +$7,852 | $970        | $1,994       | PASS BOTH targets ✓
```

**3 of 4 years pass both phases. 0 of 4 bust the $10k total DD or $5k daily DD limits.**

### Why not structural SL?

Structural SL (lookback 80 bars) more than doubles profit on personal
accounts but **busts the5ers $5k daily-loss rule at 5+ ETH**:

| Position | Static SL daily max | Structural SL daily max |
|---|---|---|
| 3 ETH | $1,021 | $3,502 |
| 5 ETH | $1,702 | **$5,837** (busts daily) |
| 6 ETH | $2,042 | ~$7,005 (busts daily) |
| 8 ETH | $2,723 | **$9,339** (busts daily) |

Static SL keeps the daily loss comfortably under $3k even at 8 ETH. Use
structural SL only if you're trading personal capital with no DD ceilings.

## Sizing for the5ers ($10k total DD, $5k max daily loss)

Static SL at **6 ETH per entry** is the sweet spot:
- 0 of 4 yearly windows bust either DD rule
- 3 of 4 yearly windows pass both phases ($8k / $5k targets)
- Maximum 6mo expected weeks to funded: ~5-10 weeks in normal conditions

```
Trade Volume (Lots) = 0.6   (1lot=10ETH broker convention)
SL Lookback Bars   = 0      (locked OFF — required for daily-loss rule)
Stop Loss (pips)   = 47
TP Multiplier      = 1.0
Max Open Positions = 4
```

Drop to 5 ETH if you want 100% survival across the tested years (gives
up 1 P1 hit rate in exchange for higher confidence).

## Sizing for the5ers ($5-10k accounts)

The strategy is fundamentally too aggressive for sub-$30k accounts at
any pyramid depth. Either:
- Drop `MaxPositions` to 1 (no pyramid) and accept much lower profit
- Use a smaller asset / smaller per-trade exposure
- Skip the5ers for this strategy

## Optimization grid (start here for a different asset)

| param | values |
|---|---|
| `BbLength` | 10, 11, 14, 20 |
| `BbMult` | 2.0, 2.1, 2.3 |
| `KcLength` | 20, 22 |
| `KcMult` | 1.0, 1.4, 1.5 |
| `HtfSmaPeriod` | 30, 47, 60 |
| `StopLossPips` | 30, 47, 60 |
| `TpMultiplier` | 0.7, 1.0, 1.5 |
| `MaxPositions` | 2, 4, 6 |
| `RequireProfitToPyramid` | true (lock) |

## Files

- `SqueezeMomentumBot.cs` — the cBot
- `SqueezeMomentumBot.md` — this file
- Kotlin reference port: `src/main/kotlin/com/arviman/ta/strategy/SqueezeMomentumStrategy.kt`
- Pine version: `pinescripts/SqueezeMomentumStrategy.pine`
