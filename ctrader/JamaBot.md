# JAMA_HCC — strategy notes (no cBot port)

The original JAMA HCC strategy is implemented in Kotlin only
(`src/main/kotlin/com/arviman/ta/strategy/JamaStrategy.kt`). It's the
"full-gate" version of what `JamaEarlyBot.cs` is the early-entry port
of. This doc summarises the original so the cBot port can be re-derived
later if needed.

## The idea (one paragraph)

JAMA_HCC pairs a momentum-acceleration entry (the "jarvis" smoothed-RSI
delta) with a strict trend-regime confirmation built from a Hurst Cycle
Channel and a Mayer Multiple cap. The entry fires when the smoothed-RSI
delta crosses above a threshold (`changeEmaThreshold`, default 0.06) or
when a short MA-cross continues an existing jarvis impulse. The trend
filter then requires (a) close above the upper percentile of both a
short and a medium Hurst cycle band, (b) close-to-SMA200 ratio below a
ceiling (`mmCap`), (c) the higher-TF SMA50 rising and angled above a
slope threshold, and (d) the medium Hurst median angled positively.
Exits are the soft jarvis rollover (smoothed-RSI delta drops below
threshold − closeDelta) plus an ATR-based stop and a multi-ATR take
profit; the SL/TP ladder is single-tier by default
(`stopLossAtrMultiplier`, `takeProfitAtrMultiplier = slAtrMult × tpSlRatio`).

## Parameter map (Kotlin → cBot)

The cBot port (`JamaEarlyBot.cs`) drops the heavier gates. This table
shows what's kept and what's removed:

| Kotlin (`JamaParams`) | cBot (`JamaEarlyBot.cs`) | Notes |
|---|---|---|
| `slAtrMult` | `SlAtrMult` | kept |
| `tpSlRatio` | `TpSlRatio` | kept |
| `changeEmaThreshold` | `ChangeEmaThreshold` | kept |
| `closeDelta` | `CloseDelta` | kept |
| `mmCap` | `MmCap` | kept |
| `earlyEntry` | (hard-coded `true`) | cBot only ships the early variant |
| `useJarvisMemory` + `jarvisMemoryBars` | `Mode` + `JarvisMemory` (currently commented out) | enum-pinned in cBot for optimizer-friendliness |
| `hurstShortLongPct` / `hurstMediumLongPct` | — | Hurst breakout gate dropped |
| `sma50SlopeDeg` / `mediumHurstSlopeDeg` | — | McTrend slope gates dropped |
| `disableSoftExit` | — | soft exit always on |
| `useAngleChangeExit` | — | not ported |
| `trailingStopPct` | — | platform-managed stops only |

## Why JAMA is Kotlin-only

The full-gate version trades infrequently — the Hurst breakout + SMA50
slope + medHurst slope stack lets only the strongest impulses through.
On daily and 8H gold/BTC backtests, the *early* variant
(`earlyEntry = true`) reached comparable risk-adjusted returns with 2–3×
the trade count, so the cBot ships the early variant and exposes only
the knobs that mattered in the IS / OS sweeps. If a future asset
*needs* the strict gates, port them by mirroring `makeJamaHccStrategy`:
the Hurst channel reduces to a Bollinger-style envelope of period 10
(`mult 1.0`) and 30 (`mult 3.0`) on the same series, and the SMA50
slope is just the `angle(sma50)` from `JamaIndicators.kt` ported to C#.

## Reading list

- Strategy code: `src/main/kotlin/com/arviman/ta/strategy/JamaStrategy.kt`
- Indicators: `src/main/kotlin/com/arviman/ta/indicators/JamaIndicators.kt`
- Backtest harness: `src/main/kotlin/com/arviman/ta/backtest/BackTester.kt`
- Smoke tests / sweeps: `src/main/kotlin/com/arviman/ta/JamaMemoryTest.kt`,
  `ParameterSweep.kt`, `GoldComparison.kt`
- cBot port of the early variant: `ctrader/JamaEarlyBot.cs`,
  documented in `ctrader/JamaEarlyBot.md`
