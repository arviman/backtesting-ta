package com.arviman.ta

import com.arviman.ta.backtest.BackTestReport
import com.arviman.ta.backtest.BackTestSpec
import com.arviman.ta.backtest.BackTester
import com.arviman.ta.backtest.TpSlTier
import com.arviman.ta.backtest.TradeType
import com.arviman.ta.indicators.averageTrueRange
import com.arviman.ta.indicators.volatilityStop
import com.arviman.ta.strategy.makeBollingerMeanReversionStrategy
import com.arviman.ta.timeseries.Bar
import com.arviman.ta.timeseries.TimeFrame
import com.arviman.ta.timeseries.readCsvBars

/**
 * Tests Bollinger mean reversion on BTC at multiple timeframes. Uses ATR-based
 * tight stop (1.5×ATR) — prop-firm-style risk management. Soft exit at the
 * middle band closes winners.
 */
object BollingerMeanReversionTest {

  private data class Variant(
    val label: String,
    val tf: TimeFrame,
    val useRsiFilter: Boolean,
  )

  private val BTC_M5 = "sampledata/chart_data_BTC_USDT_p5_730d.csv"

  private val variants = listOf(
    Variant("BTC H1 BB+RSI", TimeFrame.H1, true),
    Variant("BTC H1 BB only", TimeFrame.H1, false),
    Variant("BTC H4 BB+RSI", TimeFrame.H4, true),
    Variant("BTC H4 BB only", TimeFrame.H4, false),
    Variant("BTC D  BB+RSI", TimeFrame.D, true),
  )

  // 1.5×ATR stop, 99×ATR "TP" that never fires — soft exit (middle band) is
  // the real way out of winners. Matches prop-firm tight-risk shape.
  private val ladder = listOf(
    TpSlTier(
      quantityFraction = 1.0,
      stopLossAtrMultiplier = 1.5,
      takeProfitAtrMultiplier = 99.0,
    )
  )

  @JvmStatic
  fun main(args: Array<String>) {
    val bars = readCsvBars(BTC_M5)
    val splitIdx = (bars.size * 0.6).toInt()
    val ins = bars.subList(0, splitIdx)
    val osr = bars.subList(splitIdx, bars.size)
    println("Bars: ${bars.size} → IS ${ins.size} | OS ${osr.size}  (BTC M5 file, downsampled)")
    println()
    println(header())
    println("-".repeat(140))
    for (variant in variants) {
      val rIs = runOne(ins, variant)
      val rOs = runOne(osr, variant)
      println(row(variant.label, rIs, rOs))
    }
    val bnhIs = ins.last().close / ins.first().close
    val bnhOs = osr.last().close / osr.first().close
    println()
    println("BnH ref: IS %.2fx (%.1f%%) | OS %.2fx (%.1f%%) | Total %.2fx".format(
      bnhIs, 100.0 * (bnhIs - 1.0), bnhOs, 100.0 * (bnhOs - 1.0), bnhIs * bnhOs,
    ))
  }

  private fun runOne(bars: List<Bar>, variant: Variant): BackTestReport {
    val spec = BackTestSpec(
      tradeType = TradeType.LONG,
      runTimeFrame = variant.tf,
      trailingStops = false,
      pyramidingLimit = ladder.size,
      startingBalance = 5_000.0,
      betSize = 0.02,
      feePerTrade = 0.0005,
      inputBars = bars,
      strategyFactory = { sm ->
        makeBollingerMeanReversionStrategy(
          series = sm.getTimeSeries(variant.tf),
          useRsiFilter = variant.useRsiFilter,
        )
      },
      stopLoss = { sm -> sm.getTimeSeries(variant.tf).volatilityStop(length = 14, multiplier = 2.0) },
      entryLadder = ladder,
      entryAtrFactory = { sm -> sm.getTimeSeries(variant.tf).averageTrueRange(14) },
      fixedPositionFraction = 1.0,
    )
    return BackTester.run(spec)
  }

  private fun header(): String =
    "%-18s | %4s %6s %7s %7s %6s | %4s %6s %7s %7s %6s".format(
      "variant",
      "IS#t", "IS w%", "IS E[R]", "IS prf", "IS DD",
      "OS#t", "OS w%", "OS E[R]", "OS prf", "OS DD",
    )

  private fun row(label: String, ins: BackTestReport, os: BackTestReport): String =
    "%-18s | %4d %6s %7s %7s %6s | %4d %6s %7s %7s %6s".format(
      label,
      ins.tradeCount, pct(ins.winRate), pct(ins.expectedValuePerTrade), pct(ins.profitability), pct(ins.maxDrawDown),
      os.tradeCount, pct(os.winRate), pct(os.expectedValuePerTrade), pct(os.profitability), pct(os.maxDrawDown),
    )

  private fun pct(v: Double): String = if (v.isFinite()) "%.1f%%".format(100.0 * v) else "—"
}
