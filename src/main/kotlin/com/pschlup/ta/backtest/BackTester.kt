package com.pschlup.ta.backtest

import com.pschlup.ta.backtest.TradeType.LONG
import com.pschlup.ta.indicators.latestValue
import com.pschlup.ta.timeseries.TimeSeriesManager
import com.pschlup.ta.strategy.SignalType
import com.pschlup.ta.timeseries.UnstablePeriodException
import java.time.Instant

object BackTester {
  @JvmStatic
  fun run(spec: BackTestSpec): BackTestReport {
    val inputBars = spec.inputBars
    val inputTimeFrame = inputBars[0].timeFrame
    val timeSeriesManager = TimeSeriesManager(inputTimeFrame)
    val strategy = spec.strategyFactory.buildStrategy(timeSeriesManager)
    val stopLoss = spec.stopLoss.buildIndicator(timeSeriesManager)
    val entryAtr = spec.entryAtrFactory?.invoke(timeSeriesManager)
    val runTimeSeries = timeSeriesManager.getTimeSeries(spec.runTimeFrame)
    val tradeHistory = TradeHistory(
      balance = spec.startingBalance,
      feePerTrade = spec.feePerTrade
    )

    for (inputBar in inputBars) {
      timeSeriesManager += inputBar
      val currentPrice = inputBar.close

      // Ratchet any per-tier % trailing stops before evaluating stop hits.
      tradeHistory.trailPctStops(currentPrice)

      // Closes stopped trades + take-profit hits
      tradeHistory.closeStoppedTrades(currentPrice)
      tradeHistory.closeTakeProfitTrades(currentPrice)

      // Updates trade history equity, drawdown.
      tradeHistory.updateEquity(currentPrice)

      // Avoids trading before the next close.
      // TODO: improve detection of new bars in the run time series.
      if (runTimeSeries.latestBar?.closeTime != inputBar.closeTime) {
        continue
      }
      try {
        // Moves trailing stops.
        if (spec.trailingStops) {
          tradeHistory.trailStops(stopLoss.latestValue)
        }
        val signal = strategy.signal
        if (signal === SignalType.EXIT) {
          tradeHistory.exitActiveTrades(currentPrice)
        } else if (signal === SignalType.ENTRY
          && tradeHistory.activeTradeCount < spec.pyramidingLimit
          && tradeHistory.balance > 0
        ) {
          val ladder = spec.entryLadder
          if (ladder != null) {
            openLadderEntry(spec, tradeHistory, ladder, currentPrice, inputBar.closeTime, entryAtr?.latestValue)
          } else {
            openSingleEntry(spec, tradeHistory, stopLoss.latestValue, currentPrice, inputBar.closeTime)
          }
        }
      } catch (e: UnstablePeriodException) {
        // Ignores trade signals during unstable period.
      }
    }

    // Closes trades that remain open in the end. (helpers below)
    val lastPrice: Double = runTimeSeries.latestBar?.close ?: Double.NaN
    tradeHistory.exitActiveTrades(lastPrice)
    val startPrice: Double = runTimeSeries.oldestBar?.open ?: Double.NaN
    return BackTestReport(
      spec = spec,
      trades = tradeHistory.trades,
      finalBalance = tradeHistory.balance,
      maxDrawDown = tradeHistory.maxDrawDown,
      startPrice = startPrice,
      endPrice = lastPrice,
    )
  }

  private fun openSingleEntry(
    spec: BackTestSpec,
    tradeHistory: TradeHistory,
    stopLossPrice: Double,
    currentPrice: Double,
    closeTime: Instant,
  ) {
    val risk = currentPrice - stopLossPrice
    val maxBalanceRisk = tradeHistory.balance * spec.betSize
    var amount = maxBalanceRisk / risk
    if (amount > tradeHistory.balance / currentPrice) {
      amount = tradeHistory.balance / currentPrice
    }
    tradeHistory += TradeRecord(
      type = spec.tradeType,
      timestamp = closeTime,
      entryPrice = currentPrice,
      amount = amount,
      stopLossPrice = stopLossPrice,
      trailingStopDistance = currentPrice - stopLossPrice,
    )
  }

  private fun openLadderEntry(
    spec: BackTestSpec,
    tradeHistory: TradeHistory,
    ladder: List<TpSlTier>,
    currentPrice: Double,
    closeTime: Instant,
    atrAtEntry: Double?,
  ) {
    // Resolve each tier's SL and TP distances in price-fraction units.
    val resolved = ladder.map { tier ->
      val slDistFrac = tier.stopLossAtrMultiplier?.let {
        val atr = requireNotNull(atrAtEntry) { "entryAtrFactory required for ATR-based SL" }
        (it * atr) / currentPrice
      } ?: tier.stopLossPct!!
      val tpDistFrac = tier.takeProfitAtrMultiplier?.let {
        val atr = requireNotNull(atrAtEntry) { "entryAtrFactory required for ATR-based TP" }
        (it * atr) / currentPrice
      } ?: tier.takeProfitPct!!
      Triple(tier, slDistFrac, tpDistFrac)
    }
    val maxAffordable = tradeHistory.balance / currentPrice
    var totalAmount = if (spec.fixedPositionFraction != null) {
      tradeHistory.balance * spec.fixedPositionFraction / currentPrice
    } else {
      val effectiveRiskPct = resolved.sumOf { (tier, slFrac, _) -> tier.quantityFraction * slFrac }
      if (effectiveRiskPct <= 0) return
      val maxBalanceRisk = tradeHistory.balance * spec.betSize
      maxBalanceRisk / (currentPrice * effectiveRiskPct)
    }
    if (totalAmount > maxAffordable) totalAmount = maxAffordable
    val long = spec.tradeType == LONG
    for ((tier, slFrac, tpFrac) in resolved) {
      val tierAmount = totalAmount * tier.quantityFraction
      if (tierAmount <= 0) continue
      val slPrice = if (long) currentPrice * (1 - slFrac) else currentPrice * (1 + slFrac)
      val tpPrice = if (long) currentPrice * (1 + tpFrac) else currentPrice * (1 - tpFrac)
      tradeHistory += TradeRecord(
        type = spec.tradeType,
        timestamp = closeTime,
        entryPrice = currentPrice,
        amount = tierAmount,
        stopLossPrice = slPrice,
        takeProfitPrice = tpPrice,
        trailingStopPct = tier.trailingStopPct,
        trailingStopDistance = if (long) currentPrice - slPrice else slPrice - currentPrice,
      )
    }
  }
}