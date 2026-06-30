package com.arviman.ta

import com.arviman.ta.indicators.VolumeProfile
import com.arviman.ta.timeseries.TimeFrame
import com.arviman.ta.timeseries.streamCsvBars
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Streams the 525MB Binance 1m ETH file, builds per-UTC-day volume
 * profiles, prints a summary, and emits an HTML report.
 *
 * Memory pattern: we hold ONE in-flight per-day profile + a compact summary
 * (POC/VAH/VAL + top HVN/LVN levels) per finalised day. The 1m bars are
 * never materialized into a list — `streamCsvBars` is a lazy `Sequence`.
 *
 * Run: ./gradlew run -PmainClass=com.arviman.ta.EthVolumeProfileDemo
 *
 * Each bucket = $1 in price (configurable via TICK_SIZE). With ETH ranging
 * ~$10–$5,000, an active day touches maybe 30–500 buckets.
 */
object EthVolumeProfileDemo {

  private const val DATA_PATH = "sampledata/ETHUSD_1m_Binance.csv"
  private const val OUT_HTML = ".lavish/eth-volume-profile.html"
  private const val TICK_SIZE = 1.0          // $1 buckets
  private const val VALUE_AREA_FRACTION = 0.70
  private const val HVN_WINDOW = 3
  private const val LVN_WINDOW = 3
  private const val LAST_N_DAYS_DETAIL = 30   // detailed report for last N days

  data class DaySummary(
    val date: LocalDate,
    val bars: Int,
    val totalVolume: Double,
    val priceLow: Double,
    val priceHigh: Double,
    val poc: Double,
    val val_: Double,
    val vah: Double,
    val hvns: List<Pair<Double, Double>>,
    val lvns: List<Pair<Double, Double>>,
    val histogram: List<Pair<Double, Double>>?, // null for old days to save memory
  )

  @JvmStatic
  fun main(args: Array<String>) {
    println("=== ETH 1m volume-profile demo ===")
    println("Streaming $DATA_PATH (525MB, ~4.28M bars)...\n")

    val file = File(DATA_PATH)
    if (!file.exists()) {
      System.err.println("Data file not found at $DATA_PATH — drop it there and re-run.")
      return
    }
    println("File: ${"%.1f".format(file.length() / 1024.0 / 1024.0)} MB\n")

    val startMs = System.currentTimeMillis()
    val summaries = mutableListOf<DaySummary>()
    var currentDate: LocalDate? = null
    var currentVp: VolumeProfile? = null
    var currentBars = 0
    var currentLow = Double.POSITIVE_INFINITY
    var currentHigh = Double.NEGATIVE_INFINITY
    var totalBars = 0L

    fun finalizeDay(keepHistogram: Boolean) {
      val vp = currentVp ?: return
      val date = currentDate ?: return
      if (vp.isEmpty()) return
      val (lo, hi) = vp.valueArea(VALUE_AREA_FRACTION)
      summaries += DaySummary(
        date = date,
        bars = currentBars,
        totalVolume = vp.total(),
        priceLow = currentLow,
        priceHigh = currentHigh,
        poc = vp.poc(),
        val_ = lo,
        vah = hi,
        hvns = vp.highVolumeNodes(window = HVN_WINDOW, topN = 5),
        lvns = vp.lowVolumeNodes(window = LVN_WINDOW, topN = 5),
        histogram = if (keepHistogram) vp.snapshot() else null,
      )
    }

    // Stream — TimeFrame.M1 passed so we skip the auto-detection peek.
    for (bar in streamCsvBars(DATA_PATH, TimeFrame.M1)) {
      val date = bar.openTime.atZone(ZoneOffset.UTC).toLocalDate()
      if (currentDate == null || currentDate != date) {
        // Finalize previous day. Keep full histogram only for the most recent N days.
        finalizeDay(keepHistogram = true)
        // Trim histograms older than LAST_N_DAYS_DETAIL.
        if (summaries.size > LAST_N_DAYS_DETAIL) {
          val i = summaries.size - LAST_N_DAYS_DETAIL - 1
          if (i >= 0 && summaries[i].histogram != null) {
            summaries[i] = summaries[i].copy(histogram = null)
          }
        }
        currentDate = date
        currentVp = VolumeProfile(TICK_SIZE)
        currentBars = 0
        currentLow = Double.POSITIVE_INFINITY
        currentHigh = Double.NEGATIVE_INFINITY
      }
      currentVp!!.addBar(bar)
      currentBars++
      if (bar.low < currentLow) currentLow = bar.low
      if (bar.high > currentHigh) currentHigh = bar.high
      totalBars++

      if (totalBars % 500_000L == 0L) {
        val elapsed = (System.currentTimeMillis() - startMs) / 1000
        println("  ... %,d bars (%.0fs), %d days so far".format(totalBars, elapsed.toDouble(), summaries.size))
      }
    }
    // Finalize the last (in-flight) day.
    finalizeDay(keepHistogram = true)

    val elapsedSec = (System.currentTimeMillis() - startMs) / 1000.0
    println("\nDone. Processed %,d bars over %d days in %.1fs.\n".format(totalBars, summaries.size, elapsedSec))

    // Console summary: most recent 10 days.
    val tail = summaries.takeLast(10)
    println("=== Last 10 UTC days ===")
    println("%-10s | %6s %14s %10s %10s | %10s %10s %10s | %s".format(
      "date", "bars", "volume", "low", "high", "VAL", "POC", "VAH", "top 3 HVN"))
    println("-".repeat(140))
    for (s in tail) {
      val hvnStr = s.hvns.take(3).joinToString(", ") { "$%.0f".format(it.first) }
      println("%-10s | %6d %14s %10s %10s | %10s %10s %10s | %s".format(
        s.date, s.bars, "%,.0f".format(s.totalVolume),
        "$%.2f".format(s.priceLow), "$%.2f".format(s.priceHigh),
        "$%.2f".format(s.val_), "$%.2f".format(s.poc), "$%.2f".format(s.vah),
        hvnStr))
    }

    // Aggregate stats
    val totalVolAll = summaries.sumOf { it.totalVolume }
    val avgBars = summaries.map { it.bars }.average()
    val avgRange = summaries.map { it.priceHigh - it.priceLow }.average()
    val avgValueAreaWidth = summaries.map { it.vah - it.val_ }.average()
    println("\n=== Aggregate (${summaries.size} days) ===")
    println("  Total ETH traded:        %,.0f".format(totalVolAll))
    println("  Avg bars per day:        %.0f".format(avgBars))
    println("  Avg daily price range:   \$%.2f".format(avgRange))
    println("  Avg value-area width:    \$%.2f (%.1f%% of range)".format(
      avgValueAreaWidth, 100 * avgValueAreaWidth / avgRange))

    writeHtml(summaries)
    println("\nWrote $OUT_HTML")
  }

  private fun writeHtml(summaries: List<DaySummary>) {
    val sb = StringBuilder()
    sb.append("""<!doctype html><html lang="en" data-theme="synthwave"><head>
<meta charset="UTF-8"><title>ETH 1m volume profile</title>
<link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/daisyui@5.5.19/daisyui.css">
<link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/daisyui@5.5.19/themes.css">
<script src="https://cdn.jsdelivr.net/npm/@tailwindcss/browser@4.2.4/dist/index.global.js"></script>
<style>
  .vp-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 1rem; }
  .vp-row { display: flex; align-items: center; gap: 4px; font-family: ui-monospace, monospace; font-size: 10px; line-height: 1.1; }
  .vp-row .price { width: 60px; text-align: right; color: #aaa; }
  .vp-row .bar { background: rgba(74, 222, 128, 0.7); height: 8px; min-width: 1px; }
  .vp-row.poc .bar { background: rgba(248, 113, 113, 0.95); }
  .vp-row.va .bar { background: rgba(250, 204, 21, 0.7); }
  .vp-row.poc { font-weight: 600; }
</style>
</head><body class="min-h-screen bg-base-300">
<div class="navbar bg-base-100 shadow-lg"><div class="navbar-start">
<span class="text-2xl font-bold">📊 ETH 1m volume profile</span>
<span class="badge badge-soft badge-accent ml-3">Binance · last ${summaries.size} UTC days</span></div></div>
<main class="mx-auto max-w-7xl p-6 lg:p-10 space-y-6">""")

    val agg = summaries
    val totalVol = agg.sumOf { it.totalVolume }
    val avgBars = agg.map { it.bars }.average()
    val avgRange = agg.map { it.priceHigh - it.priceLow }.average()
    val avgVA = agg.map { it.vah - it.val_ }.average()
    sb.append("""
<section class="stats stats-vertical lg:stats-horizontal shadow w-full">
  <div class="stat"><div class="stat-title">Days</div><div class="stat-value text-lg">${agg.size}</div></div>
  <div class="stat"><div class="stat-title">Total ETH traded</div><div class="stat-value text-lg">${"%,.0f".format(totalVol)}</div></div>
  <div class="stat"><div class="stat-title">Avg bars/day</div><div class="stat-value text-lg">${"%.0f".format(avgBars)}</div></div>
  <div class="stat"><div class="stat-title">Avg daily range</div><div class="stat-value text-lg">${'$'}${"%.2f".format(avgRange)}</div></div>
  <div class="stat"><div class="stat-title">Avg value-area width</div><div class="stat-value text-lg">${'$'}${"%.2f".format(avgVA)}</div></div>
</section>""")

    // Daily summary table (entire range).
    sb.append("""
<section class="card bg-base-100 shadow"><div class="card-body">
<h2 class="card-title">All days — POC / VAH / VAL</h2>
<div class="overflow-x-auto"><table class="table table-zebra table-sm">
<thead><tr><th>Date</th><th class="text-right">Bars</th><th class="text-right">Volume (ETH)</th>
<th class="text-right">Low</th><th class="text-right">High</th>
<th class="text-right">VAL</th><th class="text-right">POC</th><th class="text-right">VAH</th>
<th class="text-right">VA width</th><th>Top HVNs</th></tr></thead><tbody>""")
    for (s in agg.reversed()) {
      val vaWidth = s.vah - s.val_
      val hvnsStr = s.hvns.take(3).joinToString(", ") { "$%.0f".format(it.first) }
      sb.append("<tr>")
      sb.append("<td>${s.date}</td>")
      sb.append("<td class=\"text-right tabular-nums\">${s.bars}</td>")
      sb.append("<td class=\"text-right tabular-nums\">${"%,.0f".format(s.totalVolume)}</td>")
      sb.append("<td class=\"text-right tabular-nums\">${'$'}${"%.2f".format(s.priceLow)}</td>")
      sb.append("<td class=\"text-right tabular-nums\">${'$'}${"%.2f".format(s.priceHigh)}</td>")
      sb.append("<td class=\"text-right tabular-nums\">${'$'}${"%.2f".format(s.val_)}</td>")
      sb.append("<td class=\"text-right tabular-nums text-error\">${'$'}${"%.2f".format(s.poc)}</td>")
      sb.append("<td class=\"text-right tabular-nums\">${'$'}${"%.2f".format(s.vah)}</td>")
      sb.append("<td class=\"text-right tabular-nums\">${'$'}${"%.2f".format(vaWidth)}</td>")
      sb.append("<td>$hvnsStr</td>")
      sb.append("</tr>")
    }
    sb.append("</tbody></table></div></div></section>")

    // Detailed histograms for the most recent N days that retained data.
    val withHisto = agg.filter { it.histogram != null }.takeLast(LAST_N_DAYS_DETAIL)
    sb.append("""
<section class="card bg-base-100 shadow"><div class="card-body">
<h2 class="card-title">Volume distribution — last ${withHisto.size} days</h2>
<p class="text-sm text-base-content/60">Red = POC. Yellow = Value Area (70%). Green = remaining volume.</p>
<div class="vp-grid mt-4">""")
    for (s in withHisto.reversed()) {
      val histo = s.histogram!!
      val maxVol = histo.maxOf { it.second }
      sb.append("""<div class="border border-base-content/20 rounded p-2">
<div class="text-sm font-semibold mb-1">${s.date}</div>
<div class="text-xs text-base-content/60 mb-2">POC ${'$'}${"%.2f".format(s.poc)} · VA ${'$'}${"%.2f".format(s.val_)}-${'$'}${"%.2f".format(s.vah)}</div>""")
      // Sort by price descending so high prices are at top of chart.
      for ((price, vol) in histo.sortedByDescending { it.first }) {
        val widthPct = (vol / maxVol * 100).coerceIn(1.0, 100.0)
        val cls = when {
          kotlin.math.abs(price - s.poc) < TICK_SIZE -> "vp-row poc"
          price in s.val_..s.vah -> "vp-row va"
          else -> "vp-row"
        }
        sb.append("""<div class="$cls"><span class="price">${'$'}${"%.0f".format(price)}</span><div class="bar" style="width: ${"%.1f".format(widthPct)}%;"></div></div>""")
      }
      sb.append("</div>")
    }
    sb.append("</div></div></section>")

    sb.append("</main></body></html>")
    File(OUT_HTML).apply { parentFile?.mkdirs(); writeText(sb.toString()) }
  }
}
