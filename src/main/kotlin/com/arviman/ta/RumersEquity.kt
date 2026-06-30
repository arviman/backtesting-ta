package com.arviman.ta

import com.arviman.ta.timeseries.readCsvBars
import java.io.File
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Equity curve + drawdown analyzer for the deployed Rumers config on NQ M15.
 *
 * Produces:
 *   - Per-trade running equity (aggregated to per-day for plotting).
 *   - Underwater drawdown series (balance − running peak).
 *   - Top N drawdown episodes: peak date, trough date, recovery date,
 *     magnitude $, calendar days underwater.
 *   - Top N worst single-day losses.
 *   - Position-sizing scenarios for common funded evaluation rule sets,
 *     showing the largest contract count that still fits each rule.
 *
 * HTML report at .lavish/rumers-equity.html (Chart.js from CDN).
 */
object RumersEquity {

  private const val NQ_DATA = "sampledata/NQ_5Years_8_11_2024.csv"
  private const val OUT_HTML = ".lavish/rumers-equity.html"

  // Deployed config (grid-search Composite winner; same as RumersBot.cs defaults).
  private val CFG = RumersTest.Config(
    lookback = 120, bufferPts = 5.0, minRR = 1.5, tpFrac = 0.75,
    skipBand = 0.0, is24x7 = false,
    pointValue = 20.0, contracts = 1.0,
    pyramidingLimit = 1, requireProfitToPyramid = true,
  )

  // Funded-evaluation rule sets across common account sizes.
  // (name, dailyLossCap, totalDDCap, profitTarget). Micro NQ (MNQ) is 1/10
  // the dollar value of NQ, so the contract column shows the largest NQ
  // count OR equivalent MNQ count (× 10) that fits each rule.
  private val EVAL_RULES = listOf(
    EvalRules("the5ers $50k",   dailyLoss = 2_000.0,  totalDD =  5_000.0, target =  5_000.0),
    EvalRules("the5ers $100k",  dailyLoss = 4_000.0,  totalDD = 10_000.0, target =  8_000.0),
    EvalRules("the5ers $200k",  dailyLoss = 8_000.0,  totalDD = 20_000.0, target = 20_000.0),
    EvalRules("FTMO $50k Std",  dailyLoss = 2_500.0,  totalDD =  5_000.0, target =  5_000.0),
    EvalRules("FTMO $100k Std", dailyLoss = 5_000.0,  totalDD = 10_000.0, target = 10_000.0),
    EvalRules("FTMO $200k Std", dailyLoss = 10_000.0, totalDD = 20_000.0, target = 20_000.0),
    EvalRules("MFFU $100k",     dailyLoss = 5_000.0,  totalDD = 10_000.0, target =  8_000.0),
  )

  data class EvalRules(val name: String, val dailyLoss: Double, val totalDD: Double, val target: Double)

  data class DayPnL(val date: LocalDate, val pnl: Double, val trades: Int)
  data class EquityPoint(val date: LocalDate, val balance: Double, val peak: Double, val dd: Double)
  data class DDEpisode(
    val peakDate: LocalDate,
    val troughDate: LocalDate,
    val recoveryDate: LocalDate?,
    val magnitude: Double,
    val days: Int,
  )

  @JvmStatic
  fun main(args: Array<String>) {
    println("Loading NQ M15 data...")
    val bars5m = readCsvBars(NQ_DATA)
    val bars15m = RumersTest.aggregateTo15m(bars5m)
    val rthHL = RumersTest.buildRthDailyHL(bars5m)
    val result = RumersTest.runStrategy(bars15m, rthHL, CFG)
    println("Trades: ${result.trades.size}, total: \$${"%,.0f".format(result.total)}\n")

    // Per-day aggregation.
    val byDay: List<DayPnL> = result.trades
      .groupBy { it.date }
      .toSortedMap()
      .map { (date, ts) -> DayPnL(date, ts.sumOf { it.dollarPnL }, ts.size) }

    // Running equity + underwater drawdown.
    var bal = 0.0
    var peak = 0.0
    val equity = mutableListOf<EquityPoint>()
    for (d in byDay) {
      bal += d.pnl
      if (bal > peak) peak = bal
      equity += EquityPoint(d.date, bal, peak, bal - peak)
    }

    // DD episodes: each (peak → trough → recovery).
    val episodes = mutableListOf<DDEpisode>()
    var ddPeakDate: LocalDate? = null
    var ddPeakValue = Double.NEGATIVE_INFINITY
    var ddTrough = 0.0
    var ddTroughDate: LocalDate? = null
    for (p in equity) {
      if (p.balance >= ddPeakValue) {
        if (ddPeakDate != null && ddTroughDate != null && ddTrough < ddPeakValue) {
          episodes += DDEpisode(
            peakDate = ddPeakDate!!,
            troughDate = ddTroughDate!!,
            recoveryDate = p.date,
            magnitude = ddPeakValue - ddTrough,
            days = ChronoUnit.DAYS.between(ddPeakDate, p.date).toInt(),
          )
        }
        ddPeakValue = p.balance
        ddPeakDate = p.date
        ddTrough = p.balance
        ddTroughDate = p.date
      } else if (p.balance < ddTrough) {
        ddTrough = p.balance
        ddTroughDate = p.date
      }
    }
    if (ddPeakDate != null && ddTroughDate != null && ddTrough < ddPeakValue) {
      episodes += DDEpisode(
        peakDate = ddPeakDate!!,
        troughDate = ddTroughDate!!,
        recoveryDate = null,
        magnitude = ddPeakValue - ddTrough,
        days = ChronoUnit.DAYS.between(ddPeakDate, equity.last().date).toInt(),
      )
    }

    val maxDD = -(equity.minByOrNull { it.dd }?.dd ?: 0.0)
    val worstDay = byDay.minByOrNull { it.pnl }!!
    val years = ChronoUnit.DAYS.between(equity.first().date, equity.last().date) / 365.25

    println("Headline:")
    println("  Final balance:    \$${"%,.0f".format(equity.last().balance)} over ${"%.1f".format(years)}y")
    println("  Annual:           \$${"%,.0f".format(result.total / years)}/yr")
    println("  Peak equity DD:   \$${"%,.0f".format(maxDD)}")
    println("  Worst single day: \$${"%,.0f".format(worstDay.pnl)} on ${worstDay.date}")
    println()

    // Position-sizing scenarios per eval rule set.
    println("Sizing scenarios (max NQ-equivalent that fits both rules):")
    val sizingRows = sizingTable(maxDD, worstDay.pnl, result.total)
    for (s in sizingRows) {
      val scale = s.unitsX10 / 10.0
      val nqWhole = s.unitsX10 / 10
      val mnqExtra = s.unitsX10 % 10
      val sizeStr = when {
        s.unitsX10 == 0 -> "(none fits)"
        mnqExtra == 0 -> "${nqWhole} NQ"
        else -> "${nqWhole} NQ + ${mnqExtra} MNQ"
      }
      val annual = result.total * scale / years
      val monthsToTarget = if (annual > 0) s.rules.target / annual * 12.0 else Double.POSITIVE_INFINITY
      println("  ${s.rules.name.padEnd(20)}  $sizeStr  → " +
        "daily ${'$'}${"%,.0f".format(-worstDay.pnl * scale)}, " +
        "DD ${'$'}${"%,.0f".format(maxDD * scale)}, " +
        "${'$'}${"%,.0f".format(annual)}/yr, " +
        "target in %.1f mo".format(monthsToTarget))
    }

    writeHtml(byDay, equity, episodes.sortedByDescending { it.magnitude }.take(10),
              byDay.sortedBy { it.pnl }.take(10), maxDD, worstDay, result.total, years, sizingRows)
    println("\nWrote $OUT_HTML")
  }

  // unitsX10 = best total exposure in units of 0.1 NQ (i.e. integer MNQ count).
  // Convert back: NQ = unitsX10 / 10. With MNQ at 1/10 the dollar value, this
  // lets sizing step in 10% increments instead of whole-NQ jumps.
  data class SizingRow(val rules: EvalRules, val unitsX10: Int)

  private fun sizingTable(maxDD: Double, worstDay: Double, totalProfit: Double): List<SizingRow> {
    val worstDayAbs = -worstDay
    return EVAL_RULES.map { rules ->
      var best = 0
      // 1 .. 500 tenths of a contract (= up to 50 NQ-equivalent).
      for (m in 1..500) {
        val scale = m / 10.0
        if (scale * maxDD <= rules.totalDD && scale * worstDayAbs <= rules.dailyLoss) best = m
      }
      SizingRow(rules, best)
    }
  }

  private fun writeHtml(
    byDay: List<DayPnL>,
    equity: List<EquityPoint>,
    topEpisodes: List<DDEpisode>,
    worstDays: List<DayPnL>,
    maxDD: Double,
    worstDay: DayPnL,
    totalProfit: Double,
    years: Double,
    sizing: List<SizingRow>,
  ) {
    val labels = equity.joinToString(",") { "\"${it.date}\"" }
    val balSeries = equity.joinToString(",") { "%.2f".format(it.balance) }
    val ddSeries = equity.joinToString(",") { "%.2f".format(it.dd) }
    val dayLabels = byDay.joinToString(",") { "\"${it.date}\"" }
    val dayBars = byDay.joinToString(",") { "%.2f".format(it.pnl) }

    val sb = StringBuilder()
    sb.append("""<!doctype html><html lang="en" data-theme="synthwave"><head>
<meta charset="UTF-8"><title>Rumers equity + drawdown</title>
<link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/daisyui@5.5.19/daisyui.css">
<link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/daisyui@5.5.19/themes.css">
<script src="https://cdn.jsdelivr.net/npm/@tailwindcss/browser@4.2.4/dist/index.global.js"></script>
<script src="https://cdn.jsdelivr.net/npm/chart.js@4.4.4/dist/chart.umd.min.js"></script>
<script src="https://cdn.jsdelivr.net/npm/chartjs-adapter-date-fns@3.0.0/dist/chartjs-adapter-date-fns.bundle.min.js"></script>
</head><body class="min-h-screen bg-base-300">
<div class="navbar bg-base-100 shadow-lg"><div class="navbar-start">
<span class="text-2xl font-bold">📈 Rumers equity + drawdown</span>
<span class="badge badge-soft badge-accent ml-3">NQ M15 · deploy config · 1 contract</span></div></div>
<main class="mx-auto max-w-7xl p-6 lg:p-10 space-y-6">

<section class="stats stats-vertical lg:stats-horizontal shadow w-full">
  <div class="stat"><div class="stat-title">Final balance</div>
    <div class="stat-value text-lg">${'$'}${"%,.0f".format(equity.last().balance)}</div>
    <div class="stat-desc">${"%.1f".format(years)}y · ${byDay.size} trading days</div></div>
  <div class="stat"><div class="stat-title">Annual profit</div>
    <div class="stat-value text-lg text-success">${'$'}${"%,.0f".format(totalProfit / years)}/yr</div></div>
  <div class="stat"><div class="stat-title">Peak equity DD</div>
    <div class="stat-value text-lg text-error">${'$'}${"%,.0f".format(maxDD)}</div></div>
  <div class="stat"><div class="stat-title">Worst day</div>
    <div class="stat-value text-lg text-error">${'$'}${"%,.0f".format(worstDay.pnl)}</div>
    <div class="stat-desc">${worstDay.date}</div></div>
</section>

<section class="card bg-base-100 shadow"><div class="card-body">
<h2 class="card-title">Equity curve (top) + underwater drawdown (bottom)</h2>
<p class="text-sm text-base-content/60">1 NQ contract per trade · ${'$'}20/pt · entries flat at start.</p>
<div style="height: 480px;"><canvas id="eqChart"></canvas></div>
</div></section>

<section class="card bg-base-100 shadow"><div class="card-body">
<h2 class="card-title">Per-day P&L (\$)</h2>
<div style="height: 220px;"><canvas id="dayChart"></canvas></div>
</div></section>

<section class="card bg-base-100 shadow"><div class="card-body">
<h2 class="card-title">Top 10 drawdown episodes</h2>
<div class="overflow-x-auto"><table class="table table-zebra table-sm">
<thead><tr><th>#</th><th>Peak date</th><th>Trough date</th><th>Recovery</th>
<th class="text-right">Magnitude \$</th><th class="text-right">Days underwater</th></tr></thead>
<tbody>""")
    topEpisodes.forEachIndexed { i, ep ->
      val rec = ep.recoveryDate?.toString() ?: "<span class=\"badge badge-warning badge-soft\">unrecovered</span>"
      sb.append("<tr><td>${i + 1}</td><td>${ep.peakDate}</td><td>${ep.troughDate}</td><td>$rec</td>")
      sb.append("<td class=\"text-right tabular-nums text-error\">${'$'}${"%,.0f".format(ep.magnitude)}</td>")
      sb.append("<td class=\"text-right tabular-nums\">${ep.days}</td></tr>")
    }
    sb.append("</tbody></table></div></div></section>")

    sb.append("""
<section class="card bg-base-100 shadow"><div class="card-body">
<h2 class="card-title">Top 10 worst single days</h2>
<div class="overflow-x-auto"><table class="table table-zebra table-sm">
<thead><tr><th>#</th><th>Date</th><th class="text-right">P&L \$</th><th class="text-right">Trades</th></tr></thead>
<tbody>""")
    worstDays.forEachIndexed { i, d ->
      sb.append("<tr><td>${i + 1}</td><td>${d.date}</td>")
      sb.append("<td class=\"text-right tabular-nums text-error\">${'$'}${"%,.0f".format(d.pnl)}</td>")
      sb.append("<td class=\"text-right tabular-nums\">${d.trades}</td></tr>")
    }
    sb.append("</tbody></table></div></div></section>")

    sb.append("""
<section class="card bg-base-100 shadow border border-success/40"><div class="card-body">
<h2 class="card-title">Position sizing for funded evaluations</h2>
<p class="text-sm">Largest contract count that satisfies BOTH the daily-loss cap and the total-DD cap, based on this backtest's historical max daily loss and max equity DD. Assumes \$100k account size.</p>
<div class="overflow-x-auto"><table class="table table-zebra">
<thead><tr><th>Eval ruleset</th><th class="text-right">Daily cap</th>
<th class="text-right">Total DD cap</th><th class="text-right">Profit target</th>
<th class="text-right">Max contracts</th>
<th class="text-right">Projected daily-worst</th><th class="text-right">Projected DD</th>
<th class="text-right">Projected annual</th>
<th class="text-right">Months to target</th></tr></thead>
<tbody>""")
    for (s in sizing) {
      val scale = s.unitsX10 / 10.0
      val nqWhole = s.unitsX10 / 10
      val mnqExtra = s.unitsX10 % 10
      val sizeStr = when {
        s.unitsX10 == 0 -> "(none)"
        mnqExtra == 0 -> "${nqWhole} NQ"
        else -> "${nqWhole} NQ + ${mnqExtra} MNQ"
      }
      val projDaily = -worstDay.pnl * scale
      val projDD = maxDD * scale
      val projAnnual = totalProfit * scale / years
      val monthsToTarget = if (projAnnual > 0) (s.rules.target / projAnnual) * 12.0 else Double.POSITIVE_INFINITY
      val sizeCls = when {
        s.unitsX10 == 0 -> "text-error"
        s.unitsX10 >= 20 -> "text-success font-semibold"
        else -> ""
      }
      sb.append("<tr>")
      sb.append("<td>${s.rules.name}</td>")
      sb.append("<td class=\"text-right tabular-nums\">${'$'}${"%,.0f".format(s.rules.dailyLoss)}</td>")
      sb.append("<td class=\"text-right tabular-nums\">${'$'}${"%,.0f".format(s.rules.totalDD)}</td>")
      sb.append("<td class=\"text-right tabular-nums\">${'$'}${"%,.0f".format(s.rules.target)}</td>")
      sb.append("<td class=\"text-right tabular-nums $sizeCls\">$sizeStr</td>")
      sb.append("<td class=\"text-right tabular-nums\">${'$'}${"%,.0f".format(projDaily)}</td>")
      sb.append("<td class=\"text-right tabular-nums\">${'$'}${"%,.0f".format(projDD)}</td>")
      sb.append("<td class=\"text-right tabular-nums text-success\">${'$'}${"%,.0f".format(projAnnual)}/yr</td>")
      val mttStr = if (monthsToTarget.isFinite()) "%.1f mo".format(monthsToTarget) else "—"
      sb.append("<td class=\"text-right tabular-nums\">$mttStr</td>")
      sb.append("</tr>")
    }
    sb.append("</tbody></table></div>")
    sb.append("""<div class="alert alert-warning mt-4"><div class="text-sm">
Backtest worst-day / DD are <em>realized</em> historical numbers. Live trading will eventually exceed them. Real-money sizing should bake in a margin of safety (e.g. half the max contract count shown).</div></div>""")
    sb.append("</div></section>")

    sb.append("""
<script>
const eqCtx = document.getElementById('eqChart');
new Chart(eqCtx, {
  data: {
    labels: [$labels],
    datasets: [
      { type: 'line', label: 'Equity ($)', data: [$balSeries], yAxisID: 'y',
        borderColor: 'rgb(74, 222, 128)', backgroundColor: 'rgba(74, 222, 128, 0.1)',
        borderWidth: 2, pointRadius: 0, fill: true, tension: 0 },
      { type: 'line', label: 'Drawdown ($)', data: [$ddSeries], yAxisID: 'y2',
        borderColor: 'rgb(248, 113, 113)', backgroundColor: 'rgba(248, 113, 113, 0.25)',
        borderWidth: 1, pointRadius: 0, fill: true, tension: 0 },
    ],
  },
  options: {
    responsive: true, maintainAspectRatio: false, interaction: { mode: 'index', intersect: false },
    scales: {
      x: { type: 'time', time: { unit: 'month' }, ticks: { color: '#aaa' } },
      y: { type: 'linear', position: 'left', title: { display: true, text: 'Equity (${'$'})', color: '#aaa' }, ticks: { color: '#aaa' } },
      y2: { type: 'linear', position: 'right', title: { display: true, text: 'Drawdown (${'$'})', color: '#aaa' }, max: 0, ticks: { color: '#aaa' }, grid: { drawOnChartArea: false } },
    },
    plugins: { legend: { labels: { color: '#ddd' } } },
  },
});

const dayCtx = document.getElementById('dayChart');
new Chart(dayCtx, {
  type: 'bar',
  data: {
    labels: [$dayLabels],
    datasets: [{
      label: 'Daily P&L (${'$'})',
      data: [$dayBars],
      backgroundColor: ctx => ctx.raw >= 0 ? 'rgba(74, 222, 128, 0.7)' : 'rgba(248, 113, 113, 0.7)',
      borderWidth: 0,
    }],
  },
  options: {
    responsive: true, maintainAspectRatio: false,
    scales: {
      x: { type: 'time', time: { unit: 'month' }, ticks: { color: '#aaa' } },
      y: { ticks: { color: '#aaa' }, grid: { color: 'rgba(255,255,255,0.05)' } },
    },
    plugins: { legend: { display: false } },
  },
});
</script>
</main></body></html>""")

    File(OUT_HTML).apply { parentFile?.mkdirs(); writeText(sb.toString()) }
  }
}
