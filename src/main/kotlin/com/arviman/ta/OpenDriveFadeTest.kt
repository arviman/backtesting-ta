package com.arviman.ta

import com.arviman.ta.timeseries.Bar
import com.arviman.ta.timeseries.readCsvBars
import java.time.ZoneOffset
import kotlin.math.max

/**
 * Open-drive continuation + afternoon fade on US index futures (ES, NQ),
 * 5-minute bars. Tests the classic intraday pattern:
 *
 *   Morning leg (continuation): at 10:05 ET (30 min after RTH cash open),
 *     check whether the move from open is > threshold. Enter in that
 *     direction with static SL/TP. Force-close at 12:00 ET if still open.
 *
 *   Afternoon leg (fade): at 14:00 ET, check the day's range so far.
 *     Fade the dominant direction (long if day-down, short if day-up).
 *     Force-close at 15:55 ET.
 *
 * Data timestamps are bar-CLOSE in Eastern Time. The 9:35 bar represents
 * the 9:30-9:35 ET cash-open candle (which is why volume spikes there).
 *
 * Each entry is 1 contract:
 *   ES: 1 contract = $50 per point of move
 *   NQ: 1 contract = $20 per point of move
 *
 * One leg-position at a time (no pyramiding). Both legs can be open
 * simultaneously across a single day in theory but in practice the
 * morning leg is usually closed before the fade fires at 14:00.
 */
object OpenDriveFadeTest {

  // ── Time constants (minutes-of-day in data's local TZ, assumed ET) ──
  private const val RTH_OPEN_MIN = 9 * 60 + 35           // first RTH bar (close-stamped)
  private const val CONT_ENTRY_MIN = 10 * 60 + 5         // 30 min after open
  private const val CONT_EXIT_MIN = 12 * 60              // noon cutoff
  private const val FADE_ENTRY_MIN = 14 * 60             // 2 pm
  private const val SESSION_CLOSE_MIN = 15 * 60 + 55     // last 5-min RTH bar

  data class AssetConfig(
    val symbol: String,
    val path: String,
    val pointValue: Double,        // $ per point of price move per contract
    val contThresholdPts: Double,  // min move from open to qualify for continuation
    val fadeThresholdPts: Double,  // min day-range to qualify for fade
    val slPts: Double,             // static SL in points
    val tpPts: Double,             // static TP in points
    val tickValue: Double,         // smallest price increment
  )

  private val assets = listOf(
    AssetConfig(
      symbol = "ES (S&P 500)",
      path = "sampledata/ES_5Years_8_11_2024.csv",
      pointValue = 50.0,
      contThresholdPts = 3.0,
      fadeThresholdPts = 10.0,
      slPts = 8.0,
      tpPts = 12.0,
      tickValue = 0.25,
    ),
    AssetConfig(
      symbol = "NQ (Nasdaq)",
      path = "sampledata/NQ_5Years_8_11_2024.csv",
      pointValue = 20.0,
      contThresholdPts = 15.0,
      fadeThresholdPts = 40.0,
      slPts = 40.0,
      tpPts = 60.0,
      tickValue = 0.25,
    ),
  )

  enum class LegType { CONTINUATION, FADE }
  enum class CloseReason { TP, SL, TIMEOUT, EOD }

  data class Position(
    val leg: LegType,
    val side: Int,        // +1 long, -1 short
    val entryPrice: Double,
    val slPrice: Double,
    val tpPrice: Double,
    val entryMinOfDay: Int,
  )

  data class Trade(
    val leg: LegType,
    val side: Int,
    val entryPrice: Double,
    val exitPrice: Double,
    val pointsPnL: Double,
    val dollarPnL: Double,
    val closeReason: CloseReason,
    val year: Int,
  )

  @JvmStatic
  fun main(args: Array<String>) {
    println("=== Open-drive continuation + afternoon fade — US index futures (5-min) ===\n")
    for (asset in assets) {
      println("Loading ${asset.symbol} from ${asset.path} ...")
      val bars = readCsvBars(asset.path)
      println("Loaded ${bars.size} bars.")
      val result = runStrategy(asset, bars)
      report(asset, result)
      println()
    }
  }

  data class Result(
    val trades: List<Trade>,
    val yearlyPnL: Map<Int, Double>,
    val yearlyMaxDailyLoss: Map<Int, Double>,
    val yearlyFromStartDD: Map<Int, Double>,
    val yearlyStart: Map<Int, Double>,
    val yearlyEnd: Map<Int, Double>,
    val yearlyMaxEq: Map<Int, Double>,
    val yearlyMinEq: Map<Int, Double>,
  )

  private fun runStrategy(asset: AssetConfig, bars: List<Bar>): Result {
    var balance = 0.0
    val trades = mutableListOf<Trade>()
    var openCont: Position? = null
    var openFade: Position? = null

    // Per-day state (resets at RTH open).
    var rthOpen: Double? = null
    var dayHigh = Double.NEGATIVE_INFINITY
    var dayLow = Double.POSITIVE_INFINITY

    val yearlyPnL = sortedMapOf<Int, Double>()
    val yearlyMaxDailyLoss = sortedMapOf<Int, Double>()
    val yearlyFromStartDD = sortedMapOf<Int, Double>()
    val yearlyStart = sortedMapOf<Int, Double>()
    val yearlyEnd = sortedMapOf<Int, Double>()
    val yearlyMaxEq = sortedMapOf<Int, Double>()
    val yearlyMinEq = sortedMapOf<Int, Double>()

    // Daily P&L tracking (for daily-loss rule).
    var dayStartBalance = balance
    var currentDay: java.time.LocalDate? = null
    var yearStartBalance = sortedMapOf<Int, Double>()

    fun closePos(pos: Position, exitPrice: Double, reason: CloseReason, year: Int) {
      val pointsPnL = (exitPrice - pos.entryPrice) * pos.side
      val dollarPnL = pointsPnL * asset.pointValue
      balance += dollarPnL
      trades += Trade(pos.leg, pos.side, pos.entryPrice, exitPrice, pointsPnL, dollarPnL, reason, year)
      yearlyPnL.merge(year, dollarPnL) { a, b -> a + b }
    }

    for (bar in bars) {
      val zdt = bar.openTime.atZone(ZoneOffset.UTC)
      val year = zdt.year
      val barDate = zdt.toLocalDate()
      val minuteOfDay = zdt.hour * 60 + zdt.minute

      // Year-start equity (for prop-style from-start DD).
      yearStartBalance.getOrPut(year) { balance }
      yearlyStart.getOrPut(year) { balance }
      yearlyEnd[year] = balance

      // Day rollover: reset RTH state, day-open balance, recompute daily DD.
      if (currentDay == null || currentDay != barDate) {
        // Close any leftover positions from prior session.
        openCont?.let { closePos(it, bar.open, CloseReason.EOD, year); openCont = null }
        openFade?.let { closePos(it, bar.open, CloseReason.EOD, year); openFade = null }
        currentDay = barDate
        dayStartBalance = balance
        rthOpen = null
        dayHigh = Double.NEGATIVE_INFINITY
        dayLow = Double.POSITIVE_INFINITY
      }

      val high = bar.high
      val low = bar.low
      val close = bar.close

      // Update RTH day high/low while inside RTH session.
      if (rthOpen != null && minuteOfDay in RTH_OPEN_MIN..SESSION_CLOSE_MIN) {
        dayHigh = max(dayHigh, high)
        dayLow = kotlin.math.min(dayLow, low)
      }

      // Intrabar SL/TP check for open positions.
      openCont = openCont?.let { pos ->
        val hitSL = if (pos.side > 0) low <= pos.slPrice else high >= pos.slPrice
        val hitTP = if (pos.side > 0) high >= pos.tpPrice else low <= pos.tpPrice
        when {
          hitSL -> { closePos(pos, pos.slPrice, CloseReason.SL, year); null }
          hitTP -> { closePos(pos, pos.tpPrice, CloseReason.TP, year); null }
          else -> pos
        }
      }
      openFade = openFade?.let { pos ->
        val hitSL = if (pos.side > 0) low <= pos.slPrice else high >= pos.slPrice
        val hitTP = if (pos.side > 0) high >= pos.tpPrice else low <= pos.tpPrice
        when {
          hitSL -> { closePos(pos, pos.slPrice, CloseReason.SL, year); null }
          hitTP -> { closePos(pos, pos.tpPrice, CloseReason.TP, year); null }
          else -> pos
        }
      }

      // Record RTH open price (first bar at 9:35 ET — the cash-open candle).
      if (minuteOfDay == RTH_OPEN_MIN && rthOpen == null) {
        rthOpen = bar.open
        dayHigh = high
        dayLow = low
      }

      // Continuation entry at 10:05 (30 min after open).
      val openPrice = rthOpen
      if (minuteOfDay == CONT_ENTRY_MIN && openCont == null && openPrice != null) {
        val moveFromOpen = close - openPrice
        val direction = when {
          moveFromOpen > asset.contThresholdPts -> +1
          moveFromOpen < -asset.contThresholdPts -> -1
          else -> 0
        }
        if (direction != 0) {
          val slPrice = if (direction > 0) close - asset.slPts else close + asset.slPts
          val tpPrice = if (direction > 0) close + asset.tpPts else close - asset.tpPts
          openCont = Position(LegType.CONTINUATION, direction, close, slPrice, tpPrice, minuteOfDay)
        }
      }

      // Continuation force-close at noon.
      if (minuteOfDay >= CONT_EXIT_MIN) {
        openCont?.let { closePos(it, close, CloseReason.TIMEOUT, year); openCont = null }
      }

      // Fade entry at 14:00.
      if (minuteOfDay == FADE_ENTRY_MIN && openFade == null && openPrice != null) {
        val rangeUp = dayHigh - openPrice
        val rangeDown = openPrice - dayLow
        val direction = when {
          rangeUp > rangeDown && rangeUp > asset.fadeThresholdPts -> -1  // fade up
          rangeDown > rangeUp && rangeDown > asset.fadeThresholdPts -> +1  // fade down
          else -> 0
        }
        if (direction != 0) {
          val slPrice = if (direction > 0) close - asset.slPts else close + asset.slPts
          val tpPrice = if (direction > 0) close + asset.tpPts else close - asset.tpPts
          openFade = Position(LegType.FADE, direction, close, slPrice, tpPrice, minuteOfDay)
        }
      }

      // End-of-session force-close at 15:55.
      if (minuteOfDay >= SESSION_CLOSE_MIN) {
        openCont?.let { closePos(it, close, CloseReason.EOD, year); openCont = null }
        openFade?.let { closePos(it, close, CloseReason.EOD, year); openFade = null }
      }

      // Track per-day max loss (running floating + realized loss vs day-open balance).
      val dayLossNow = (dayStartBalance - balance).coerceAtLeast(0.0)
      yearlyMaxDailyLoss.merge(year, dayLossNow) { a, b -> max(a, b) }

      // Track from-year-start DD (max equity loss vs year-open).
      val ys = yearStartBalance[year]!!
      val fromStart = (ys - balance).coerceAtLeast(0.0)
      yearlyFromStartDD.merge(year, fromStart) { a, b -> max(a, b) }

      // Track running max/min equity per year.
      yearlyMaxEq.merge(year, balance) { a, b -> max(a, b) }
      yearlyMinEq.merge(year, balance) { a, b -> kotlin.math.min(a, b) }
    }

    return Result(
      trades = trades,
      yearlyPnL = yearlyPnL,
      yearlyMaxDailyLoss = yearlyMaxDailyLoss,
      yearlyFromStartDD = yearlyFromStartDD,
      yearlyStart = yearlyStart,
      yearlyEnd = yearlyEnd,
      yearlyMaxEq = yearlyMaxEq,
      yearlyMinEq = yearlyMinEq,
    )
  }

  private fun report(asset: AssetConfig, r: Result) {
    val totalDollar = r.trades.sumOf { it.dollarPnL }
    val byLeg = r.trades.groupBy { it.leg }
    println("─── ${asset.symbol} ───")
    println("  Total trades: ${r.trades.size}, total \$P&L: ${"$%,.0f".format(totalDollar)}")

    for (leg in LegType.values()) {
      val ls = byLeg[leg] ?: continue
      val wins = ls.filter { it.dollarPnL > 0 }
      val winRate = if (ls.isNotEmpty()) 100.0 * wins.size / ls.size else 0.0
      val avgWinPts = wins.map { it.pointsPnL }.average().takeIf { wins.isNotEmpty() } ?: 0.0
      val losers = ls.filter { it.dollarPnL <= 0 }
      val avgLossPts = losers.map { it.pointsPnL }.average().takeIf { losers.isNotEmpty() } ?: 0.0
      val grossWin = wins.sumOf { it.dollarPnL }
      val grossLoss = -losers.sumOf { it.dollarPnL }
      val pf = if (grossLoss > 0) grossWin / grossLoss else Double.POSITIVE_INFINITY
      val total = ls.sumOf { it.dollarPnL }
      println("  ${leg.name.padEnd(13)} %-4d trades / win %.1f%% / avgWin %.1f pt / avgLoss %.1f pt / PF %.2f / total $%,.0f".format(
        ls.size, winRate, avgWinPts, avgLossPts, pf, total))
    }

    println("\n  ${"Year".padEnd(6)} | ${"P&L $".padStart(10)} | ${"FromStartDD$".padStart(13)} | ${"MaxDaily$".padStart(10)} | trades")
    println("  " + "-".repeat(60))
    val years = r.yearlyPnL.keys.sorted()
    for (y in years) {
      val pnl = r.yearlyPnL[y] ?: 0.0
      val dd = r.yearlyFromStartDD[y] ?: 0.0
      val mdl = r.yearlyMaxDailyLoss[y] ?: 0.0
      val tradesInYear = r.trades.count { it.year == y }
      println("  %-6d | %10s | %13s | %10s | %d".format(
        y, "$%,.0f".format(pnl), "$%,.0f".format(dd), "$%,.0f".format(mdl), tradesInYear))
    }
  }
}
