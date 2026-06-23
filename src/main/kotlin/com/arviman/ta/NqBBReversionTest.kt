package com.arviman.ta

import com.arviman.ta.timeseries.Bar
import com.arviman.ta.timeseries.readCsvBars
import java.time.ZoneOffset
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Afternoon Bollinger-Band reversion on NQ (Nasdaq-100) 5-minute futures.
 *
 *   - Compute BB(length, mult) over the last N closes (rolling, no
 *     session reset).
 *   - During the AFTERNOON entry window (default 13:00 - 15:30 ET) only:
 *       * Long  if close <= lower band  (oversold → mean revert up)
 *       * Short if close >= upper band  (overbought → mean revert down)
 *   - Exit:
 *       * Take profit at the middle band (mean) — natural reversion exit
 *       * Static SL in points
 *       * Force close at 15:55 ET (last 5-min bar of RTH)
 *
 * Why afternoon only: morning's open-drive momentum often violates the
 * BB without reverting (the impulse is real). After ~13:00 ET institutional
 * flow tapers and standard mean-reversion behavior takes over.
 *
 * 1 contract per trade, max 1 position at a time. NQ point value = $20.
 *
 * Data note: ES_/NQ_ CSVs use "M/d/yyyy H:mm" timestamps stamped at
 * bar-CLOSE, in Eastern Time (volume spike at 9:35 confirms ET cash open).
 */
object NqBBReversionTest {

  private const val DATA_PATH = "sampledata/NQ_5Years_8_11_2024.csv"
  private const val POINT_VALUE = 20.0       // $ per NQ point per contract

  // BB defaults.
  private const val BB_LENGTH = 20
  private const val BB_MULT = 2.0

  // Entry window (minute-of-day in data tz = ET).
  private const val ENTRY_WINDOW_START_MIN = 13 * 60       // 13:00 ET
  private const val ENTRY_WINDOW_END_MIN   = 15 * 60 + 30  // last entry allowed
  private const val SESSION_CLOSE_MIN      = 15 * 60 + 55  // force close

  // Risk (overridden by sweep).
  private const val DEFAULT_SL_PTS = 40.0
  private const val CONTRACTS = 1.0
  private val slSweep = listOf(10.0, 15.0, 20.0, 25.0, 30.0, 40.0, 60.0)

  // Continuation TP sweep (for breakout mode only — reversion uses mid band).
  private val contTpSweep = listOf(20.0, 40.0, 60.0, 100.0, 150.0)

  enum class BBMode { REVERSION, CONTINUATION }

  enum class CloseReason { TP_MID, SL, EOD }

  data class Position(
    val side: Int,
    val entryPrice: Double,
    val slPrice: Double,
    val tpPrice: Double = Double.NaN,    // used only in CONTINUATION mode
  )

  data class Trade(
    val side: Int,
    val entryPrice: Double,
    val exitPrice: Double,
    val pointsPnL: Double,
    val dollarPnL: Double,
    val closeReason: CloseReason,
    val year: Int,
  )

  data class Result(
    val trades: List<Trade>,
    val yearlyPnL: Map<Int, Double>,
    val yearlyMaxDailyLoss: Map<Int, Double>,
    val yearlyFromStartDD: Map<Int, Double>,
    val yearlyStart: Map<Int, Double>,
    val yearlyEnd: Map<Int, Double>,
  )

  @JvmStatic
  fun main(args: Array<String>) {
    println("=== NQ afternoon Bollinger-Band (5-min) ===")
    println("Loading $DATA_PATH ...")
    val bars = readCsvBars(DATA_PATH)
    println("Loaded ${bars.size} bars.")
    println("Config: BB($BB_LENGTH, $BB_MULT), entry window ${fmtTime(ENTRY_WINDOW_START_MIN)}-${fmtTime(ENTRY_WINDOW_END_MIN)} ET, " +
        "force close ${fmtTime(SESSION_CLOSE_MIN)} ET, 1 contract.\n")

    println("=== REVERSION (long lower → mid, short upper → mid) — SL sweep ===")
    println("%-7s | %5s %6s %8s %8s %5s %12s %12s".format(
      "SL pts", "#t", "win%", "avgWin", "avgLoss", "PF", "totalP&L", "5yMaxDD$"))
    println("-".repeat(75))
    for (sl in slSweep) {
      val r = runStrategy(bars, BBMode.REVERSION, sl, tpPts = 0.0)
      printSweepRow("%.0f".format(sl), r)
    }

    println("\n=== CONTINUATION (long upper, short lower) — SL × TP grid ===")
    println("%-12s | %5s %6s %8s %8s %5s %12s %12s".format(
      "SL/TP pts", "#t", "win%", "avgWin", "avgLoss", "PF", "totalP&L", "5yMaxDD$"))
    println("-".repeat(80))
    val contResults = mutableListOf<Triple<Double, Double, Result>>()
    for (sl in slSweep) {
      for (tp in contTpSweep) {
        if (tp < sl) continue   // R:R ≥ 1 only (the whole point of inverted strategy)
        val r = runStrategy(bars, BBMode.CONTINUATION, sl, tp)
        contResults += Triple(sl, tp, r)
        printSweepRow("%.0f / %.0f".format(sl, tp), r)
      }
      println()
    }

    val best = contResults.maxByOrNull { it.third.trades.sumOf { t -> t.dollarPnL } }
    if (best != null) {
      println("=== Best CONTINUATION by total P&L: SL=${best.first} TP=${best.second} ===")
      report(best.third)
    }
  }

  private fun printSweepRow(label: String, r: Result) {
    if (r.trades.isEmpty()) { println("%-12s | (no trades)".format(label)); return }
    val wins = r.trades.filter { it.dollarPnL > 0 }
    val losers = r.trades.filter { it.dollarPnL <= 0 }
    val winRate = 100.0 * wins.size / r.trades.size
    val avgWin = wins.map { it.pointsPnL }.average().takeIf { wins.isNotEmpty() } ?: 0.0
    val avgLoss = losers.map { it.pointsPnL }.average().takeIf { losers.isNotEmpty() } ?: 0.0
    val pf = if (-losers.sumOf { it.dollarPnL } > 0) wins.sumOf { it.dollarPnL } / -losers.sumOf { it.dollarPnL } else Double.POSITIVE_INFINITY
    val total = r.trades.sumOf { it.dollarPnL }
    val maxDD = r.yearlyFromStartDD.values.maxOrNull() ?: 0.0
    println("%-12s | %5d %5.1f%% %7.1fpt %7.1fpt %5.2f %12s %12s".format(
      label, r.trades.size, winRate, avgWin, avgLoss, pf,
      "$%,.0f".format(total), "$%,.0f".format(maxDD)))
  }

  private fun runStrategy(
    bars: List<Bar>,
    mode: BBMode = BBMode.REVERSION,
    slPts: Double = DEFAULT_SL_PTS,
    tpPts: Double = 0.0,    // only used for CONTINUATION (REVERSION exits at mid band)
  ): Result {
    var balance = 0.0
    val trades = mutableListOf<Trade>()
    var open: Position? = null

    // Rolling sum + sum-of-squares for BB. Sliding window of last BB_LENGTH closes.
    val window = ArrayDeque<Double>(BB_LENGTH)
    var sum = 0.0
    var sumSq = 0.0

    val yearlyPnL = sortedMapOf<Int, Double>()
    val yearlyMaxDailyLoss = sortedMapOf<Int, Double>()
    val yearlyFromStartDD = sortedMapOf<Int, Double>()
    val yearlyStart = sortedMapOf<Int, Double>()
    val yearlyEnd = sortedMapOf<Int, Double>()
    val yearStartBalance = sortedMapOf<Int, Double>()

    var currentDay: java.time.LocalDate? = null
    var dayStartBalance = balance

    fun closePos(pos: Position, exitPrice: Double, reason: CloseReason, year: Int) {
      val pts = (exitPrice - pos.entryPrice) * pos.side
      val dollars = pts * POINT_VALUE * CONTRACTS
      balance += dollars
      trades += Trade(pos.side, pos.entryPrice, exitPrice, pts, dollars, reason, year)
      yearlyPnL.merge(year, dollars) { a, b -> a + b }
    }

    for (bar in bars) {
      val zdt = bar.openTime.atZone(ZoneOffset.UTC)
      val year = zdt.year
      val barDate = zdt.toLocalDate()
      val minuteOfDay = zdt.hour * 60 + zdt.minute
      val close = bar.close
      val high = bar.high
      val low = bar.low

      yearStartBalance.getOrPut(year) { balance }
      yearlyStart.getOrPut(year) { balance }
      yearlyEnd[year] = balance

      // Day rollover: reset daily start balance; force-close any leftover.
      if (currentDay == null || currentDay != barDate) {
        open?.let { closePos(it, bar.open, CloseReason.EOD, year) }
        open = null
        currentDay = barDate
        dayStartBalance = balance
      }

      // Update rolling BB window with this close BEFORE checking signals.
      window.addLast(close)
      sum += close
      sumSq += close * close
      if (window.size > BB_LENGTH) {
        val dropped = window.removeFirst()
        sum -= dropped
        sumSq -= dropped * dropped
      }

      // Intrabar SL check on open position.
      open = open?.let { pos ->
        val hitSL = if (pos.side > 0) low <= pos.slPrice else high >= pos.slPrice
        if (hitSL) { closePos(pos, pos.slPrice, CloseReason.SL, year); null } else pos
      }

      val mid = if (window.size > 0) sum / window.size else close
      val variance = if (window.size > 1) (sumSq / window.size - mid * mid).coerceAtLeast(0.0) else 0.0
      val sd = sqrt(variance)
      val upperBand = mid + BB_MULT * sd
      val lowerBand = mid - BB_MULT * sd

      // Reversion: TP at mid band (intrabar). Continuation: TP is a fixed
      // distance set at entry and stored on Position; checked below.
      if (mode == BBMode.REVERSION) {
        open = open?.let { pos ->
          val hitMid = if (pos.side > 0) close >= mid else close <= mid
          if (hitMid) { closePos(pos, mid, CloseReason.TP_MID, year); null } else pos
        }
      } else {
        open = open?.let { pos ->
          val hitTP = if (pos.side > 0) high >= pos.tpPrice else low <= pos.tpPrice
          if (hitTP) { closePos(pos, pos.tpPrice, CloseReason.TP_MID, year); null } else pos
        }
      }

      // Entry: only in afternoon window, only when no position open, only once warmed up.
      if (open == null && window.size == BB_LENGTH
          && minuteOfDay in ENTRY_WINDOW_START_MIN..ENTRY_WINDOW_END_MIN) {
        val direction = when (mode) {
          BBMode.REVERSION -> when {
            close <= lowerBand -> +1   // long the lower band
            close >= upperBand -> -1   // short the upper band
            else -> 0
          }
          BBMode.CONTINUATION -> when {
            close >= upperBand -> +1   // long the upper band (breakout)
            close <= lowerBand -> -1   // short the lower band
            else -> 0
          }
        }
        if (direction != 0) {
          val slPrice = if (direction > 0) close - slPts else close + slPts
          val tpPrice = if (direction > 0) close + tpPts else close - tpPts
          open = Position(direction, close, slPrice, tpPrice)
        }
      }

      // End-of-session force close.
      if (minuteOfDay >= SESSION_CLOSE_MIN) {
        open?.let { closePos(it, close, CloseReason.EOD, year); open = null }
      }

      // Track daily and from-year-start DD.
      val dayLossNow = (dayStartBalance - balance).coerceAtLeast(0.0)
      yearlyMaxDailyLoss.merge(year, dayLossNow) { a, b -> max(a, b) }
      val ys = yearStartBalance[year]!!
      val fromStart = (ys - balance).coerceAtLeast(0.0)
      yearlyFromStartDD.merge(year, fromStart) { a, b -> max(a, b) }
    }

    return Result(trades, yearlyPnL, yearlyMaxDailyLoss, yearlyFromStartDD, yearlyStart, yearlyEnd)
  }

  private fun report(r: Result) {
    if (r.trades.isEmpty()) { println("(no trades)"); return }

    val total = r.trades.sumOf { it.dollarPnL }
    val wins = r.trades.filter { it.dollarPnL > 0 }
    val losers = r.trades.filter { it.dollarPnL <= 0 }
    val winRate = 100.0 * wins.size / r.trades.size
    val avgWin = wins.map { it.pointsPnL }.average().takeIf { wins.isNotEmpty() } ?: 0.0
    val avgLoss = losers.map { it.pointsPnL }.average().takeIf { losers.isNotEmpty() } ?: 0.0
    val pf = if (-losers.sumOf { it.dollarPnL } > 0) wins.sumOf { it.dollarPnL } / -losers.sumOf { it.dollarPnL } else Double.POSITIVE_INFINITY

    println("─── Overall ───")
    println("  Trades: ${r.trades.size}, total \$P&L: ${"$%,.0f".format(total)}")
    println("  Win rate %.1f%% / avgWin %.1f pt / avgLoss %.1f pt / PF %.2f"
      .format(winRate, avgWin, avgLoss, pf))

    val byReason = r.trades.groupBy { it.closeReason }
    println("\n  Close reason breakdown:")
    for (reason in CloseReason.values()) {
      val rs = byReason[reason] ?: continue
      val rSum = rs.sumOf { it.dollarPnL }
      println("    ${reason.name.padEnd(8)} %4d / $%,.0f".format(rs.size, rSum))
    }

    println("\n  Direction breakdown:")
    for (side in listOf(+1, -1)) {
      val ss = r.trades.filter { it.side == side }
      if (ss.isEmpty()) continue
      val sw = ss.count { it.dollarPnL > 0 }
      val sumDollars = ss.sumOf { it.dollarPnL }
      println("    ${if (side > 0) "LONG " else "SHORT"} %4d / win %.1f%% / $%,.0f"
        .format(ss.size, 100.0 * sw / ss.size, sumDollars))
    }

    println("\n  Year   |     P&L $ | FromStartDD$ |  MaxDaily$ | trades")
    println("  " + "-".repeat(58))
    for (y in r.yearlyPnL.keys.sorted()) {
      val pnl = r.yearlyPnL[y] ?: 0.0
      val dd = r.yearlyFromStartDD[y] ?: 0.0
      val mdl = r.yearlyMaxDailyLoss[y] ?: 0.0
      val n = r.trades.count { it.year == y }
      println("  %-6d | %9s | %12s | %10s | %d"
        .format(y, "$%,.0f".format(pnl), "$%,.0f".format(dd), "$%,.0f".format(mdl), n))
    }
  }

  private fun fmtTime(mod: Int) = "%02d:%02d".format(mod / 60, mod % 60)
}
