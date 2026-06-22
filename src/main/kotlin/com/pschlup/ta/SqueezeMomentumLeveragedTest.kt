package com.pschlup.ta

import com.pschlup.ta.strategy.SqueezeParams
import com.pschlup.ta.strategy.SqzEntryMode
import com.pschlup.ta.strategy.SqzExitMode
import com.pschlup.ta.strategy.makeSqueezeMomentumStrategy
import com.pschlup.ta.strategy.SignalType
import com.pschlup.ta.timeseries.Bar
import com.pschlup.ta.timeseries.TimeSeriesManager
import com.pschlup.ta.timeseries.UnstablePeriodException
import com.pschlup.ta.timeseries.readCsvBars
import kotlin.math.max
import kotlin.math.min

/**
 * Squeeze Momentum on ETHUSD H1 — **combined-direction, leveraged**
 * runner that matches the user's cTrader setup:
 *
 *   - One account, longs + shorts share the same balance.
 *   - Pyramid cap = 6 total open positions across both directions.
 *   - Each entry opens at a fixed notional ($13k) regardless of balance,
 *     i.e. margin trading. Pyramid 6 × $13k = $78k peak notional on
 *     $13k → ≈ 6× leverage.
 *   - SL/TP fixed in absolute price points (replicates "49 pips").
 *   - Opposite-direction signal closes existing position, then opens new.
 *
 * Runs OS slice only (last 40% of data).
 */
object SqueezeMomentumLeveragedTest {

  private const val DATA_PATH = "sampledata/ETHUSD_1h_Bitstamp.csv"

  private const val STARTING_BAL = 13_000.0
  private const val NOTIONAL_PER_ENTRY = 13_000.0
  private const val SL_ABS_PTS = 50.0
  private const val PYRAMID = 6
  private const val FEE = 0.0004

  private data class Variant(
    val label: String,
    val entryMode: SqzEntryMode,
    val exitMode: SqzExitMode,
  )

  private val variants = listOf(
    Variant("A zero/zero ", SqzEntryMode.ZeroCross, SqzExitMode.ZeroCross),
    Variant("B zero/color", SqzEntryMode.ZeroCross, SqzExitMode.Color),
    Variant("C color/color", SqzEntryMode.Color, SqzExitMode.Color),
  )

  private val tpRatios = listOf(0.3, 0.5, 0.7, 1.0, 1.5)

  @JvmStatic
  fun main(args: Array<String>) {
    println("Loading bars from $DATA_PATH ...")
    val bars = readCsvBars(DATA_PATH)
    val splitIdx = (bars.size * 0.6).toInt()
    val osr = bars.subList(splitIdx, bars.size)
    println("OS bars: ${osr.size} (~${osr.size / (24 * 365)} years)\n")
    println("Combined-direction leveraged runner. Balance $$STARTING_BAL, " +
        "notional $$NOTIONAL_PER_ENTRY/entry, pyramid $PYRAMID, SL $SL_ABS_PTS pts.")
    println(header())
    println("-".repeat(120))

    val rows = mutableListOf<Pair<String, CombinedReport>>()
    for (v in variants) {
      for (tp in tpRatios) {
        val r = runCombined(osr, v, tp)
        val label = "${v.label} tp=$tp"
        println(row(label, r))
        rows += label to r
      }
      println()
    }

    println("=== Top 5 by NET profit ===")
    rows.sortedByDescending { it.second.profitPct }.take(5)
      .forEach { (l, r) -> println(row(l, r)) }
    println("\n=== Top 5 by PROFIT FACTOR ===")
    rows.filter { it.second.tradeCount > 30 }
      .sortedByDescending { it.second.profitFactor }.take(5)
      .forEach { (l, r) -> println(row(l, r)) }
    println("\n=== Top 5 by PROFIT / DD ===")
    rows.filter { it.second.tradeCount > 30 && it.second.maxDD > 0 }
      .sortedByDescending { it.second.profitPct / it.second.maxDD }.take(5)
      .forEach { (l, r) -> println(row(l, r)) }

    val bnh = osr.last().close / osr.first().close
    println("\nBuy & hold OS: %.2fx (%+.1f%%)".format(bnh, 100.0 * (bnh - 1.0)))
  }

  // ─────────────────────────────────────────────────────────────────────
  //  Combined-direction leveraged runner
  // ─────────────────────────────────────────────────────────────────────

  private data class OpenPos(
    val side: Int,            // +1 long, -1 short
    val coins: Double,
    val entryPrice: Double,
    val slPrice: Double,
    val tpPrice: Double,
  )

  data class CombinedReport(
    val tradeCount: Int,
    val winRate: Double,
    val avgWinPct: Double,
    val avgLossPct: Double,
    val profitFactor: Double,
    val profitPct: Double,
    val maxDD: Double,
    val finalBalance: Double,
  )

  private fun runCombined(bars: List<Bar>, v: Variant, tpRatio: Double): CombinedReport {
    val tsm = TimeSeriesManager(bars[0].timeFrame)
    val longParams = SqueezeParams(entryMode = v.entryMode, exitMode = v.exitMode)
    val shortParams = SqueezeParams(entryMode = v.entryMode, exitMode = v.exitMode)
    val longStrategy = makeSqueezeMomentumStrategy(tsm.h1, longParams, longSide = true)
    val shortStrategy = makeSqueezeMomentumStrategy(tsm.h1, shortParams, longSide = false)
    val tpAbsPts = SL_ABS_PTS * tpRatio

    var balance = STARTING_BAL
    var peakEquity = balance
    var maxDD = 0.0
    val positions = mutableListOf<OpenPos>()
    val pnls = mutableListOf<Double>()

    fun closePos(pos: OpenPos, price: Double) {
      val gross = (price - pos.entryPrice) * pos.coins * pos.side
      val exitFee = price * pos.coins * FEE
      val net = gross - exitFee
      balance += net
      pnls += net
    }

    fun openPos(side: Int, price: Double) {
      val coins = NOTIONAL_PER_ENTRY / price
      val slPrice = if (side > 0) price - SL_ABS_PTS else price + SL_ABS_PTS
      val tpPrice = if (side > 0) price + tpAbsPts else price - tpAbsPts
      val entryFee = price * coins * FEE
      balance -= entryFee
      positions += OpenPos(side, coins, price, slPrice, tpPrice)
    }

    for (bar in bars) {
      val close = bar.close
      val high = bar.high
      val low = bar.low

      // 1) Check intrabar SL/TP hits on open positions.
      val iter = positions.iterator()
      while (iter.hasNext()) {
        val pos = iter.next()
        val hitSL = if (pos.side > 0) low <= pos.slPrice else high >= pos.slPrice
        val hitTP = if (pos.side > 0) high >= pos.tpPrice else low <= pos.tpPrice
        // Worst-case: assume SL first if both touched in the same bar.
        if (hitSL) { closePos(pos, pos.slPrice); iter.remove() }
        else if (hitTP) { closePos(pos, pos.tpPrice); iter.remove() }
      }

      // 2) Mark to market and update drawdown.
      val unrealized = positions.sumOf { (close - it.entryPrice) * it.coins * it.side }
      val equity = balance + unrealized
      peakEquity = max(peakEquity, equity)
      val dd = if (peakEquity > 0) (peakEquity - equity) / peakEquity else 0.0
      if (dd > maxDD) maxDD = dd

      // 3) Feed the timeseries and read signals.
      tsm += bar
      if (tsm.h1.latestBar?.closeTime != bar.closeTime) continue

      val longSig = try { longStrategy[0] } catch (_: UnstablePeriodException) { SignalType.NO_OP }
      val shortSig = try { shortStrategy[0] } catch (_: UnstablePeriodException) { SignalType.NO_OP }

      // 4) Handle exits.
      if (longSig === SignalType.EXIT) {
        positions.filter { it.side > 0 }.forEach { closePos(it, close) }
        positions.removeAll { it.side > 0 }
      }
      if (shortSig === SignalType.EXIT) {
        positions.filter { it.side < 0 }.forEach { closePos(it, close) }
        positions.removeAll { it.side < 0 }
      }

      // 5) Handle entries. Opposite-side signal first closes existing direction.
      if (longSig === SignalType.ENTRY) {
        positions.filter { it.side < 0 }.forEach { closePos(it, close) }
        positions.removeAll { it.side < 0 }
        val sameSide = positions.count { it.side > 0 }
        if (sameSide < PYRAMID && balance > 0) openPos(+1, close)
      }
      if (shortSig === SignalType.ENTRY) {
        positions.filter { it.side > 0 }.forEach { closePos(it, close) }
        positions.removeAll { it.side > 0 }
        val sameSide = positions.count { it.side < 0 }
        if (sameSide < PYRAMID && balance > 0) openPos(-1, close)
      }
    }

    // 6) Force-close anything left at the final close.
    val lastClose = bars.last().close
    positions.forEach { closePos(it, lastClose) }
    positions.clear()

    val wins = pnls.filter { it > 0 }
    val losses = pnls.filter { it <= 0 }
    val winRate = if (pnls.isNotEmpty()) wins.size.toDouble() / pnls.size else 0.0
    val avgWinPct = if (wins.isNotEmpty()) wins.average() / NOTIONAL_PER_ENTRY else 0.0
    val avgLossPct = if (losses.isNotEmpty()) losses.average() / NOTIONAL_PER_ENTRY else 0.0
    val grossWins = wins.sum()
    val grossLosses = -losses.sum()
    val pf = if (grossLosses > 0) grossWins / grossLosses else Double.POSITIVE_INFINITY
    return CombinedReport(
      tradeCount = pnls.size,
      winRate = winRate,
      avgWinPct = avgWinPct,
      avgLossPct = avgLossPct,
      profitFactor = pf,
      profitPct = (balance - STARTING_BAL) / STARTING_BAL,
      maxDD = maxDD,
      finalBalance = balance,
    )
  }

  // ─────────────────────────────────────────────────────────────────────
  //  Formatting
  // ─────────────────────────────────────────────────────────────────────

  private fun header(): String =
    "%-22s | %5s %7s %7s %7s %8s %7s %5s".format(
      "variant", "#t", "win%", "avgWin%", "avgLoss%", "profit%", "DD%", "PF",
    )

  private fun row(label: String, r: CombinedReport): String =
    "%-22s | %5d %7s %7s %7s %8s %7s %5s".format(
      label,
      r.tradeCount,
      pct(r.winRate),
      pct(r.avgWinPct),
      pct(r.avgLossPct),
      pctSigned(r.profitPct),
      pct(r.maxDD),
      if (r.profitFactor.isFinite()) "%.2f".format(r.profitFactor) else "—",
    )

  private fun pct(v: Double): String = if (v.isFinite()) "%.1f%%".format(100.0 * v) else "—"
  private fun pctSigned(v: Double): String = if (v.isFinite()) "%+.1f%%".format(100.0 * v) else "—"
}
