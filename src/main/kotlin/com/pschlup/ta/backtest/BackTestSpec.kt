package com.pschlup.ta.backtest

import com.pschlup.ta.indicators.Indicator
import com.pschlup.ta.strategy.Strategy
import com.pschlup.ta.timeseries.Bar
import com.pschlup.ta.timeseries.TimeFrame
import com.pschlup.ta.timeseries.TimeSeriesManager

/**
 * Optional per-tier exit ladder. When provided on [BackTestSpec.entryLadder], each
 * entry is split into N independent [com.pschlup.ta.backtest.TradeRecord]s, one per
 * tier, with its own SL price, TP price, position-size fraction, and optional %
 * trailing stop. Tiers close independently as price hits each level.
 *
 * Quantity fractions across tiers must sum to 1.0.
 *
 * SL and TP each accept either a fixed fraction of entry price OR a multiplier
 * applied to ATR-at-entry. The ATR form requires [BackTestSpec.entryAtrFactory].
 * Exactly one form must be provided for each of SL and TP.
 */
data class TpSlTier(
  /** Share of the total position assigned to this tier. */
  val quantityFraction: Double,
  /** Stop-loss distance as fraction of entry price (LONG: entry × (1 − slPct)). */
  val stopLossPct: Double? = null,
  /** Stop-loss distance as N × ATR-at-entry below/above entry price. */
  val stopLossAtrMultiplier: Double? = null,
  /** Take-profit distance as fraction of entry price (LONG: entry × (1 + tpPct)). */
  val takeProfitPct: Double? = null,
  /** Take-profit distance as N × ATR-at-entry above/below entry price. */
  val takeProfitAtrMultiplier: Double? = null,
  /** Optional % trailing stop (e.g. 0.05 = 5% behind current price). */
  val trailingStopPct: Double? = null,
) {
  init {
    require((stopLossPct == null) != (stopLossAtrMultiplier == null)) {
      "Tier needs exactly one of stopLossPct or stopLossAtrMultiplier"
    }
    require((takeProfitPct == null) != (takeProfitAtrMultiplier == null)) {
      "Tier needs exactly one of takeProfitPct or takeProfitAtrMultiplier"
    }
  }
}

/** Specifies the configuration for a back test run.  */
data class BackTestSpec(
  val tradeType: TradeType = TradeType.LONG,
  /** The base time frame at which the back tester runs. This is typically the time frame of the fastest indicator. */
  val runTimeFrame: TimeFrame,
  /** The strategy to back test. */
  val strategyFactory: StrategyFactory,
  /** The input bars to use in backtesting. Must be in the same time frame as the base time frame, or faster. */
  val inputBars: List<Bar>,
  /** The stop loss level (used when [entryLadder] is null). */
  val stopLoss: StopLossFactory,
  val trailingStops: Boolean,
  /** The % of the account to risk on each trade. 0.01 = 1% */
  val betSize: Double = 0.02,
  /** The initial account balance, in counter currency amount. */
  val startingBalance: Double = 10000.0,
  /** The average fee % charged by the exchange in each trade. */
  val feePerTrade: Double = 0.001,
  var pyramidingLimit: Int = 1,
  /** Level at which we exit 50% of the position. */
  val takeProfitIndicator: Indicator? = null,
  /**
   * Multi-tier TP/SL ladder. When non-null, each entry splits into one
   * [com.pschlup.ta.backtest.TradeRecord] per tier — see [TpSlTier]. The
   * [stopLoss] factory is ignored on entry sizing; total position is sized from
   * the effective risk (Σ tier.qty × tier.sl). [pyramidingLimit] should be
   * raised to N × desired pyramids (one ladder occupies N active trades).
   */
  val entryLadder: List<TpSlTier>? = null,
  /**
   * Indicator providing ATR-at-entry for tiers configured with
   * [TpSlTier.stopLossAtrMultiplier] or [TpSlTier.takeProfitAtrMultiplier].
   * Read at entry time only.
   */
  val entryAtrFactory: ((TimeSeriesManager) -> Indicator)? = null,
  /**
   * When set, every entry sizes at `balance × fraction / currentPrice`,
   * ignoring the SL distance for sizing. The SL still triggers stops; this
   * just decouples bet size from risk. Use with R:R-style strategies where
   * you want a fixed bet per trade and let SL/TP handle the outcome.
   */
  val fixedPositionFraction: Double? = null,
)

fun interface StopLossFactory {
  fun buildIndicator(timeSeriesManager: TimeSeriesManager): Indicator
}

fun interface StrategyFactory {
  fun buildStrategy(timeSeriesManager: TimeSeriesManager): Strategy
}