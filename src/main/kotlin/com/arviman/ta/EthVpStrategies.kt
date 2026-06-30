package com.arviman.ta

import com.arviman.ta.indicators.VolumeProfile
import com.arviman.ta.timeseries.Bar
import com.arviman.ta.timeseries.TimeFrame
import com.arviman.ta.timeseries.streamCsvBars
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Three volume-profile strategies on ETH 1m → 1h decision cadence.
 *
 * v2 changes (vs v1, all PF < 1.0):
 *   - Decision TF moved 15m → 1h (fewer noisy signals)
 *   - Trend filter: skip fades when |close − 20d daily SMA| > 2.5 × 14d ATR
 *   - S1/S3 extension thresholds widened (S1: 0.5 × VAW, S3: 1.0 × VAW)
 *   - S2 SL widened to 2.5% past the LVN (from 0.5%) to survive whipsaws
 *   - S3 holds multi-day: no UTC midnight force-close (composite reversion
 *     needs days, not hours)
 *
 *   S1. **Fade-to-POC**       — 1h close > yesterday-VAH + 0.5 × VAW → short to yPOC.
 *                               1h close < yesterday-VAL − 0.5 × VAW → long  to yPOC.
 *                               SL: 0.5 × VAW past entry. EOD-closes.
 *
 *   S2. **LVN/HVN breakout**  — when 1h close crosses a composite LVN price,
 *                               enter in cross direction. TP at next composite HVN.
 *                               SL: 2.5% past the LVN. EOD-closes.
 *
 *   S3. **Composite fade**    — like S1 but vs 5-day composite VP and bigger
 *                               threshold (1.0 × VAW). HOLDS THROUGH UTC MIDNIGHT
 *                               until SL/TP fires (multi-day reversion).
 *
 * One position per strategy.
 */
object EthVpStrategies {

  private const val DATA_PATH = "sampledata/ETHUSD_1m_Binance.csv"
  private const val OUT_HTML = ".lavish/eth-vp-strategies.html"

  private const val TICK_SIZE = 1.0
  private const val VALUE_AREA_FRAC = 0.70

  // S1 / S3 knobs (per-strategy extension fraction).
  private const val EXTENSION_FRAC_S1 = 0.50
  private const val EXTENSION_FRAC_S3 = 1.00
  private const val SL_FRAC = 0.50            // SL = 50% of VAW past entry
  // S2 knobs.
  private const val LVN_BUFFER_PCT = 0.025    // 2.5% past LVN
  // S3 holding behaviour.
  private const val S3_HOLD_MULTI_DAY = true
  // S3: rolling composite window.
  private const val COMPOSITE_DAYS = 5

  // Trend filter (S1, S3).
  private const val DAILY_SMA_LEN = 20
  private const val DAILY_ATR_LEN = 14
  private const val TREND_FILTER_ATR_MULT = 2.5

  // Decision-TF aggregation: switch to 1h (= 3600s).
  private const val DECISION_TF_SECONDS = 60 * 60

  private const val MIN_PROFILE_DAYS_FOR_ENTRY = 21   // need 20d SMA warm-up too

  data class Position(
    val side: Int,
    val entry: Double,
    val sl: Double,
    val tp: Double,
    val openDate: LocalDate,
  )

  data class TradeRec(
    val side: Int,
    val entry: Double,
    val exit: Double,
    val pnl: Double,
    val reason: String,
    val date: LocalDate,
  )

  class StrategyState(val name: String) {
    var open: Position? = null
    val trades = mutableListOf<TradeRec>()
    var lastClose: Double = Double.NaN     // S2 needs prev-close for cross detection
  }

  @JvmStatic
  fun main(args: Array<String>) {
    val file = File(DATA_PATH)
    if (!file.exists()) {
      System.err.println("Data file not found: $DATA_PATH")
      return
    }
    println("=== ETH 1m VP strategies ===")
    println("Streaming $DATA_PATH (${"%.1f".format(file.length() / 1024.0 / 1024.0)} MB)...\n")

    val s1 = StrategyState("S1 Fade-to-POC (yest, 1h, trend-filter)")
    val s2 = StrategyState("S2 LVN/HVN breakout (composite, wider SL)")
    val s3 = StrategyState("S3 Composite fade (${COMPOSITE_DAYS}d, multi-day hold)")
    val strategies = listOf(s1, s2, s3)

    // Per-day VP state.
    var todayVp = VolumeProfile(TICK_SIZE)
    var todayHigh = Double.NEGATIVE_INFINITY
    var todayLow = Double.POSITIVE_INFINITY
    var todayClose = Double.NaN
    var currentDay: LocalDate? = null

    // Rolling last N day snapshots (just the histogram data) for composite VP.
    val recentSnapshots = ArrayDeque<List<Pair<Double, Double>>>()

    // Yesterday's metrics (S1).
    var yPoc = Double.NaN; var yVah = Double.NaN; var yVal = Double.NaN
    var yHigh = Double.NaN; var yLow = Double.NaN; var yVaw = Double.NaN

    // Composite metrics (S2, S3).
    var compPoc = Double.NaN; var compVah = Double.NaN; var compVal = Double.NaN
    var compVaw = Double.NaN
    var compHvns: List<Double> = emptyList()   // ascending
    var compLvns: List<Double> = emptyList()   // ascending

    // Daily trend-filter state.
    val dailyCloses = ArrayDeque<Double>()             // last N closes (S1/S3 trend filter)
    val dailyHighs = ArrayDeque<Double>()
    val dailyLows  = ArrayDeque<Double>()
    val dailyPrevCloses = ArrayDeque<Double>()         // prev-close per day for ATR
    var dailySma: Double = Double.NaN
    var dailyAtr: Double = Double.NaN

    var daysSeen = 0

    // 15m aggregation state.
    var cur15m: Bar? = null
    var cur15mKey: Long = Long.MIN_VALUE

    fun process15m(bar15: Bar, date: LocalDate) {
      val close = bar15.close
      val hi = bar15.high
      val lo = bar15.low
      if (daysSeen < MIN_PROFILE_DAYS_FOR_ENTRY) return

      // ── Intrabar exits for every open position (worst-case: SL first).
      for (st in strategies) {
        val p = st.open ?: continue
        // SL
        val hitSL = if (p.side > 0) lo <= p.sl else hi >= p.sl
        if (hitSL) {
          st.trades += TradeRec(p.side, p.entry, p.sl, (p.sl - p.entry) * p.side, "SL", date)
          st.open = null
          continue
        }
        // TP
        val hitTP = if (p.side > 0) hi >= p.tp else lo <= p.tp
        if (hitTP) {
          st.trades += TradeRec(p.side, p.entry, p.tp, (p.tp - p.entry) * p.side, "TP", date)
          st.open = null
        }
      }

      // ── Entries for each strategy that has none open.

      // Trend filter for S1 / S3: skip fade entries when price is too
      // extended vs the 20d daily SMA (more than 2.5× the 14d daily ATR).
      // In a strong trend, fades get steamrolled.
      val trendOk = if (dailySma.isNaN() || dailyAtr.isNaN() || dailyAtr <= 0) false
                    else abs(close - dailySma) <= TREND_FILTER_ATR_MULT * dailyAtr

      // S1: Fade-to-POC vs yesterday (trend-filtered).
      if (s1.open == null && trendOk && !yPoc.isNaN() && !yVaw.isNaN() && yVaw > 0) {
        val ext = EXTENSION_FRAC_S1 * yVaw
        when {
          close > yVah + ext -> {
            val entry = close
            val tp = yPoc
            val sl = entry + SL_FRAC * yVaw
            if (sl > entry && tp < entry)
              s1.open = Position(-1, entry, sl, tp, date)
          }
          close < yVal - ext -> {
            val entry = close
            val tp = yPoc
            val sl = entry - SL_FRAC * yVaw
            if (sl < entry && tp > entry)
              s1.open = Position(+1, entry, sl, tp, date)
          }
        }
      }

      // S2: LVN/HVN breakout. Detect cross of nearest LVN since previous close.
      if (s2.open == null && compLvns.isNotEmpty() && compHvns.isNotEmpty()
          && !s2.lastClose.isNaN()) {
        val prev = s2.lastClose
        // Crossed up through which LVNs?
        val upCrossed = compLvns.firstOrNull { it in prev..close && it != prev }
        val downCrossed = compLvns.lastOrNull { it in close..prev && it != prev }
        if (upCrossed != null) {
          val nextHvn = compHvns.firstOrNull { it > close }
          if (nextHvn != null) {
            val entry = close
            val tp = nextHvn
            val sl = upCrossed * (1 - LVN_BUFFER_PCT)
            if (sl < entry && tp > entry)
              s2.open = Position(+1, entry, sl, tp, date)
          }
        } else if (downCrossed != null) {
          val nextHvn = compHvns.lastOrNull { it < close }
          if (nextHvn != null) {
            val entry = close
            val tp = nextHvn
            val sl = downCrossed * (1 + LVN_BUFFER_PCT)
            if (sl > entry && tp < entry)
              s2.open = Position(-1, entry, sl, tp, date)
          }
        }
      }
      s2.lastClose = close

      // S3: Composite fade — like S1 but vs composite VP, wider threshold,
      // multi-day hold (no EOD close).
      if (s3.open == null && trendOk && !compPoc.isNaN() && !compVaw.isNaN() && compVaw > 0) {
        val ext = EXTENSION_FRAC_S3 * compVaw
        when {
          close > compVah + ext -> {
            val entry = close
            val tp = compPoc
            val sl = entry + SL_FRAC * compVaw
            if (sl > entry && tp < entry)
              s3.open = Position(-1, entry, sl, tp, date)
          }
          close < compVal - ext -> {
            val entry = close
            val tp = compPoc
            val sl = entry - SL_FRAC * compVaw
            if (sl < entry && tp > entry)
              s3.open = Position(+1, entry, sl, tp, date)
          }
        }
      }
    }

    var totalBars = 0L
    val startMs = System.currentTimeMillis()

    for (bar1m in streamCsvBars(DATA_PATH, TimeFrame.M1)) {
      val date = bar1m.openTime.atZone(ZoneOffset.UTC).toLocalDate()

      // ── Day rollover.
      if (currentDay == null || currentDay != date) {
        // Finalize yesterday's VP — produce metrics for today's strategies.
        if (currentDay != null && !todayVp.isEmpty()) {
          val (vlow, vhigh) = todayVp.valueArea(VALUE_AREA_FRAC)
          yPoc = todayVp.poc(); yVah = vhigh; yVal = vlow
          yHigh = todayHigh; yLow = todayLow; yVaw = yVah - yVal

          // Push snapshot into rolling deque.
          recentSnapshots.addLast(todayVp.snapshot())
          while (recentSnapshots.size > COMPOSITE_DAYS) recentSnapshots.removeFirst()

          // Rebuild composite VP from snapshots.
          val composite = VolumeProfile(TICK_SIZE)
          for (snap in recentSnapshots) {
            for ((p, v) in snap) composite.addBar(p, p, v)   // single-bucket add
          }
          compPoc = composite.poc()
          val (cval, cvah) = composite.valueArea(VALUE_AREA_FRAC)
          compVal = cval; compVah = cvah; compVaw = compVah - compVal
          compHvns = composite.highVolumeNodes(window = 3, topN = 12).map { it.first }.sorted()
          compLvns = composite.lowVolumeNodes(window = 3, topN = 12).map { it.first }.sorted()

          // Daily trend-filter state: roll close / high / low.
          val prevClose = dailyCloses.lastOrNull()
          dailyCloses.addLast(todayClose)
          dailyHighs.addLast(todayHigh)
          dailyLows.addLast(todayLow)
          dailyPrevCloses.addLast(prevClose ?: todayClose)
          while (dailyCloses.size > DAILY_SMA_LEN) dailyCloses.removeFirst()
          while (dailyHighs.size > DAILY_ATR_LEN + 1) dailyHighs.removeFirst()
          while (dailyLows.size  > DAILY_ATR_LEN + 1) dailyLows.removeFirst()
          while (dailyPrevCloses.size > DAILY_ATR_LEN + 1) dailyPrevCloses.removeFirst()
          if (dailyCloses.size >= DAILY_SMA_LEN) {
            dailySma = dailyCloses.average()
          }
          if (dailyHighs.size >= DAILY_ATR_LEN + 1) {
            // Drop the oldest (only used to seed prevClose); compute TR over last 14.
            val hs = dailyHighs.toList()
            val ls = dailyLows.toList()
            val pcs = dailyPrevCloses.toList()
            var trSum = 0.0
            for (i in hs.size - DAILY_ATR_LEN until hs.size) {
              val tr = max(hs[i] - ls[i], max(abs(hs[i] - pcs[i]), abs(ls[i] - pcs[i])))
              trSum += tr
            }
            dailyAtr = trSum / DAILY_ATR_LEN
          }

          daysSeen++
        }
        // EOD: force-close S1 and S2 at this bar's open. S3 holds multi-day.
        for (st in strategies) {
          val p = st.open ?: continue
          val isS3 = st === s3
          if (isS3 && S3_HOLD_MULTI_DAY) continue   // keep S3's trade alive
          st.trades += TradeRec(p.side, p.entry, bar1m.open, (bar1m.open - p.entry) * p.side, "EOD", currentDay ?: date)
          st.open = null
        }
        // Reset today.
        todayVp = VolumeProfile(TICK_SIZE)
        todayHigh = Double.NEGATIVE_INFINITY
        todayLow = Double.POSITIVE_INFINITY
        todayClose = Double.NaN
        currentDay = date
        // Flush in-flight 15m aggregator since we're entering a new day.
        cur15m?.let { cur ->
          // Process the final 15m of yesterday (open positions already EOD'd).
          // Skip strategy entries for this bar to avoid same-bar-day-mismatch.
        }
        cur15m = null
        cur15mKey = Long.MIN_VALUE
      }

      // ── Update today's VP + running close.
      todayVp.addBar(bar1m)
      if (bar1m.high > todayHigh) todayHigh = bar1m.high
      if (bar1m.low < todayLow) todayLow = bar1m.low
      todayClose = bar1m.close

      // ── Aggregate 1m → decision TF (default 1h).
      val bucketKey = bar1m.openTime.epochSecond / DECISION_TF_SECONDS
      if (cur15m == null || bucketKey != cur15mKey) {
        // Process the just-finished decision bar (if any).
        cur15m?.let { process15m(it, date) }
        cur15m = Bar(
          timeFrame = TimeFrame.H1,
          openTime = Instant.ofEpochSecond(bucketKey * DECISION_TF_SECONDS),
          open = bar1m.open, high = bar1m.high, low = bar1m.low, close = bar1m.close,
          volume = bar1m.volume,
        )
        cur15mKey = bucketKey
      } else {
        cur15m!! += bar1m
      }

      totalBars++
      if (totalBars % 500_000L == 0L) {
        val es = (System.currentTimeMillis() - startMs) / 1000
        println("  ... ${"%,d".format(totalBars)} bars (${es}s), $daysSeen days, " +
          "S1=${s1.trades.size} S2=${s2.trades.size} S3=${s3.trades.size}")
      }
    }
    // Finalize trailing 15m bar.
    cur15m?.let { process15m(it, currentDay ?: LocalDate.now()) }
    // Final EOD close of any leftover positions.
    val finalClose = cur15m?.close ?: 0.0
    for (st in strategies) {
      val p = st.open ?: continue
      st.trades += TradeRec(p.side, p.entry, finalClose, (finalClose - p.entry) * p.side, "EOD-final", currentDay ?: LocalDate.now())
      st.open = null
    }

    val elapsedS = (System.currentTimeMillis() - startMs) / 1000.0
    println("\nDone in %.1fs.\n".format(elapsedS))

    val reports = strategies.map { reportFor(it) }
    printConsoleReport(reports)
    writeHtml(reports)
    println("\nWrote $OUT_HTML")
  }

  data class StrategyReport(
    val name: String,
    val trades: Int,
    val wins: Int,
    val winRatePct: Double,
    val avgWin: Double,
    val avgLoss: Double,
    val totalPnL: Double,
    val pf: Double,
    val peakEquityDD: Double,
    val worstDay: Double,
    val byYear: Map<Int, Double>,
    val byReason: Map<String, Int>,
    val tradesData: List<TradeRec>,
  )

  private fun reportFor(st: StrategyState): StrategyReport {
    val ts = st.trades
    if (ts.isEmpty()) return StrategyReport(st.name, 0, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, emptyMap(), emptyMap(), emptyList())
    val wins = ts.filter { it.pnl > 0 }
    val losers = ts.filter { it.pnl <= 0 }
    val gw = wins.sumOf { it.pnl }
    val gl = -losers.sumOf { it.pnl }
    val pf = if (gl > 0) gw / gl else Double.POSITIVE_INFINITY
    val total = ts.sumOf { it.pnl }
    val winRate = 100.0 * wins.size / ts.size
    val avgWin = if (wins.isNotEmpty()) wins.map { it.pnl }.average() else 0.0
    val avgLoss = if (losers.isNotEmpty()) losers.map { it.pnl }.average() else 0.0

    // Per-day P&L
    val byDay = ts.groupBy { it.date }.toSortedMap()
      .map { (d, list) -> d to list.sumOf { it.pnl } }
    var bal = 0.0; var peak = 0.0; var maxDD = 0.0; var worstDay = 0.0
    for ((_, p) in byDay) {
      bal += p
      if (bal > peak) peak = bal
      val dd = peak - bal
      if (dd > maxDD) maxDD = dd
      if (p < worstDay) worstDay = p
    }

    val byYear = ts.groupBy { it.date.year }.mapValues { e -> e.value.sumOf { it.pnl } }.toSortedMap()
    val byReason = ts.groupBy { it.reason }.mapValues { it.value.size }

    return StrategyReport(st.name, ts.size, wins.size, winRate, avgWin, avgLoss, total, pf, maxDD, worstDay, byYear, byReason, ts)
  }

  private fun printConsoleReport(reports: List<StrategyReport>) {
    println("%-40s | %6s %6s %9s %9s %6s %14s %12s %12s".format(
      "Strategy", "#t", "win%", "avgWin$", "avgLoss$", "PF", "totalP&L", "peakDD$", "worstDay"))
    println("-".repeat(135))
    for (r in reports) {
      if (r.trades == 0) {
        println("%-40s | (no trades)".format(r.name)); continue
      }
      println("%-40s | %6d %6.1f %9s %9s %6.2f %14s %12s %12s".format(
        r.name, r.trades, r.winRatePct,
        "$%.2f".format(r.avgWin), "$%.2f".format(r.avgLoss), r.pf,
        "$%,.0f".format(r.totalPnL),
        "$%,.0f".format(r.peakEquityDD),
        "$%,.0f".format(r.worstDay)))
    }

    println()
    for (r in reports) {
      if (r.trades == 0) continue
      println("\n=== ${r.name} ===")
      println("  Reasons: ${r.byReason}")
      println("  Year     | P&L ($)        | trades")
      println("  ----------+----------------+--------")
      for (y in r.byYear.keys.sorted()) {
        val pnl = r.byYear[y]!!
        val n = r.tradesData.count { it.date.year == y }
        println("  %-9d | %-14s | %d".format(y, "$%,.0f".format(pnl), n))
      }
    }
  }

  private fun writeHtml(reports: List<StrategyReport>) {
    val sb = StringBuilder()
    sb.append("""<!doctype html><html lang="en" data-theme="synthwave"><head>
<meta charset="UTF-8"><title>ETH VP strategies</title>
<link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/daisyui@5.5.19/daisyui.css">
<link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/daisyui@5.5.19/themes.css">
<script src="https://cdn.jsdelivr.net/npm/@tailwindcss/browser@4.2.4/dist/index.global.js"></script>
</head><body class="min-h-screen bg-base-300">
<div class="navbar bg-base-100 shadow-lg"><div class="navbar-start">
<span class="text-2xl font-bold">📊 ETH VP strategies</span>
<span class="badge badge-soft badge-accent ml-3">1 ETH per trade · 15m decision · UTC-day VP</span></div></div>
<main class="mx-auto max-w-7xl p-6 lg:p-10 space-y-6">

<section class="card bg-base-100 shadow"><div class="card-body">
<h2 class="card-title">Comparison</h2>
<div class="overflow-x-auto"><table class="table table-zebra">
<thead><tr><th>Strategy</th><th class="text-right">Trades</th><th class="text-right">Win %</th>
<th class="text-right">Avg win \$</th><th class="text-right">Avg loss \$</th>
<th class="text-right">PF</th><th class="text-right">Total \$</th>
<th class="text-right">Peak equity DD \$</th><th class="text-right">Worst day \$</th></tr></thead><tbody>""")
    for (r in reports) {
      if (r.trades == 0) { sb.append("<tr><td>${r.name}</td><td colspan=\"8\">(no trades)</td></tr>"); continue }
      val pfCls = if (r.pf >= 1.3) "text-success font-semibold"
                  else if (r.pf >= 1.0) "text-warning" else "text-error"
      val totalCls = if (r.totalPnL > 0) "text-success" else "text-error"
      sb.append("<tr>")
      sb.append("<td>${r.name}</td>")
      sb.append("<td class=\"text-right tabular-nums\">${r.trades}</td>")
      sb.append("<td class=\"text-right tabular-nums\">${"%.1f".format(r.winRatePct)}%</td>")
      sb.append("<td class=\"text-right tabular-nums\">${'$'}${"%.2f".format(r.avgWin)}</td>")
      sb.append("<td class=\"text-right tabular-nums\">${'$'}${"%.2f".format(r.avgLoss)}</td>")
      sb.append("<td class=\"text-right tabular-nums $pfCls\">${"%.2f".format(r.pf)}</td>")
      sb.append("<td class=\"text-right tabular-nums $totalCls\">${'$'}${"%,.0f".format(r.totalPnL)}</td>")
      sb.append("<td class=\"text-right tabular-nums\">${'$'}${"%,.0f".format(r.peakEquityDD)}</td>")
      sb.append("<td class=\"text-right tabular-nums\">${'$'}${"%,.0f".format(r.worstDay)}</td>")
      sb.append("</tr>")
    }
    sb.append("</tbody></table></div></div></section>")

    // Per-strategy yearly breakdown
    for (r in reports) {
      if (r.trades == 0) continue
      sb.append("""<section class="card bg-base-100 shadow"><div class="card-body">
<h2 class="card-title">${r.name}</h2>
<div class="grid md:grid-cols-2 gap-4">
<div><h3 class="font-semibold mb-2">Yearly P&L</h3>
<table class="table table-sm table-zebra"><thead><tr><th>Year</th><th class="text-right">P&L</th><th class="text-right">Trades</th></tr></thead><tbody>""")
      for (y in r.byYear.keys.sorted()) {
        val pnl = r.byYear[y]!!
        val n = r.tradesData.count { it.date.year == y }
        val cls = if (pnl >= 0) "text-success" else "text-error"
        sb.append("<tr><td>$y</td><td class=\"text-right tabular-nums $cls\">${'$'}${"%,.0f".format(pnl)}</td><td class=\"text-right tabular-nums\">$n</td></tr>")
      }
      sb.append("</tbody></table></div>")
      sb.append("""<div><h3 class="font-semibold mb-2">Close reasons</h3>
<table class="table table-sm table-zebra"><thead><tr><th>Reason</th><th class="text-right">#</th></tr></thead><tbody>""")
      for ((reason, cnt) in r.byReason.entries.sortedByDescending { it.value }) {
        sb.append("<tr><td>$reason</td><td class=\"text-right tabular-nums\">$cnt</td></tr>")
      }
      sb.append("</tbody></table></div></div></div></section>")
    }

    sb.append("</main></body></html>")
    File(OUT_HTML).apply { parentFile?.mkdirs(); writeText(sb.toString()) }
  }
}
