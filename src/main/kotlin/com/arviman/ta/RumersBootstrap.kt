package com.arviman.ta

import com.arviman.ta.timeseries.readCsvBars
import java.io.File
import java.time.LocalDate
import java.util.Random

/**
 * Bootstrap monte-carlo for sizing decisions.
 *
 * For each candidate config, resample the historical daily-PnL distribution
 * with replacement, walk a synthetic eval path day-by-day, and stop on
 * pass / daily-bust / total-DD-bust / max-window.
 *
 * Then for each size multiplier, report:
 *   - P(pass within window)
 *   - P(bust before pass)
 *   - median calendar months to pass | passed
 *
 * The objective: find the largest size where P(bust) ≤ 30%
 * AND median pass-time ≤ 6 months. That's the size the user can safely
 * push the bot to.
 *
 * Bootstrap is iid by trading day. Caveat: it ignores serial correlation
 * (volatility clustering). Real-world bust probabilities are likely a bit
 * higher than reported. Treat results as a calibrated guide, not gospel.
 */
object RumersBootstrap {

  private const val NQ_DATA = "sampledata/NQ_5Years_8_11_2024.csv"
  private const val OUT_HTML = ".lavish/rumers-bootstrap.html"

  // the5ers $100k cTrader rules.
  private const val DAILY_CAP = 4_000.0
  private const val TOTAL_DD  = 10_000.0
  private const val TARGET    = 8_000.0

  // Window: 6 months ≈ 126 trading days; allow up to 12 months for slow paths.
  private const val WINDOW_DAYS = 126
  private const val MAX_DAYS    = 252

  // Bootstrap iterations per (config, size).
  private const val N_ITER = 20_000

  // Size multipliers in NQ-equivalent units (0.1 = 1 MNQ).
  private val SIZES = listOf(0.5, 0.8, 1.0, 1.1, 1.3, 1.5, 1.8, 2.0, 2.5, 3.0)

  // Candidate configs (label, config). Includes deploy + best rejection variants.
  internal val CANDIDATES: List<Pair<String, RumersTest.Config>> = listOf(
    "Deploy (rej=off)" to RumersTest.Config(
      lookback = 120, bufferPts = 5.0, minRR = 1.5, tpFrac = 0.75,
      pyramidingLimit = 1, requireProfitToPyramid = true, requireRejection = false,
    ),
    "Rejection-A: lb=30 buf=10 rr=2.0 tp=0.75" to RumersTest.Config(
      lookback = 30, bufferPts = 10.0, minRR = 2.0, tpFrac = 0.75,
      pyramidingLimit = 1, requireProfitToPyramid = true, requireRejection = true,
    ),
    "Rejection-B: lb=90 buf=5 rr=1.0 tp=1.0" to RumersTest.Config(
      lookback = 90, bufferPts = 5.0, minRR = 1.0, tpFrac = 1.0,
      pyramidingLimit = 1, requireProfitToPyramid = true, requireRejection = true,
    ),
    "Rejection-C: lb=45 buf=5 rr=1.5 tp=0.5" to RumersTest.Config(
      lookback = 45, bufferPts = 5.0, minRR = 1.5, tpFrac = 0.5,
      pyramidingLimit = 1, requireProfitToPyramid = true, requireRejection = true,
    ),
  )

  data class BootRow(
    val label: String,
    val size: Double,
    val nIter: Int,
    val passes: Int,
    val bustsDaily: Int,
    val bustsDD: Int,
    val ongoing: Int,
    val medianPassDays: Double,
    val meanPassDays: Double,
    val p25PassDays: Int,
    val p75PassDays: Int,
  ) {
    val pPass: Double get() = passes.toDouble() / nIter
    val pBust: Double get() = (bustsDaily + bustsDD).toDouble() / nIter
    val pPassIn6mo: Double get() = passes.toDouble() / nIter   // (window is 6mo)
    val medianMonths: Double get() = medianPassDays / 21.0     // ~21 trading days / month
  }

  @JvmStatic
  fun main(args: Array<String>) {
    println("=== Rumers bootstrap monte-carlo ===")
    println("Rules: daily \$${"%,.0f".format(DAILY_CAP)}, total DD \$${"%,.0f".format(TOTAL_DD)}, target \$${"%,.0f".format(TARGET)}")
    println("Window: $WINDOW_DAYS trading days (~6 mo). Iterations: $N_ITER per (config, size).\n")

    println("Loading data...")
    val bars5m = readCsvBars(NQ_DATA)
    val bars15m = RumersTest.aggregateTo15m(bars5m)
    val rthHL = RumersTest.buildRthDailyHL(bars5m)

    // Pool of all RTH trading days in the data (including non-trade days = $0).
    val allTradingDays: List<LocalDate> = rthHL.keys.sorted()
    println("Universe: ${allTradingDays.size} trading days.\n")

    val allRows = mutableListOf<BootRow>()

    for ((label, cfg) in CANDIDATES) {
      println("=== $label ===")
      val r = RumersTest.runStrategy(bars15m, rthHL, cfg)
      println("  Backtest: ${r.trades.size} trades, total \$${"%,.0f".format(r.total)}, PF=${"%.2f".format(r.profitFactor)}")
      if (r.trades.size < 30) { println("  (skipped — too few trades)"); continue }

      // Daily P&L pool: every trading day → trade P&L (0 if no trade).
      val tradesByDate = r.trades.groupBy { it.date }
      val pool = DoubleArray(allTradingDays.size) { i ->
        val d = allTradingDays[i]
        tradesByDate[d]?.sumOf { it.dollarPnL } ?: 0.0
      }

      println("  %-7s | %-8s %-8s %-8s | %-10s %-8s %-8s".format(
        "size", "P(pass)", "P(bust)", "P(grind)", "medianMo", "p25", "p75"))
      println("  " + "-".repeat(75))

      for (size in SIZES) {
        val row = bootstrap(label, pool, size, N_ITER)
        allRows += row
        println("  %-7.2f | %-8.1f%% %-8.1f%% %-8.1f%% | %-10.1f %-8d %-8d".format(
          size,
          row.pPass * 100, row.pBust * 100, row.ongoing * 100.0 / N_ITER,
          row.medianMonths,
          (row.p25PassDays / 21),
          (row.p75PassDays / 21),
        ))
      }
      println()
    }

    // Recommendation: per config, the largest size where P(bust) ≤ 30% AND P(pass) ≥ 50%.
    println("=== RECOMMENDED (per config) — largest size where P(bust) ≤ 30% AND P(pass) ≥ 50% ===")
    for ((label, _) in CANDIDATES) {
      val rows = allRows.filter { it.label == label }
      val feasible = rows.filter { it.pBust <= 0.30 && it.pPass >= 0.50 }
      val pick = feasible.maxByOrNull { it.size }
      if (pick == null) {
        println("  $label: (no size meets both criteria)")
      } else {
        println("  $label  →  size=${"%.2f".format(pick.size)} NQ  " +
          "(P(pass)=${"%.1f".format(pick.pPass*100)}%, P(bust)=${"%.1f".format(pick.pBust*100)}%, " +
          "median ${"%.1f".format(pick.medianMonths)} mo)")
      }
    }

    println("\n=== RECOMMENDED (overall) — highest P(pass within 6 mo) with P(bust) ≤ 30% ===")
    val ranked = allRows.filter { it.pBust <= 0.30 }.sortedByDescending { it.pPass }
    ranked.take(10).forEachIndexed { i, r ->
      println("  ${i + 1}. ${r.label}  size=${"%.2f".format(r.size)} NQ  " +
        "P(pass)=${"%.1f".format(r.pPass*100)}% P(bust)=${"%.1f".format(r.pBust*100)}% " +
        "median=${"%.1f".format(r.medianMonths)}mo")
    }

    writeHtml(allRows)
    println("\nWrote $OUT_HTML")
  }

  private fun bootstrap(label: String, pool: DoubleArray, size: Double, n: Int): BootRow {
    val rng = Random(42L + label.hashCode())   // reproducible per label
    val poolSize = pool.size
    var passes = 0
    var bustsDaily = 0
    var bustsDD = 0
    var ongoing = 0
    val passTimes = IntArray(n)
    var passIdx = 0

    for (i in 0 until n) {
      var bal = 0.0
      var peak = 0.0
      var outcome = 0   // 0 = ongoing, 1 = pass, 2 = bust daily, 3 = bust DD
      var passDay = -1
      for (day in 0 until WINDOW_DAYS) {
        val pnl = pool[rng.nextInt(poolSize)] * size
        if (pnl < -DAILY_CAP) { outcome = 2; break }
        bal += pnl
        if (bal > peak) peak = bal
        if (peak - bal > TOTAL_DD) { outcome = 3; break }
        if (bal >= TARGET) { outcome = 1; passDay = day + 1; break }
      }
      when (outcome) {
        1 -> { passes++; passTimes[passIdx++] = passDay }
        2 -> bustsDaily++
        3 -> bustsDD++
        else -> ongoing++
      }
    }

    val times = passTimes.copyOf(passIdx).sortedArray()
    val median = if (times.isEmpty()) Double.NaN else times[times.size / 2].toDouble()
    val mean = if (times.isEmpty()) Double.NaN else times.average()
    val p25 = if (times.isEmpty()) 0 else times[(times.size * 0.25).toInt()]
    val p75 = if (times.isEmpty()) 0 else times[(times.size * 0.75).toInt().coerceAtMost(times.size - 1)]

    return BootRow(label, size, n, passes, bustsDaily, bustsDD, ongoing, median, mean, p25, p75)
  }

  private fun writeHtml(rows: List<BootRow>) {
    val sb = StringBuilder()
    sb.append("""<!doctype html><html lang="en" data-theme="synthwave"><head>
<meta charset="UTF-8"><title>Rumers bootstrap monte-carlo</title>
<link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/daisyui@5.5.19/daisyui.css">
<link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/daisyui@5.5.19/themes.css">
<script src="https://cdn.jsdelivr.net/npm/@tailwindcss/browser@4.2.4/dist/index.global.js"></script>
</head><body class="min-h-screen bg-base-300">
<div class="navbar bg-base-100 shadow-lg"><div class="navbar-start">
<span class="text-2xl font-bold">🎲 Rumers bootstrap monte-carlo</span>
<span class="badge badge-soft badge-accent ml-3">the5ers \$100k · ${"%,d".format(N_ITER)} iter · 6mo window</span></div></div>
<main class="mx-auto max-w-7xl p-6 lg:p-10 space-y-6">
<section class="alert alert-info"><div class="text-sm">
Day-by-day bootstrap from the actual daily-P&L distribution. Each cell shows: chance of passing the \$${"%,.0f".format(TARGET)} target before busting (\$${"%,.0f".format(DAILY_CAP)} daily or \$${"%,.0f".format(TOTAL_DD)} total DD) in 126 trading days (~6 months).
Picked cells (green) maximize P(pass) while keeping P(bust) ≤ 30%.
Caveat: iid daily sampling ignores volatility clustering, so live bust risk may be higher than reported.
</div></section>""")

    val byLabel = rows.groupBy { it.label }
    for ((label, items) in byLabel) {
      val bestInLabel = items.filter { it.pBust <= 0.30 && it.pPass >= 0.50 }.maxByOrNull { it.size }
      sb.append("""<section class="card bg-base-100 shadow"><div class="card-body">
<h2 class="card-title">$label</h2>
<div class="overflow-x-auto"><table class="table table-zebra table-sm">
<thead><tr><th class="text-right">Size (NQ)</th>
<th class="text-right">P(pass)</th><th class="text-right">P(bust)</th><th class="text-right">P(grind)</th>
<th class="text-right">Daily bust</th><th class="text-right">DD bust</th>
<th class="text-right">Median (mo)</th><th class="text-right">p25 (mo)</th><th class="text-right">p75 (mo)</th></tr></thead><tbody>""")
      for (r in items) {
        val isPick = bestInLabel != null && r === bestInLabel
        val cls = when {
          isPick -> "bg-success/25 font-semibold"
          r.pBust > 0.30 -> "text-error/70"
          else -> ""
        }
        val pBustDaily = r.bustsDaily * 100.0 / r.nIter
        val pBustDD = r.bustsDD * 100.0 / r.nIter
        val pGrind = r.ongoing * 100.0 / r.nIter
        sb.append("<tr class=\"$cls\">")
        sb.append("<td class=\"text-right tabular-nums\">${"%.2f".format(r.size)}</td>")
        sb.append("<td class=\"text-right tabular-nums text-success\">${"%.1f".format(r.pPass*100)}%</td>")
        sb.append("<td class=\"text-right tabular-nums text-error\">${"%.1f".format(r.pBust*100)}%</td>")
        sb.append("<td class=\"text-right tabular-nums\">${"%.1f".format(pGrind)}%</td>")
        sb.append("<td class=\"text-right tabular-nums\">${"%.1f".format(pBustDaily)}%</td>")
        sb.append("<td class=\"text-right tabular-nums\">${"%.1f".format(pBustDD)}%</td>")
        val mt = if (r.medianMonths.isNaN()) "—" else "%.1f".format(r.medianMonths)
        val p25mo = if (r.p25PassDays == 0) "—" else "%.1f".format(r.p25PassDays / 21.0)
        val p75mo = if (r.p75PassDays == 0) "—" else "%.1f".format(r.p75PassDays / 21.0)
        sb.append("<td class=\"text-right tabular-nums\">$mt</td>")
        sb.append("<td class=\"text-right tabular-nums\">$p25mo</td>")
        sb.append("<td class=\"text-right tabular-nums\">$p75mo</td>")
        sb.append("</tr>")
      }
      sb.append("</tbody></table></div></div></section>")
    }

    sb.append("</main></body></html>")
    File(OUT_HTML).apply { parentFile?.mkdirs(); writeText(sb.toString()) }
  }
}
