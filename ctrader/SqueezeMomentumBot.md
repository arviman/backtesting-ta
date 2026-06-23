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
| Trade | `Trade Volume (Lots)` | 0.5 | Broker-dependent. FTMO: 0.05 = 5 ETH. the5ers: 5.0 = 5 ETH. Set to give ~5 ETH per entry |
| Trade | `Stop Loss (pips)` (floor) | 47 | Acts as floor; structural SL extends wider when prior swing is farther |
| Trade | `SL Lookback Bars` | 80 | Bars to scan for structural swing low/high. 0 = static SL only |
| Trade | `SL Buffer (pips)` | 5 | Pad past structural level to avoid pixel-hunt stops |
| Trade | `TP Multiplier` | 1.0 | TP = SL × multiplier. **TP=0.5 was unprofitable** in OS backtest. |
| Trade | `Max Open Positions` | 4 | 6 also works; 4 is safer |
| Pyramiding | `MinEntryDistancePips` | 0 | Off — backtest showed no benefit |
| Pyramiding | `RequireProfitToPyramid` | true | **Keep ON** — 30% DD reduction |
| Pyramiding | `SlStaggerPips` | 0 | Off |
| Pyramiding | `TpStaggerMultiplier` | 0 | Off |

## Empirical results (3-year ETHUSD H1 OS slice)

### Final winner: V1 + profit gate + structural SL (lb=80), 5 ETH per entry

```
trades         : 2,734
win rate       : 39.2%
profit factor  : 1.21
profit (abs)   : +$59,400
peak DD (abs)  : $26,862
profit/DD      : 2.21
```

vs the static-SL baseline (also at 5 ETH per entry):

```
trades         : 3,322
win rate       : 48.0%
profit factor  : 1.15
profit (abs)   : +$27,572
peak DD (abs)  : $13,410
profit/DD      : 2.06
```

Structural SL more than doubles absolute profit while keeping the same
risk-adjusted ratio. The fewer trades + lower win rate are expected:
wider SL means trades that would have stopped at 47 pips now survive
through pullbacks; more catch real reversals; the rest cost more when
they do lose.

### Account scaling (fixed-lot → absolute $ invariant)

| Account size | profit% | DD% | Verdict |
|---|---|---|---|
| $50,000 | 119% | 54% | Blows up |
| $100,000 | 59% | 27% | Fails FTMO 10% ceiling |
| $200,000 | 30% | 13.4% | Still over FTMO |
| $300,000 | 20% | 9.0% | **Passes FTMO** ✓ |

### Yearly P&L (5 ETH per entry, structural SL lb=80)

```
2022: +$16,602
2023:  -$3,591  (mild down year)
2024: +$33,888  (big year)
2025: +$12,501
```

Every year except 2023 is positive; 2024 was the big year (heavy ETH
trending). Structural SL helps most in trending years.

## Sizing for FTMO 10% DD ceiling

To pass FTMO ($100k account, 10% total DD ceiling), scale volume down
to ~2 ETH per entry (current best $26.8k DD on 5 ETH → ~$10.7k on 2 ETH
= 10.7% of $100k, right at ceiling):

```
Trade Volume (Lots) = 0.02  (= 2 ETH per entry on FTMO's 100 ETH/lot)
```

Expected: ~$24k profit / ~$11k DD over 3yr → 24% profit / 11% DD on $100k.
Borderline on ceiling but profit-target friendly.

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
