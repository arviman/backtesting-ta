package com.pschlup.ta.backtest

class BackTestReport(
  private val spec: BackTestSpec,
  private val trades: List<TradeRecord>,
  private val startPrice: Double,
  private val endPrice: Double,
  val finalBalance: Double,
  val maxDrawDown: Double,
) {
  val tradeCount: Int
    get() = trades.size

  val pyramidingLimit: Int
    get() = spec.pyramidingLimit

  val betSize: Double
    get() = spec.betSize

  val initialBalance: Double
    get() = spec.startingBalance

  /**
   * The Sortino ratio with a risk-free rate of 0%.
   *
   * {@see https://www.investopedia.com/terms/s/sortinoratio.asp}
   */
  val sortinoRatio: Double
    get() = (profitability - RISK_FREE_RATE) / maxDrawDown

  val profitability: Double
    get() = profitLoss / initialBalance

  val profitLoss: Double
    get() = trades.sumOf { it.profitLoss }

  val winRate: Double
    get() = winCount.toDouble() / tradeCount.toDouble()

  private val winCount: Int
    get() = trades.filter { it.isProfitable }.count()

  /** Average return % of winning trades (per unit position). */
  val avgWinPct: Double
    get() {
      val winners = trades.filter { it.isProfitable && it.exitPrice != null }
      if (winners.isEmpty()) return 0.0
      return winners.map { tradeReturnPct(it) }.average()
    }

  /** Average return % of losing trades (negative). */
  val avgLossPct: Double
    get() {
      val losers = trades.filter { !it.isProfitable && it.exitPrice != null }
      if (losers.isEmpty()) return 0.0
      return losers.map { tradeReturnPct(it) }.average()
    }

  /** Expected return per trade in % of position; positive = strategy has edge. */
  val expectedValuePerTrade: Double
    get() = if (tradeCount == 0) 0.0
            else winRate * avgWinPct + (1 - winRate) * avgLossPct

  /** Achieved reward:risk ratio = avgWinPct / |avgLossPct|. */
  val avgRewardRiskRatio: Double
    get() {
      val loss = avgLossPct
      return if (loss < 0) avgWinPct / -loss else Double.NaN
    }

  private fun tradeReturnPct(t: TradeRecord): Double =
    t.profitLoss / (t.entryPrice * t.amount)

  val vsBuyAndHold: Double
    get() = profitability / buyAndHoldProfitability - 1.0

  val buyAndHoldProfitability: Double
    get() = if (spec.tradeType === TradeType.LONG) endPrice / startPrice else startPrice / endPrice

  val riskReward: Double
    get() = trades.sumOf { this.getRiskReward(it) } / tradeCount

  private fun getRiskReward(tradeRecord: TradeRecord): Double {
    val positionSize = tradeRecord.entryPrice * tradeRecord.amount
    val profitLoss = tradeRecord.profitLoss
    val percentageProfitLoss = 1 + profitLoss / (positionSize - Math.abs(profitLoss))
    return percentageProfitLoss / maxDrawDown
  }
}

/**
 * The risk-free rate to use when calculating Sharpe and Sortino ratios.
 *
 * Current personal savings interest ratio is near zero or negative so this value is mostly included for clarity.
 */
private const val RISK_FREE_RATE = 0.0
