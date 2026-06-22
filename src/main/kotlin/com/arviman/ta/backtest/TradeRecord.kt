package com.arviman.ta.backtest

import com.arviman.ta.backtest.TradeType.LONG
import com.arviman.ta.backtest.TradeType.SHORT
import java.time.Instant

enum class CloseReason {
  EXIT, STOP_LOSS, TAKE_PROFIT
}

/** A simulated trade record. */
data class TradeRecord(
  val type: TradeType = LONG,
  val timestamp: Instant? = null,
  val entryPrice: Double = 0.0,
  val amount: Double = 0.0,
  val trailingStopDistance: Double = 0.0,
  /** Optional % trail (e.g. 0.05 = 5%). Re-evaluated each bar from current price. */
  val trailingStopPct: Double? = null,
  var stopLossPrice: Double = 0.0,
  var takeProfitPrice: Double? = null,
  var closeReason: CloseReason? = null,
  var exitPrice: Double? = null,
) {

  val isProfitable: Boolean
    get() = requireNotNull(exitPrice).let { exitPrice ->
      when (type) {
        LONG -> exitPrice > entryPrice
        SHORT -> exitPrice < entryPrice
      }
    }

  val profitLoss: Double
    get() = requireNotNull(exitPrice).let { exitPrice ->
      when (type) {
        LONG -> amount * (exitPrice - entryPrice)
        SHORT -> amount * (entryPrice - exitPrice)
      }
    }

  fun getUnrealizedProfitLoss(currentPrice: Double): Double =
    when (type) {
      LONG -> amount * (currentPrice - entryPrice)
      SHORT -> amount * (entryPrice - currentPrice)
    }

  fun updateTrailingStop(currentPrice: Double) {
    when (type) {
      LONG -> {
        if (currentPrice - trailingStopDistance > stopLossPrice) {
          stopLossPrice = currentPrice - trailingStopDistance
        }
      }
      SHORT -> {
        if (currentPrice + trailingStopDistance < stopLossPrice) {
          stopLossPrice = currentPrice + trailingStopDistance
        }
      }
    }
  }

  /** Re-evaluates a percentage trail against the current price (ratchets only). */
  fun updateTrailingPctStop(currentPrice: Double) {
    val pct = trailingStopPct ?: return
    when (type) {
      LONG -> {
        val candidate = currentPrice * (1.0 - pct)
        if (candidate > stopLossPrice) stopLossPrice = candidate
      }
      SHORT -> {
        val candidate = currentPrice * (1.0 + pct)
        if (candidate < stopLossPrice) stopLossPrice = candidate
      }
    }
  }

  fun shouldStop(currentPrice: Double): Boolean {
    require(isOpen)
    return when (type) {
      LONG -> currentPrice < stopLossPrice
      SHORT -> currentPrice > stopLossPrice
    }
  }

  fun shouldTakeProfit(currentPrice: Double): Boolean {
    require(isOpen)
    val tp = takeProfitPrice ?: return false
    return when (type) {
      LONG -> currentPrice >= tp
      SHORT -> currentPrice <= tp
    }
  }

  fun exit(exitPrice: Double) {
    require(isOpen)
    closeReason = CloseReason.EXIT
    this.exitPrice = exitPrice
  }

  fun stop(exitPrice: Double) {
    require(isOpen)
    closeReason = CloseReason.STOP_LOSS
    this.exitPrice = exitPrice
  }

  fun takeProfit(exitPrice: Double) {
    require(isOpen)
    closeReason = CloseReason.TAKE_PROFIT
    this.exitPrice = exitPrice
  }

  val isOpen: Boolean
    get() = closeReason == null
}
