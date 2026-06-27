package com.arviman.ta

import com.arviman.ta.timeseries.Bar
import com.arviman.ta.timeseries.TimeFrame
import com.arviman.ta.timeseries.readCsvBars
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.max

/**
 * Rumers: previous-day-range fade on NQ 15-min.
 *
 *   - Levels (recomputed at each new RTH day):
 *       RH, RL = previous RTH day's high, low.
 *       SH = highest high over the last lookback RTH days BEFORE yesterday,
 *            only if SH > RH. Else no shorts today.
 *       SL = lowest low over the same window, only if SL < RL.
 *            Else no longs today.
 *
 *   - Knobs (Config):
 *       lookback   N RTH days before yesterday for SH/SL.
 *       bufferPts  Stop buffer above SH (shorts) / below SL (longs).
 *       minRR      Worst-case R:R floor at entry (1.0 = mid-zone).
 *       tpFrac     Take-profit fraction of yesterday's range.
 *                  1.0 = opposite edge (RL/RH). 0.5 = mid of (RH, RL).
 *       skipBand   Trend-day filter: skip shorts if prev close in top
 *                  `skipBand` of yesterday's range, longs if in bottom.
 *                  0.0 = no filter.
 *
 *   - Intraday (RTH, 15m bar closes):
 *       SHORT: stop = SH + bufferPts, tp = RH - tpFrac*(RH-RL).
 *              Entry allowed when close in [entryFloor, SH] where
 *              entryFloor = (minRR*stop + tp)/(1+minRR).
 *       LONG : symmetric.
 *
 *   - Max 1 position open. Max 1 entry per RTH day.
 *   - EOD force close at 16:00 ET.
 *
 * Data: 5-min NQ CSV stamped at bar-close in ET, aggregated to 15m on the
 * fly. NQ point value = $20.
 */
object RumersTest {

  private const val DATA_PATH = "sampledata/NQ_5Years_8_11_2024.csv"
  private const val OUT_HTML = ".lavish/rumers.html"
  private const val POINT_VALUE = 20.0
  private const val CONTRACTS = 1.0

  // A 15m bar timestamped 9:45 covers 9:30-9:45 → first RTH bar.
  // 16:00 bar covers 15:45-16:00 → last RTH bar.
  private const val RTH_FIRST_BAR_MIN = 9 * 60 + 45     // 09:45
  private const val RTH_LAST_BAR_MIN  = 16 * 60         // 16:00
  private const val LAST_ENTRY_MIN    = 15 * 60 + 45    // 15:45
  private const val FORCE_CLOSE_MIN   = 16 * 60         // 16:00

  data class Config(
    val lookback: Int = 15,
    val bufferPts: Double = 3.0,
    val minRR: Double = 1.0,
    val tpFrac: Double = 1.0,
    val skipBand: Double = 0.0,
  )

  private val BASELINE = Config()

  enum class CloseReason { TP, SL, EOD }

  data class Position(
    val side: Int,           // +1 long, -1 short
    val entryPrice: Double,
    val slPrice: Double,
    val tpPrice: Double,
  )

  data class Trade(
    val side: Int,
    val entry: Double,
    val exit: Double,
    val pointsPnL: Double,
    val dollarPnL: Double,
    val reason: CloseReason,
    val year: Int,
    val date: LocalDate,
  )

  data class Result(
    val trades: List<Trade>,
    val yearlyPnL: Map<Int, Double>,
    val yearlyFromStartDD: Map<Int, Double>,
  ) {
    val total: Double get() = trades.sumOf { it.dollarPnL }
    val maxYearlyDD: Double get() = yearlyFromStartDD.values.maxOrNull() ?: 0.0
    val winRate: Double
      get() = if (trades.isEmpty()) 0.0
              else 100.0 * trades.count { it.dollarPnL > 0 } / trades.size
    val profitFactor: Double
      get() {
        val gw = trades.filter { it.dollarPnL > 0 }.sumOf { it.dollarPnL }
        val gl = -trades.filter { it.dollarPnL <= 0 }.sumOf { it.dollarPnL }
        return if (gl > 0) gw / gl else Double.POSITIVE_INFINITY
      }
    val avgWinPts: Double
      get() = trades.filter { it.dollarPnL > 0 }.let { w ->
        if (w.isEmpty()) 0.0 else w.map { it.pointsPnL }.average()
      }
    val avgLossPts: Double
      get() = trades.filter { it.dollarPnL <= 0 }.let { l ->
        if (l.isEmpty()) 0.0 else l.map { it.pointsPnL }.average()
      }
    val reasonCounts: Map<CloseReason, Int>
      get() = CloseReason.values().associateWith { r -> trades.count { it.reason == r } }
  }

  data class SweepRow(val cfg: Config, val r: Result)

  @JvmStatic
  fun main(args: Array<String>) {
    println("=== Rumers fade on NQ 15m ===")
    println("Loading $DATA_PATH ...")
    val bars5m = readCsvBars(DATA_PATH)
    println("Loaded ${bars5m.size} 5m bars.")

    val rthHL = buildRthDailyHL(bars5m)
    val bars15m = aggregateTo15m(bars5m)
    println("Built ${bars15m.size} 15m bars, ${rthHL.size} RTH days.\n")

    val baseline = runStrategy(bars15m, rthHL, BASELINE)
    printBaselineReport(baseline)

    val sweeps = LinkedHashMap<String, List<SweepRow>>()

    // ── A. Lookback × buffer (B/C/D at baseline)
    println("\n=== A. Lookback × buffer (minRR=1.0, tpFrac=1.0, skipBand=0) ===")
    val swA = mutableListOf<SweepRow>()
    val lookbackGrid = listOf(15, 30, 45, 60, 90)
    val bufferGrid   = listOf(3.0, 5.0, 10.0)
    printSweepHeader("lookbk", "buf pt")
    for (lb in lookbackGrid) {
      for (buf in bufferGrid) {
        val c = BASELINE.copy(lookback = lb, bufferPts = buf)
        val r = runStrategy(bars15m, rthHL, c)
        swA += SweepRow(c, r)
        printSweepRow("$lb", "%.0f".format(buf), r)
      }
      println()
    }
    sweeps["A. lookback × buffer"] = swA

    // ── B. R:R floor × lookback (buf/tp/skip at baseline)
    println("=== B. R:R floor × lookback (buffer=3, tpFrac=1.0, skipBand=0) ===")
    val swB = mutableListOf<SweepRow>()
    val rrGrid = listOf(0.3, 0.5, 0.7, 1.0, 1.5)
    printSweepHeader("minRR", "lookbk")
    for (rr in rrGrid) {
      for (lb in lookbackGrid) {
        val c = BASELINE.copy(minRR = rr, lookback = lb)
        val r = runStrategy(bars15m, rthHL, c)
        swB += SweepRow(c, r)
        printSweepRow("%.1f".format(rr), "$lb", r)
      }
      println()
    }
    sweeps["B. minRR × lookback"] = swB

    // ── C. TP fraction × R:R floor (lookback=30 default, buf/skip at baseline)
    println("=== C. tpFrac × minRR (lookback=30, buffer=3, skipBand=0) ===")
    val swC = mutableListOf<SweepRow>()
    val tpGrid = listOf(0.25, 0.5, 0.75, 1.0)
    printSweepHeader("tpFrac", "minRR")
    for (tp in tpGrid) {
      for (rr in rrGrid) {
        val c = BASELINE.copy(tpFrac = tp, minRR = rr, lookback = 30)
        val r = runStrategy(bars15m, rthHL, c)
        swC += SweepRow(c, r)
        printSweepRow("%.2f".format(tp), "%.1f".format(rr), r)
      }
      println()
    }
    sweeps["C. tpFrac × minRR (lookback=30)"] = swC

    // ── D. Trend-day filter × lookback (others at baseline)
    println("=== D. skipBand × lookback (buffer=3, minRR=1.0, tpFrac=1.0) ===")
    val swD = mutableListOf<SweepRow>()
    val skipGrid = listOf(0.0, 0.2, 0.3, 0.5)
    printSweepHeader("skipBnd", "lookbk")
    for (sk in skipGrid) {
      for (lb in lookbackGrid) {
        val c = BASELINE.copy(skipBand = sk, lookback = lb)
        val r = runStrategy(bars15m, rthHL, c)
        swD += SweepRow(c, r)
        printSweepRow("%.2f".format(sk), "$lb", r)
      }
      println()
    }
    sweeps["D. skipBand × lookback"] = swD

    // ── Best per sweep (by PF, min 100 trades), and a stacked best run.
    val bestA = swA.bestByPF()
    val bestB = swB.bestByPF()
    val bestC = swC.bestByPF()
    val bestD = swD.bestByPF()
    println("\n=== Best of each sweep (PF, ≥100 trades) ===")
    listOf("A" to bestA, "B" to bestB, "C" to bestC, "D" to bestD).forEach { (lbl, sr) ->
      if (sr == null) println("  $lbl: (none ≥100 trades)") else
        println("  $lbl: ${cfgStr(sr.cfg)}  → ${summStr(sr.r)}")
    }

    val stacked = Config(
      lookback = bestA?.cfg?.lookback ?: BASELINE.lookback,
      bufferPts = bestA?.cfg?.bufferPts ?: BASELINE.bufferPts,
      minRR = bestB?.cfg?.minRR ?: BASELINE.minRR,
      tpFrac = bestC?.cfg?.tpFrac ?: BASELINE.tpFrac,
      skipBand = bestD?.cfg?.skipBand ?: BASELINE.skipBand,
    )
    val stackedR = runStrategy(bars15m, rthHL, stacked)
    println("\n  Stacked best: ${cfgStr(stacked)}  → ${summStr(stackedR)}")

    // ── Write the HTML report.
    writeHtml(baseline, sweeps, stacked, stackedR)
    println("\nWrote $OUT_HTML")
  }

  private fun List<SweepRow>.bestByPF(minTrades: Int = 100): SweepRow? =
    filter { it.r.trades.size >= minTrades }.maxByOrNull { it.r.profitFactor }

  private fun cfgStr(c: Config) =
    "lb=${c.lookback} buf=${c.bufferPts} minRR=%.1f tpFrac=%.2f skipBand=%.2f"
      .format(c.minRR, c.tpFrac, c.skipBand)

  private fun summStr(r: Result) =
    "%d tr, PF=%.2f, total=$%,.0f, maxYrDD=$%,.0f, win=%.1f%%"
      .format(r.trades.size, r.profitFactor, r.total, r.maxYearlyDD, r.winRate)

  // ───────────────────────────────────────────────────────────────────────
  // Pre-pass helpers
  // ───────────────────────────────────────────────────────────────────────

  private fun buildRthDailyHL(bars5m: List<Bar>): Map<LocalDate, Pair<Double, Double>> {
    val out = sortedMapOf<LocalDate, DoubleArray>() // [high, low]
    for (b in bars5m) {
      val zdt = b.openTime.atZone(ZoneOffset.UTC)
      val mod = zdt.hour * 60 + zdt.minute
      if (mod < 9 * 60 + 35 || mod > 16 * 60) continue
      val date = zdt.toLocalDate()
      val hl = out.getOrPut(date) { doubleArrayOf(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY) }
      if (b.high > hl[0]) hl[0] = b.high
      if (b.low < hl[1]) hl[1] = b.low
    }
    return out.mapValues { it.value[0] to it.value[1] }
  }

  private fun aggregateTo15m(bars5m: List<Bar>): List<Bar> {
    val out = mutableListOf<Bar>()
    var cur: Bar? = null
    var curKeyMin = Long.MIN_VALUE
    for (b in bars5m) {
      val epochMin = b.openTime.epochSecond / 60
      val bucketCloseMin = ((epochMin + 14) / 15) * 15
      if (cur == null || bucketCloseMin != curKeyMin) {
        cur?.let { out += it }
        cur = Bar(
          timeFrame = TimeFrame.M15,
          openTime = Instant.ofEpochSecond(bucketCloseMin * 60),
          open = b.open,
          high = b.high,
          low = b.low,
          close = b.close,
          volume = b.volume,
        )
        curKeyMin = bucketCloseMin
      } else {
        cur!! += b
      }
    }
    cur?.let { out += it }
    return out
  }

  // ───────────────────────────────────────────────────────────────────────
  // Strategy
  // ───────────────────────────────────────────────────────────────────────

  private fun runStrategy(
    bars: List<Bar>,
    rthHL: Map<LocalDate, Pair<Double, Double>>,
    cfg: Config,
  ): Result {
    val rthDates = rthHL.keys.sorted()
    val dateIdx = HashMap<LocalDate, Int>(rthDates.size).apply {
      rthDates.forEachIndexed { i, d -> put(d, i) }
    }

    var balance = 0.0
    val trades = mutableListOf<Trade>()
    var open: Position? = null

    val yearlyPnL = sortedMapOf<Int, Double>()
    val yearStartBal = sortedMapOf<Int, Double>()
    val yearlyDD = sortedMapOf<Int, Double>()

    var currentDay: LocalDate? = null
    var todayRH = Double.NaN
    var todayRL = Double.NaN
    var todaySH: Double? = null
    var todaySL: Double? = null
    var todayPrevClose = Double.NaN
    var hasTradedToday = false

    fun closePos(pos: Position, exit: Double, reason: CloseReason, year: Int, date: LocalDate) {
      val pts = (exit - pos.entryPrice) * pos.side
      val dollars = pts * POINT_VALUE * CONTRACTS
      balance += dollars
      trades += Trade(pos.side, pos.entryPrice, exit, pts, dollars, reason, year, date)
      yearlyPnL.merge(year, dollars) { a, b -> a + b }
    }

    fun setupLevelsFor(date: LocalDate, firstBarOpen: Double) {
      val idx = dateIdx[date]
      todayRH = Double.NaN; todayRL = Double.NaN
      todaySH = null; todaySL = null
      todayPrevClose = Double.NaN
      if (idx == null || idx < 1) return
      val prev = rthDates[idx - 1]
      val (rh, rl) = rthHL[prev] ?: return
      todayRH = rh; todayRL = rl
      todayPrevClose = firstBarOpen   // approximation: first overnight bar's open ≈ prev session close
      val start = maxOf(0, idx - 1 - cfg.lookback)
      val endExcl = idx - 1
      if (endExcl <= start) return
      var maxH = Double.NEGATIVE_INFINITY
      var minL = Double.POSITIVE_INFINITY
      for (k in start until endExcl) {
        val (h, l) = rthHL[rthDates[k]] ?: continue
        if (h > maxH) maxH = h
        if (l < minL) minL = l
      }
      todaySH = if (maxH > rh) maxH else null
      todaySL = if (minL < rl) minL else null
    }

    for (bar in bars) {
      val zdt = bar.openTime.atZone(ZoneOffset.UTC)
      val year = zdt.year
      val date = zdt.toLocalDate()
      val mod = zdt.hour * 60 + zdt.minute
      val close = bar.close

      yearStartBal.getOrPut(year) { balance }

      if (currentDay == null || currentDay != date) {
        open?.let { closePos(it, bar.open, CloseReason.EOD, year, currentDay ?: date) }
        open = null
        currentDay = date
        hasTradedToday = false
        setupLevelsFor(date, bar.open)
      }

      val inRTH = mod in RTH_FIRST_BAR_MIN..RTH_LAST_BAR_MIN

      open = open?.let { pos ->
        val hitSL = if (pos.side > 0) bar.low <= pos.slPrice else bar.high >= pos.slPrice
        if (hitSL) { closePos(pos, pos.slPrice, CloseReason.SL, year, date); null } else pos
      }
      open = open?.let { pos ->
        val hitTP = if (pos.side > 0) bar.high >= pos.tpPrice else bar.low <= pos.tpPrice
        if (hitTP) { closePos(pos, pos.tpPrice, CloseReason.TP, year, date); null } else pos
      }

      if (inRTH && open == null && !hasTradedToday && mod <= LAST_ENTRY_MIN) {
        val sh = todaySH
        val sl = todaySL
        val rh = todayRH
        val rl = todayRL
        if (!rh.isNaN() && !rl.isNaN() && rh > rl) {
          val range = rh - rl
          // closeFrac = where yesterday closed in its range. 1 = at high, 0 = at low.
          val closeFrac = if (!todayPrevClose.isNaN())
            ((todayPrevClose - rl) / range).coerceIn(0.0, 1.0)
          else 0.5
          val allowShort = sh != null && closeFrac <= 1.0 - cfg.skipBand
          val allowLong  = sl != null && closeFrac >= cfg.skipBand

          // SHORT
          if (allowShort) {
            val stop = sh!! + cfg.bufferPts
            val tp = rh - cfg.tpFrac * range
            if (stop > tp) {
              val entryFloor = (cfg.minRR * stop + tp) / (1.0 + cfg.minRR)
              if (close in entryFloor..sh) {
                open = Position(side = -1, entryPrice = close, slPrice = stop, tpPrice = tp)
                hasTradedToday = true
              }
            }
          }
          // LONG
          if (open == null && allowLong) {
            val stop = sl!! - cfg.bufferPts
            val tp = rl + cfg.tpFrac * range
            if (tp > stop) {
              val entryCeil = (cfg.minRR * stop + tp) / (1.0 + cfg.minRR)
              if (close in sl..entryCeil) {
                open = Position(side = +1, entryPrice = close, slPrice = stop, tpPrice = tp)
                hasTradedToday = true
              }
            }
          }
        }
      }

      if (mod >= FORCE_CLOSE_MIN) {
        open?.let { closePos(it, close, CloseReason.EOD, year, date); open = null }
      }

      val ys = yearStartBal[year]!!
      val dd = (ys - balance).coerceAtLeast(0.0)
      yearlyDD.merge(year, dd) { a, b -> max(a, b) }
    }

    return Result(trades, yearlyPnL, yearlyDD)
  }

  // ───────────────────────────────────────────────────────────────────────
  // Console reporting
  // ───────────────────────────────────────────────────────────────────────

  private fun printSweepHeader(c1: String, c2: String) {
    println("%-8s %-8s | %5s %6s %8s %8s %5s %13s %13s".format(
      c1, c2, "#t", "win%", "avgWin", "avgLoss", "PF", "totalP&L", "maxYrDD"))
    println("-".repeat(95))
  }

  private fun printSweepRow(c1: String, c2: String, r: Result) {
    if (r.trades.isEmpty()) {
      println("%-8s %-8s | (no trades)".format(c1, c2)); return
    }
    println("%-8s %-8s | %5d %5.1f%% %7.1fpt %7.1fpt %5.2f %13s %13s".format(
      c1, c2, r.trades.size, r.winRate, r.avgWinPts, r.avgLossPts, r.profitFactor,
      "$%,.0f".format(r.total), "$%,.0f".format(r.maxYearlyDD)))
  }

  private fun printBaselineReport(r: Result) {
    println("=== Baseline ${cfgStr(BASELINE)} ===")
    if (r.trades.isEmpty()) { println("(no trades)"); return }
    println("  Trades: ${r.trades.size}, total \$P&L: ${"$%,.0f".format(r.total)}, PF: %.2f, win: %.1f%%"
      .format(r.profitFactor, r.winRate))
    val rc = r.reasonCounts
    println("  Close reason: TP=${rc[CloseReason.TP]}, SL=${rc[CloseReason.SL]}, EOD=${rc[CloseReason.EOD]}")
  }

  // ───────────────────────────────────────────────────────────────────────
  // HTML reporting
  // ───────────────────────────────────────────────────────────────────────

  private fun writeHtml(
    baseline: Result,
    sweeps: Map<String, List<SweepRow>>,
    stackedCfg: Config,
    stackedR: Result,
  ) {
    val outFile = File(OUT_HTML)
    outFile.parentFile?.mkdirs()

    val sb = StringBuilder()
    sb.append(HTML_HEAD)

    // Hero / summary
    sb.appendHero(baseline, stackedCfg, stackedR)

    // Baseline detail card
    sb.appendBaselineCard(baseline)

    // Each sweep section
    for ((name, rows) in sweeps) {
      sb.appendSweepSection(name, rows)
    }

    // Stacked best detail card
    sb.appendStackedCard(stackedCfg, stackedR)

    sb.append(HTML_FOOT)
    outFile.writeText(sb.toString())
  }

  private fun StringBuilder.appendHero(baseline: Result, stackedCfg: Config, stackedR: Result) {
    append("""
      <section class="hero bg-base-200 rounded-box p-8">
        <div class="hero-content text-center">
          <div>
            <h1 class="text-4xl font-extrabold tracking-tight">Rumers fade on NQ 15m</h1>
            <p class="py-3 text-base text-base-content/70 max-w-3xl mx-auto">
              Previous-day-range fade. SH/SL = highest/lowest of last <code>lookback</code> RTH days
              before yesterday. Entries gated by R:R floor; TP at fraction of yesterday's range;
              optional trend-day filter.
            </p>
            <div class="stats stats-vertical lg:stats-horizontal shadow mt-4">
              <div class="stat">
                <div class="stat-title">Baseline total P&L</div>
                <div class="stat-value text-lg">${dollarFmt(baseline.total)}</div>
                <div class="stat-desc">PF ${"%.2f".format(baseline.profitFactor)} • ${baseline.trades.size} trades</div>
              </div>
              <div class="stat">
                <div class="stat-title">Stacked-best total P&L</div>
                <div class="stat-value text-lg ${if (stackedR.total >= baseline.total) "text-success" else "text-warning"}">${dollarFmt(stackedR.total)}</div>
                <div class="stat-desc">PF ${"%.2f".format(stackedR.profitFactor)} • ${stackedR.trades.size} trades</div>
              </div>
              <div class="stat">
                <div class="stat-title">Stacked maxYrDD</div>
                <div class="stat-value text-lg">${dollarFmt(stackedR.maxYearlyDD)}</div>
                <div class="stat-desc">${cfgStr(stackedCfg)}</div>
              </div>
            </div>
          </div>
        </div>
      </section>
    """.trimIndent())
    append('\n')
  }

  private fun StringBuilder.appendBaselineCard(r: Result) {
    append("""
      <section class="card bg-base-100 shadow">
        <div class="card-body">
          <h2 class="card-title">Baseline (lookback=15, buffer=3, minRR=1.0, tpFrac=1.0, skipBand=0.0)</h2>
          <div class="grid md:grid-cols-2 gap-4">
            ${yearlyTable(r)}
            ${reasonTable(r)}
          </div>
        </div>
      </section>
    """.trimIndent())
    append('\n')
  }

  private fun StringBuilder.appendStackedCard(cfg: Config, r: Result) {
    append("""
      <section class="card bg-base-100 shadow border border-success/40">
        <div class="card-body">
          <h2 class="card-title">Stacked best: ${cfgStr(cfg)}</h2>
          <p class="text-sm text-base-content/60">
            Pulls the best per-sweep value of each knob (PF, ≥100 trades) and runs them together.
            Combined improvement isn't guaranteed -- knob interactions can subtract.
          </p>
          <div class="grid md:grid-cols-2 gap-4">
            ${yearlyTable(r)}
            ${reasonTable(r)}
          </div>
        </div>
      </section>
    """.trimIndent())
    append('\n')
  }

  private fun StringBuilder.appendSweepSection(name: String, rows: List<SweepRow>) {
    val best = rows.bestByPF()
    append("""
      <section class="card bg-base-100 shadow">
        <div class="card-body">
          <h2 class="card-title">$name</h2>
          ${if (best != null) """<p class="text-sm">Best (PF, ≥100 trades): <span class="badge badge-success badge-soft">${cfgStr(best.cfg)}</span> → ${summStr(best.r)}</p>""" else ""}
          <div class="overflow-x-auto">
            <table class="table table-zebra table-sm">
              <thead>
                <tr>
                  <th>lookback</th><th>buffer</th><th>minRR</th><th>tpFrac</th><th>skipBand</th>
                  <th class="text-right">trades</th>
                  <th class="text-right">win %</th>
                  <th class="text-right">avgWin pt</th>
                  <th class="text-right">avgLoss pt</th>
                  <th class="text-right">PF</th>
                  <th class="text-right">total P&L</th>
                  <th class="text-right">maxYrDD</th>
                </tr>
              </thead>
              <tbody>
                ${rows.joinToString("\n") { row -> sweepTableRow(row, best) }}
              </tbody>
            </table>
          </div>
        </div>
      </section>
    """.trimIndent())
    append('\n')
  }

  private fun sweepTableRow(row: SweepRow, best: SweepRow?): String {
    val isBest = best != null && row === best
    val cls = if (isBest) "bg-success/15 font-semibold" else ""
    val totalCls = when { row.r.total > 0 -> "text-success"; row.r.total < 0 -> "text-error"; else -> "" }
    return """
        <tr class="$cls">
          <td>${row.cfg.lookback}</td>
          <td>${"%.0f".format(row.cfg.bufferPts)}</td>
          <td>${"%.2f".format(row.cfg.minRR)}</td>
          <td>${"%.2f".format(row.cfg.tpFrac)}</td>
          <td>${"%.2f".format(row.cfg.skipBand)}</td>
          <td class="text-right tabular-nums">${row.r.trades.size}</td>
          <td class="text-right tabular-nums">${"%.1f".format(row.r.winRate)}</td>
          <td class="text-right tabular-nums">${"%.1f".format(row.r.avgWinPts)}</td>
          <td class="text-right tabular-nums">${"%.1f".format(row.r.avgLossPts)}</td>
          <td class="text-right tabular-nums">${"%.2f".format(row.r.profitFactor)}</td>
          <td class="text-right tabular-nums $totalCls">${dollarFmt(row.r.total)}</td>
          <td class="text-right tabular-nums">${dollarFmt(row.r.maxYearlyDD)}</td>
        </tr>
    """.trimIndent()
  }

  private fun yearlyTable(r: Result): String {
    val rows = r.yearlyPnL.keys.sorted().joinToString("\n") { y ->
      val pnl = r.yearlyPnL[y] ?: 0.0
      val dd = r.yearlyFromStartDD[y] ?: 0.0
      val n = r.trades.count { it.year == y }
      val cls = if (pnl >= 0) "text-success" else "text-error"
      """
        <tr>
          <td>$y</td>
          <td class="text-right tabular-nums $cls">${dollarFmt(pnl)}</td>
          <td class="text-right tabular-nums">${dollarFmt(dd)}</td>
          <td class="text-right tabular-nums">$n</td>
        </tr>
      """.trimIndent()
    }
    return """
      <div class="overflow-x-auto">
        <table class="table table-zebra table-sm">
          <thead><tr><th>Year</th><th class="text-right">P&L</th><th class="text-right">FromStartDD</th><th class="text-right">trades</th></tr></thead>
          <tbody>$rows</tbody>
        </table>
      </div>
    """.trimIndent()
  }

  private fun reasonTable(r: Result): String {
    val rc = r.reasonCounts
    val rows = CloseReason.values().joinToString("\n") { reason ->
      val ts = r.trades.filter { it.reason == reason }
      val dollars = ts.sumOf { it.dollarPnL }
      val cls = if (dollars >= 0) "text-success" else "text-error"
      """
        <tr>
          <td>${reason.name}</td>
          <td class="text-right tabular-nums">${rc[reason] ?: 0}</td>
          <td class="text-right tabular-nums $cls">${dollarFmt(dollars)}</td>
        </tr>
      """.trimIndent()
    }
    return """
      <div class="overflow-x-auto">
        <table class="table table-zebra table-sm">
          <thead><tr><th>Close reason</th><th class="text-right">#</th><th class="text-right">P&L</th></tr></thead>
          <tbody>$rows</tbody>
        </table>
      </div>
    """.trimIndent()
  }

  private fun dollarFmt(v: Double) =
    if (v >= 0) "$%,.0f".format(v) else "-$%,.0f".format(-v)

  private val HTML_HEAD = """
    <!doctype html>
    <html lang="en" data-theme="synthwave">
    <head>
      <meta charset="UTF-8" />
      <meta name="viewport" content="width=device-width, initial-scale=1.0" />
      <title>Rumers backtest — NQ 15m</title>
      <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/daisyui@5.5.19/daisyui.css" />
      <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/daisyui@5.5.19/themes.css" />
      <script src="https://cdn.jsdelivr.net/npm/@tailwindcss/browser@4.2.4/dist/index.global.js"></script>
    </head>
    <body class="min-h-screen bg-base-300">
      <div class="navbar bg-base-100 shadow-lg">
        <div class="navbar-start">
          <span class="text-2xl font-bold tracking-tight">📉 Rumers</span>
          <span class="badge badge-soft badge-accent ml-3">NQ 15m</span>
        </div>
        <div class="navbar-end gap-2">
          <span class="badge badge-ghost">prev-day fade</span>
          <span class="badge badge-ghost">5y backtest</span>
        </div>
      </div>
      <main class="mx-auto max-w-7xl p-6 lg:p-10 space-y-8">
  """.trimIndent()

  private val HTML_FOOT = """
      </main>
    </body>
    </html>
  """.trimIndent()
}
