# JamaEarlyBot — cTrader cBot

C# port of the JAMA early-entry strategy from the Kotlin backtest in this
repo (`src/main/kotlin/com/arviman/ta/strategy/JamaStrategy.kt`).

Long-only by default. Mirrored short side available via the `LongOnly`
toggle. Defaults are tuned to the H8 XAUUSD optimization winner.

## Strategy in one paragraph

Enter on smoothed-RSI delta acceleration (jarvis), optionally with a short
moving-average-cross continuation (gated by recent jarvis memory). Exit on
soft reversal of the smoothed-RSI delta. Stop loss at N×ATR(14); take profit
at N×ATR(14)×ratio. One open position at a time, by label.

## Deploying

1. Open cTrader Studio → New cBot → paste `JamaEarlyBot.cs` (replace template).
2. Build (Ctrl+B). Should compile against cAlgo.API 4.x+.
3. Attach to a chart, set Symbol and Timeframe, configure parameters
   (defaults already match the gold H8 winner).
4. Backtest first on 2+ years of data.
5. Demo-account paper-trade for 2–4 weeks before going live.

## Parameter reference

| Group | Parameter | Type | Default | Notes |
|---|---|---|---|---|
| Sizing | `Volume (lots)` | numeric | 0.05 | At 5×ATR SL on gold (~$200/oz risk per oz), 0.05 lot ≈ $1,000 max per trade |
| Sizing | `Label` | string | `JAMA_Early` | Position identifier; change if running multiple instances |
| Sizing | `Long only` | bool | `true` | Off → mirrored short side enabled (regime-aware bear trades) |
| Risk | `SL × ATR(14)` | numeric | 4.0 | Stop distance in ATR units |
| Risk | `TP : SL ratio` | numeric | 5.0 | Take-profit distance = ratio × SL distance |
| Trend | `Mayer Multiple cap` | numeric | 1.1 | Long when `close/SMA200 < cap`; short when `> 1/cap` |
| Entry | `Entry mode` | enum | `JarvisOnly` | `JarvisOnly` (smoothed-RSI Δ), `MaCrossOnly` (close > zema5 > sma21), `Both` |
| Entry | `changeEma threshold` | numeric | 0.08 | Jarvis fires when `\|changeEma\| > threshold` |
| Entry | `MA-cross memory window` | enum | `Medium` | Only matters in `Both` mode. `Small`=5, `Medium`=10, `Large`=20, `Forever`=50 bars |
| Exit | `changeEma close-delta` | numeric | 0.05 | Soft exit fires when `changeEma < threshold − delta` |

## Validated per-asset profiles

These are the winners we found in our own backtests + your cTrader
optimization runs. Use as starting points.

### Gold (XAU/USD) on H8

```
Entry mode           = JarvisOnly
LongOnly             = true
SL × ATR(14)         = 4
TP : SL ratio        = 5
Mayer Multiple cap   = 1.1
changeEma threshold  = 0.08
changeEma close-delta= 0.05
Volume               = 0.05 lot
```

Rationale: gold is in a multi-year uptrend with relatively low daily ATR,
so a moderate SL and a high R:R lets the soft exit capture most of the
move. Shorting against the secular trend hurts more than helps.

### BTC/USD on H8 (allow shorts)

```
Entry mode           = MaCrossOnly
LongOnly             = false
SL × ATR(14)         = 5            (BTC ATR is much larger; tune via opt)
TP : SL ratio        = 5            (start, re-sweep on BTC)
Mayer Multiple cap   = 2.4          (BTC's MM ranges higher than gold)
changeEma threshold  = 0.06         (BTC has more bar-over-bar variance)
changeEma close-delta= 0.04
Volume               = 0.01–0.05 lot (BTC CFD lot sizes differ — check broker)
MA-cross memory      = Medium
```

Rationale: BTC has multi-month cycles; MA-cross catches sustained trends
better than the momentum-acceleration signal. Allowing shorts captures the
2022-style bear legs.

## Optimization profiles

### Per-asset fine sweep (recommended)

For each asset, **pin the entry mode** based on the validated profile and
sweep only the numeric knobs:

| param | values | combinations |
|---|---|---|
| `SL × ATR(14)` | 2, 3, 4, 5, 6, 7 | 6 |
| `TP : SL ratio` | 2, 3, 5, 7, 10 | 5 |
| `Mayer Multiple cap` | 0.9, 1.0, 1.1, 1.2, 1.5, 2.0, 2.4, 3.0 | 8 |
| `changeEma threshold` | 0.04, 0.06, 0.08, 0.10 | 4 |
| `changeEma close-delta` | 0.03, 0.04, 0.05, 0.06 | 4 |
| `LongOnly` | true, false | 2 |

= **3,840 runs** — finishes overnight on cTrader's optimizer.

### Full surface sweep (only if pin assumption needs re-validating)

Add `Entry mode` (3) × `MA-cross memory window` (4) = 12× expansion →
**46,080 runs**. Worth doing once per asset to confirm the pinned mode is
still optimal under the full dataset.

## Prop-firm sizing

For a $100K account with $5K daily DD ceiling and $10K total DD ceiling:

- **0.05 lot per entry** on gold: worst single SL hit ≈ $1,000 (5×ATR × 5
  oz × $40 ATR/oz). Five consecutive losses ≈ $5,000 — right at daily DD
  ceiling. Safe for cumulative-DD but watch losing streaks.
- **0.10 lot per entry** on gold: worst single SL ≈ $2,000. A four-loss
  streak hits the $10K total ceiling — too thin for variance. Don't size
  this on a prop account.
- **Daily-TF only fires ~10–14 trades/year**. Won't reach a 10% profit
  target inside a 30-day eval window. For prop firms specifically, run on
  **H8 or H4** to get more trade frequency.

## Diagnostic logging

The bot's `OnStart` prints the resolved config. To debug entry/exit firing
in backtest:

1. cTrader Studio → Backtest run → Logs panel
2. Look for `JamaEarlyBot ready.` to confirm the params took effect
3. If no trades: check `Bars.Count` reached 200 (SMA200 warmup)

For deeper diagnostics, an earlier commit
(`c4eb...` "Add diagnostic logging to JamaEarlyBot to debug 0-entry runs")
has a version with per-bar state Prints. Cherry-pick if needed.

## Code map

```
JamaEarlyBot.cs
├── enums: EntryMode, MemoryWindow
├── [Robot] class JamaEarlyBot : Robot
├── OnStart()       — instantiates RSI, EMA×2, SMA21, SMA200, ATR
├── OnBar()         — reads Last(1) bar, computes signals both sides,
│                     manages position (exit / regime-flip / entry)
├── ComputeEntry()  — switches between Mode (JarvisOnly/MaCrossOnly/Both)
├── OpenPosition()  — converts ATR-units SL/TP to pip distances, fires
│                     ExecuteMarketOrder with platform-managed stops
├── ComputeZema()   — inline ZEMA(5) for MA-cross path
└── MemoryBars()    — enum → int (5/10/20/50)
```

## Notes & gotchas

- **Indexing**: cTrader uses `index 0 = OLDEST`, opposite of the Kotlin
  reverse-chronological convention. `Last(0)` is the unclosed forming bar;
  signals evaluate on `Last(1)` (the most recent closed bar).
- **Symbol.PipSize for XAUUSD** varies by broker (commonly 0.1 or 0.01).
  The SL/TP pip math adapts automatically via `Symbol.PipSize`.
- **ATR calculation**: cTrader's `AverageTrueRange(14, MovingAverageType.Simple)`
  differs from our Kotlin Wilder's-style ATR. Expect small drift (<5%) in
  trade timing vs the Kotlin backtest.
- **Position management**: one position per label. To run multiple JAMA
  instances on the same account, give each a unique `Label`.

## Files

- `JamaEarlyBot.cs` — the cBot
- `README.md` — this file
