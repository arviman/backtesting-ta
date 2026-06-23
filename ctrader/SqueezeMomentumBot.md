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
| Trade | `Trade Volume (Lots)` | 0.8 | Broker-dependent: 0.8 lots = 8 ETH on the5ers convention |
| Trade | `Stop Loss (pips)` | 47 | Fixed pip distance |
| Trade | `TP Multiplier` | 1.0 | TP = SL × multiplier. **TP=0.5 was unprofitable** in OS backtest. |
| Trade | `Max Open Positions` | 4 | 6 also works; 4 is safer |
| Pyramiding | `MinEntryDistancePips` | 0 | Off — backtest showed no benefit |
| Pyramiding | `RequireProfitToPyramid` | true | **Keep ON** — 30% DD reduction |
| Pyramiding | `SlStaggerPips` | 0 | Off |
| Pyramiding | `TpStaggerMultiplier` | 0 | Off |

## Empirical results (3-year ETHUSD H1 OS slice)

Best config (V1 baseline + RequireProfitToPyramid on, all other stagger off):

```
trades         : 3,322
win rate       : 48.0%
profit factor  : 1.15
profit (abs)   : +$44,116
peak DD (abs)  : $21,457
```

Because position sizing is **fixed lots** (not balance-based), the
absolute dollar profit and DD are invariant of starting balance. Only
the percentages scale:

| Account size | profit% | DD% | Verdict |
|---|---|---|---|
| $5,000 | 882% | 429% | Blows up (DD > account) |
| $13,000 | 339% | 165% | Blows up |
| $50,000 | 88% | 43% | Survives but ugly |
| $100,000 | 44% | 21.5% | Fails FTMO 10% ceiling |
| $200,000 | 22% | 10.7% | Just over FTMO ceiling |
| $300,000 | 14.7% | 7.2% | **Passes FTMO** ✓ |

## Sizing for FTMO 10% DD ceiling

To pass FTMO ($100k account, 10% total DD ceiling) at this strategy's
edge, scale the volume down ~3×:

```
Trade Volume (Lots) = 0.03  (= 3 ETH per entry on FTMO's 100 ETH/lot)
```

Expected: ~$16k profit / ~$8k DD over 3yr → 16% profit / 8% DD on $100k.
Passes ceiling with room to spare; profit target hit in ~6 months on average.

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
