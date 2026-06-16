# kotlin-ta — Kotlin Technical Analysis & Backtesting Library

A Kotlin/JVM library for **technical analysis** and **strategy backtesting** with built-in multi-timeframe support. Define indicators, compose signals, and run backtests against historical OHLCV data.


## Getting Started

```
brew install maven
```

## Project Structure

```
backtesting-ta/
├── pom.xml                          # Maven build (Kotlin 1.5.10, JVM target 1.8)
├── LICENSE                          # Apache 2.0
├── sampledata/                      # Sample OHLCV data (5-minute bars)
│   ├── chart_data_BTC_USDT_p5_730d.csv   # BTC/USDT 2 years
│   ├── chart_data_BTC_USDT_p5_90d.csv    # BTC/USDT 90 days
│   ├── chart_data_ETH_USDT_p5_730d.csv   # ETH/USDT 2 years
│   ├── chart_data_EOS_USDT_p5_730d.csv   # EOS/USDT 2 years
│   └── chart_data_STR_USDT_p5_730d.csv   # STR/USDT 2 years
└── src/
    └── main/kotlin/com/pschlup/ta/
        ├── BackTestDemo.kt              # Entry point: runs a demo backtest on BTC/USDT
        ├── backtest/                    # Backtesting engine
        │   ├── BackTester.kt            # Core event loop — iterates bars, evaluates signals, manages trades
        │   ├── BackTestSpec.kt          # Immutable config: input data, timeframes, risk params, factories
        │   ├── BackTestReport.kt        # Computes metrics: profitability, win rate, drawdown, Sortino ratio
        │   ├── TradeHistory.kt          # Tracks active/closed trades, equity curve, max drawdown
        │   ├── TradeRecord.kt           # Individual trade: entry/exit price, stop-loss, P&L
        │   └── TradeType.kt             # Enum: LONG, SHORT
        ├── indicators/                  # Technical indicator computations
        │   ├── Indicator.kt             # Fun interface: (Int) -> Double
        │   ├── Indicators.kt            # SMA, EMA, MMA, ATR, CCI, Stochastic, volatilityStop, chandelierStop, price extractors
        │   ├── KeltnerChannel.kt        # Keltner Channel (EMA + ATR bands)
        │   ├── LinearRegressionSlope.kt # Linear regression slope over N bars
        │   └── RSI.kt                   # Relative Strength Index
        ├── strategy/                    # Signal & strategy system
        │   ├── Strategy.kt              # Combines trend / entry / exit signals into trade decisions
        │   ├── Signal.kt                # Boolean fun interface + combinators (`and`, `or`, `not`, crossover, isOver/isUnder)
        │   └── SignalType.kt            # Enum: NO_OP, ENTRY, EXIT
        └── timeseries/                  # Time series & data management
            ├── Bar.kt                   # OHLCV candle with timeframe and timestamp
            ├── TimeFrame.kt             # Enum: M5, M10, M15, M30, H1, H2, H4, D
            ├── TimeSeries.kt            # LinkedList-backed bar series at a given timeframe
            ├── TimeSeriesManager.kt     # Multi-timeframe orchestrator with downsampling
            ├── CsvBarSource.kt          # Parses OHLCV CSV files into List<Bar>
            └── UnstablePeriodException.kt  # Thrown when insufficient bars for an indicator

src/test/kotlin/com/pschlup/ta/
├── helpers/
│   └── FakeTimeSeries.kt               # Test utility: builds TimeSeries from arrays
├── indicators/
│   ├── AverageTrueRangeTest.kt
│   ├── CCITest.kt
│   ├── ExponentialMovingAverageTest.kt
│   ├── HighestValueTest.kt
│   ├── LinearRegressionSlopeTest.kt
│   ├── LowestValueTest.kt
│   ├── MeanDeviationTest.kt
│   └── SimpleMovingAverageTest.kt
└── timeseries/
    └── BarTest.kt

src/test/resources/
└── test_chart_data.csv                  # 1001-line 5-minute BTC OHLCV test fixture
```

## Quick Start

**Prerequisites:** JDK 8+, Maven 3+

```bash
# Build and run the demo backtest
mvn

# Run tests
mvn test
```

The demo executes a multi-timeframe EMA/CCI trend-following strategy on 2 years of BTC/USDT 5-minute data and prints a report:

```
----------------------------------
Finished backtest with 306 trades
----------------------------------
Pyramiding limit   : 2
Account risk/trade : 2.0%
----------------------------------
Profitability      : 270.98%
Buy-and-hold       : 366.08%
Vs buy-and-hold    : -25.98%
Win rate           : 37.58%
Max drawdown       : 35.92%
Risk/reward ratio  : 2.82
Sortino ratio      : 7.54
----------------------------------
Start balance      : $20,000.00
Profit/loss        : $54,195.38
Final balance      : $61,521.90
----------------------------------
```

## Usage

### 1. Loading Data

```kotlin
import com.pschlup.ta.timeseries.readCsvBars

val bars = readCsvBars("sampledata/chart_data_BTC_USDT_p5_730d.csv")
```

CSV format expects columns: `Date,Open,High,Low,Close,Volume` (timestamps in ISO-8601).

### 2. Managing Time Series

```kotlin
import com.pschlup.ta.timeseries.TimeFrame
import com.pschlup.ta.timeseries.TimeSeriesManager

val manager = TimeSeriesManager(inputTimeFrame = TimeFrame.M5)

for (bar in bars) {
    manager += bar  // feeds the bar into all active time series
}

// Access downsampled series via convenience properties
val m5  = manager.m5   // 5-minute
val m15 = manager.m15  // 15-minute (auto-downsampled)
val h1  = manager.h1   // 1-hour
val h4  = manager.h4   // 4-hour
val d   = manager.d    // daily
```

**Available timeframes:** `M5`, `M10`, `M15`, `M30`, `H1`, `H2`, `H4`, `D`

Bar indices are **reverse-chronological**: index `0` is the latest/newest bar, index `1` is the one before, and so on.

### 3. Computing Indicators

```kotlin
import com.pschlup.ta.indicators.*

// Price extractors
val close = h1.closePrice
val high  = h1.highPrice
val low   = h1.lowPrice
val typical = h1.typicalPrice   // (H + L + C) / 3
val volume  = h1.volume

// Moving averages
val sma20 = close.sma(20)        // Simple Moving Average
val ema30 = close.ema(30)        // Exponential Moving Average
val mma14 = close.modifiedMovingAverage(14)  // Modified (SMMA/RMA)

// Oscillators
val rsi14  = close.rsi(14)       // Relative Strength Index
val cci20  = h1.cci(20)          // Commodity Channel Index
val stoch  = h1.stochastic(20)   // Stochastic %K
val stochRsi = rsi14.stochasticRsi(14)  // Stochastic RSI

// Volatility
val atr14 = h1.averageTrueRange(14)      // Average True Range (MMA-smoothed)
val trueRange = h1.trueRange()           // Raw true range
val meanDev = close.meanDeviation(20)    // Mean deviation

// Derived
val highest20 = close.highestValue(20)   // Rolling highest
val lowest20  = close.lowestValue(20)    // Rolling lowest

// Stop-loss indicators
val volStop = h1.volatilityStop(length = 10, multiplier = 3.0)   // ATR-based
val chandStop = h1.chandelierStop(length = 10, atrDistance = 3.0) // Chandelier exit

// Channel
val keltner = h1.keltnerChannel(emaLength = 20, atrLength = 10, atrMultiplier = 2.0)

// Trend
val lrSlope = close.linearRegressionSlope(20)  // Slope over N bars

// Getting values
val latestClose   = close.latestValue    // same as close[0]
val previousClose = close[1]
```

Indicators are **lazy and dynamic** — they recompute as new bars are added to the time series.

### 4. Composing Signals

Signals are boolean `fun interface`s that can be combined with infix operators:

```kotlin
import com.pschlup.ta.strategy.*

val ema8  = close.ema(8)
val ema21 = close.ema(21)
val rsi   = close.rsi()

// Crossover signals
val bullishCross = ema8 crossedOver ema21
val bearishCross = ema8 crossedUnder ema21

// Threshold signals
val rsiOverbought = rsi isOver 70.0
val rsiOversold   = rsi isUnder 30.0

// Composite signals
val entry = (ema8 crossedOver ema21) and (rsi isOver 70.0)
val exit  = (ema8 crossedUnder ema21) or (rsi isUnder 30.0)

// Boolean operators
val inverted = entry.not()                       // !entry
val combined = signalA and signalB or signalC

// Getting values
val shouldEnter: Boolean = entry.latestValue     // same as entry[0]
```

### 5. Defining a Strategy & Backtest

```kotlin
import com.pschlup.ta.backtest.*
import com.pschlup.ta.strategy.Strategy

val spec = BackTestSpec(
    tradeType = TradeType.LONG,
    runTimeFrame = TimeFrame.H1,            // Strategy evaluated on 1h bars
    inputBars = readCsvBars("sampledata/chart_data_BTC_USDT_p5_730d.csv"),
    trailingStops = false,
    pyramidingLimit = 2,                    // Max 2 simultaneous trades
    startingBalance = 20_000.0,
    betSize = 0.02,                         // Risk 2% of account per trade
    feePerTrade = 0.001,                    // 0.1% exchange fee
    strategyFactory = { seriesManager ->
        val h1 = seriesManager.h1
        val h1Price = h1.closePrice

        Strategy(
            trendSignal = (h1Price.ema(4) isOver h1Price.ema(30)),
            entrySignal = h1.cci(10) crossedOver 100.0,
            exitSignal = h1Price.ema(4) crossedUnder h1Price.sma(20)
        )
    },
    stopLoss = { manager ->
        manager.h4.volatilityStop(length = 4, multiplier = 0.2)
    }
)

val report = BackTester.run(spec)

println("Profitability: %.2f%%".format(100 * report.profitability))
println("Win rate:      %.2f%%".format(100 * report.winRate))
println("Max drawdown:  %.2f%%".format(100 * report.maxDrawDown))
println("Final balance: \$%,.2f".format(report.finalBalance))
```

### 6. Report Metrics

| Property | Description |
|---|---|
| `profitability` | Total P&L / initial balance |
| `profitLoss` | Sum of all trade P&L |
| `finalBalance` | Starting balance + profit/loss |
| `winRate` | Profitable trades / total trades |
| `maxDrawDown` | Largest peak-to-trough decline |
| `buyAndHoldProfitability` | If you simply held the asset |
| `vsBuyAndHold` | Strategy vs buy-and-hold comparison |
| `riskReward` | Average risk-reward per trade |
| `sortinoRatio` | Sortino ratio (risk-free rate = 0%) |
| `tradeCount` | Total number of trades |

## Architecture

### Data Flow

```
CSV File → readCsvBars() → List<Bar>
                ↓
        TimeSeriesManager += bar    (feeds bars into M5/M15/H1/H4/D series via downsampling)
                ↓
        BackTester.run(spec)        (main event loop)
                │
                ├── TimeSeriesManager tracks bars at all timeframes
                ├── Strategy evaluates trend/entry/exit signals at each runTimeFrame bar close
                ├── Indicators compute values on-the-fly (lazy, index-based)
                ├── TradeHistory manages active/closed trades, equity curve, drawdown
                │       ├── Entry: position size = (balance × betSize) / risk
                │       ├── Stop-loss from stopLoss factory (e.g., volatilityStop)
                │       └── Optional trailing stops
                └── BackTestReport computes final metrics
```

### Index Convention

All indicators and signals use **reverse-chronological indexing**, where index `0` is always the latest (most recent) bar. This means:
- `close[0]` = most recent close price
- `close[1]` = previous bar's close price
- `close[index]` looks backward `index` bars

Indicators compute windowed values by iterating from `index` to `index + length`, i.e., looking back over historical data.

### Position Sizing

Uses a **fixed-fractional** model — risks a fixed percentage of the current account balance on each trade. The stop-loss distance (determined by the `stopLoss` factory) dictates the position size:

```
positionSize = (accountBalance × betSize) / (entryPrice − stopLossPrice)
```

### Strategy Logic

A `Strategy` is composed of three optional signals:

| Signal | Purpose |
|---|---|
| `trendSignal` | Identifies market direction — entry signals are ignored if this is `false` |
| `entrySignal` | Triggers a new trade (subject to pyramiding limit) |
| `exitSignal` | Closes all active trades |

When a bar closes, the backtester:
1. Evaluates the exit signal — if true, closes all open trades.
2. Evaluates the trend signal — if false, skips entry.
3. Evaluates the entry signal — if true and below pyramiding limit, opens a new trade.

## Dependencies

| Dependency | Version | Scope |
|---|---|---|
| Kotlin stdlib | 1.5.10 | compile |
| OpenCSV | 4.1 | compile |
| JUnit Jupiter | 5.4.2 | test |
| Google Truth | 1.0 | test |

## License

Apache 2.0 — see [LICENSE](LICENSE).
