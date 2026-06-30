package com.arviman.ta

import com.arviman.ta.timeseries.readCsvBars
import java.io.File
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Eval-aware grid re-ranker.
 *
 * For every config in the standard Rumers grid (same 7-dim space as
 * RumersGridSearch), simulate at 1 NQ contract and compute:
 *   - peak equity drawdown $ (true equity peak-to-trough, not yearly-from-start)
 *   - worst single-day loss $
 *
 * Then, for a specific funded eval rule set, compute the largest
 * NQ-equivalent contract count (in tenths, so MNQ micros count) that
 * still respects both the daily-loss cap and the total-DD cap. With that
 * size, compute projected annual profit and months-to-pass-the-target.
 *
 * Recommend the config with the SHORTEST months-to-target -- the fastest
 * way to pass this specific evaluation.
 *
 * Default target ruleset: the5ers $100k -- $4k daily / $10k total DD /
 * $8k profit target. Edit RULES below for other firms.
 */
object RumersEvalOptimizer {

  private const val NQ_DATA = "sampledata/NQ_5Years_8_11_2024.csv"
  private const val OUT_HTML = ".lavish/rumers-eval-optimizer.html"

  // Target eval rules. Edit here for different firms/account sizes.
  private val RULES = RumersEquity.EvalRules(
    name = "the5ers $100k cTrader",
    dailyLoss = 4_000.0,
    totalDD = 10_000.0,
    target = 8_000.0,
  )

  // Same grid space as RumersGridSearch + rejection-wick gate.
  private val lookbackGrid  = listOf(30, 45, 60, 90, 120)
  private val bufferGrid    = listOf(5.0, 10.0, 20.0)
  private val rrGrid        = listOf(1.0, 1.5, 2.0)
  private val tpGrid        = listOf(0.5, 0.75, 1.0)
  private val skipGrid      = listOf(0.0, 0.2)
  private val pyrGrid       = listOf(1, 2, 4)
  private val gateGrid      = listOf(true, false)
  private val rejectionGrid = listOf(false, true)

  internal data class EvalRow(
    val cfg: RumersTest.Config,
    val trades: Int,
    val pf: Double,
    val winRate: Double,
    val totalProfit: Double,
    val years: Double,
    val peakEquityDD: Double,
    val worstDay: Double,
    val maxUnitsX10: Int,            // max position size in tenths of an NQ
    val projAnnual: Double,
    val monthsToTarget: Double,
  )

  @JvmStatic
  fun main(args: Array<String>) {
    println("=== Rumers eval-aware optimizer ===")
    println("Target: ${RULES.name}  (daily \$${"%,.0f".format(RULES.dailyLoss)}, " +
        "DD \$${"%,.0f".format(RULES.totalDD)}, target \$${"%,.0f".format(RULES.target)})\n")

    println("Loading data...")
    val bars5m = readCsvBars(NQ_DATA)
    val bars15m = RumersTest.aggregateTo15m(bars5m)
    val rthHL = RumersTest.buildRthDailyHL(bars5m)
    val years = (bars15m.last().openTime.epochSecond - bars15m.first().openTime.epochSecond) /
        (365.25 * 24 * 3600.0)

    val totalConfigs = lookbackGrid.size * bufferGrid.size * rrGrid.size *
        tpGrid.size * skipGrid.size * pyrGrid.size * gateGrid.size * rejectionGrid.size
    println("Sweeping $totalConfigs configs (computing equity DD per config)...\n")

    val rows = mutableListOf<EvalRow>()
    val startMs = System.currentTimeMillis()
    var done = 0
    for (lb in lookbackGrid) for (buf in bufferGrid) for (rr in rrGrid)
    for (tp in tpGrid) for (sk in skipGrid) for (pyr in pyrGrid) for (gate in gateGrid) for (rej in rejectionGrid) {
      val cfg = RumersTest.Config(
        lookback = lb, bufferPts = buf, minRR = rr, tpFrac = tp,
        skipBand = sk, is24x7 = false,
        pointValue = 20.0, contracts = 1.0,
        pyramidingLimit = pyr, requireProfitToPyramid = gate,
        requireRejection = rej,
      )
      val result = RumersTest.runStrategy(bars15m, rthHL, cfg)
      val ev = evaluate(cfg, result, years)
      if (ev != null) rows += ev
      done++
      if (done % 200 == 0) println("  ... $done / $totalConfigs (${(System.currentTimeMillis() - startMs) / 1000}s)")
    }
    println("\nDone. ${rows.size} qualified configs (≥30 trades).\n")

    // Filter: min annual return > 0 AND able to fit at least 1 MNQ.
    val feasible = rows.filter { it.maxUnitsX10 >= 1 && it.projAnnual > 0 }
      .sortedBy { it.monthsToTarget }
    println("=== TOP 20 by FASTEST months-to-target ===")
    printTop(feasible.take(20))

    val bestFast = feasible.firstOrNull()

    println("\n=== TOP 20 by HIGHEST projected annual at max feasible size ===")
    val byProfit = feasible.sortedByDescending { it.projAnnual }
    printTop(byProfit.take(20))

    println("\n=== TOP 20 by LOWEST peak equity DD (1 NQ) with positive return ===")
    val byDD = rows.filter { it.totalProfit > 0 }
      .sortedBy { it.peakEquityDD }
    printTop(byDD.take(20))

    if (bestFast != null) {
      println("\n=== RECOMMENDED for ${RULES.name} ===")
      println("  ${cfgStr(bestFast.cfg)}")
      println("  1 NQ stats: PF=%.2f, trades=%d, win=%.1f%%, peakDD=\$%,.0f, worstDay=\$%,.0f"
        .format(bestFast.pf, bestFast.trades, bestFast.winRate, bestFast.peakEquityDD, bestFast.worstDay))
      val nq = bestFast.maxUnitsX10 / 10
      val mnq = bestFast.maxUnitsX10 % 10
      val sizeStr = if (mnq == 0) "${nq} NQ" else "${nq} NQ + ${mnq} MNQ"
      println("  Max size for this eval: $sizeStr")
      println("  Projected: \$%,.0f / yr  →  target in %.1f months".format(bestFast.projAnnual, bestFast.monthsToTarget))
    }

    writeHtml(rows, bestFast, feasible.take(50))
    println("\nWrote $OUT_HTML")
  }

  private fun evaluate(cfg: RumersTest.Config, result: RumersTest.Result, years: Double): EvalRow? {
    if (result.trades.size < 30) return null
    val byDay = result.trades.groupBy { it.date }.toSortedMap()
      .map { (date, ts) -> date to ts.sumOf { it.dollarPnL } }
    if (byDay.isEmpty()) return null

    // Running equity + peak DD.
    var bal = 0.0
    var peak = 0.0
    var maxDD = 0.0
    var worstDay = 0.0
    for ((_, pnl) in byDay) {
      bal += pnl
      if (bal > peak) peak = bal
      val dd = peak - bal
      if (dd > maxDD) maxDD = dd
      if (pnl < worstDay) worstDay = pnl
    }
    if (maxDD <= 0.0 || worstDay >= 0.0) return null

    // Max units (in tenths of NQ) that fit BOTH eval caps.
    var maxUnits = 0
    for (u in 1..500) {
      val scale = u / 10.0
      if (scale * maxDD <= RULES.totalDD && scale * (-worstDay) <= RULES.dailyLoss) maxUnits = u
    }
    val scale = maxUnits / 10.0
    val projAnnual = result.total * scale / years
    val monthsToTarget = if (projAnnual > 0) RULES.target / projAnnual * 12.0 else Double.POSITIVE_INFINITY

    return EvalRow(
      cfg = cfg, trades = result.trades.size, pf = result.profitFactor, winRate = result.winRate,
      totalProfit = result.total, years = years,
      peakEquityDD = maxDD, worstDay = worstDay,
      maxUnitsX10 = maxUnits, projAnnual = projAnnual,
      monthsToTarget = monthsToTarget,
    )
  }

  private fun printTop(rows: List<EvalRow>) {
    println("%-7s %-6s %-5s %-6s %-5s %-3s %-5s %-4s | %5s %5s %6s %10s %10s %-12s %10s %7s".format(
      "lb", "buf", "rr", "tpFrac", "skip", "pyr", "gate", "rej",
      "#t", "win%", "PF", "peakDD", "worstDay", "size", "ann$", "mo→tgt"))
    println("-".repeat(133))
    for (r in rows) {
      val c = r.cfg
      val nq = r.maxUnitsX10 / 10
      val mnq = r.maxUnitsX10 % 10
      val sz = if (mnq == 0) "${nq} NQ" else "${nq}NQ+${mnq}MNQ"
      val mo = if (r.monthsToTarget.isFinite()) "%.1f".format(r.monthsToTarget) else "—"
      println("%-7d %-6.0f %-5.1f %-6.2f %-5.2f %-3d %-5s %-4s | %5d %5.1f %6.2f %10s %10s %-12s %10s %7s".format(
        c.lookback, c.bufferPts, c.minRR, c.tpFrac, c.skipBand,
        c.pyramidingLimit, if (c.requireProfitToPyramid) "on" else "off",
        if (c.requireRejection) "on" else "off",
        r.trades, r.winRate, r.pf,
        dollarFmt(r.peakEquityDD), dollarFmt(r.worstDay),
        sz, dollarFmt(r.projAnnual), mo))
    }
  }

  private fun cfgStr(c: RumersTest.Config): String =
    "lookback=${c.lookback} buffer=${c.bufferPts} minRR=%.1f tpFrac=%.2f skipBand=%.2f pyramid=${c.pyramidingLimit} profitGate=${c.requireProfitToPyramid} rejection=${c.requireRejection}"
      .format(c.minRR, c.tpFrac, c.skipBand)

  private fun dollarFmt(v: Double) =
    if (v >= 0) "$%,.0f".format(v) else "-$%,.0f".format(-v)

  private fun writeHtml(allRows: List<EvalRow>, best: EvalRow?, topFeasible: List<EvalRow>) {
    val sb = StringBuilder()
    sb.append("""<!doctype html><html lang="en" data-theme="synthwave"><head>
<meta charset="UTF-8"><title>Rumers eval optimizer</title>
<link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/daisyui@5.5.19/daisyui.css">
<link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/daisyui@5.5.19/themes.css">
<script src="https://cdn.jsdelivr.net/npm/@tailwindcss/browser@4.2.4/dist/index.global.js"></script>
</head><body class="min-h-screen bg-base-300">
<div class="navbar bg-base-100 shadow-lg"><div class="navbar-start">
<span class="text-2xl font-bold">🎯 Rumers eval optimizer</span>
<span class="badge badge-soft badge-accent ml-3">${RULES.name}</span></div></div>
<main class="mx-auto max-w-7xl p-6 lg:p-10 space-y-6">""")

    if (best != null) {
      val nq = best.maxUnitsX10 / 10
      val mnq = best.maxUnitsX10 % 10
      val sz = if (mnq == 0) "${nq} NQ" else "${nq} NQ + ${mnq} MNQ"
      sb.append("""
<section class="card bg-success/15 border border-success/40 shadow"><div class="card-body">
<h2 class="card-title">Recommended config (fastest pass)</h2>
<p class="text-sm">Of ${allRows.size} simulated configs, the one with shortest months-to-${'$'}${"%,.0f".format(RULES.target)} target at max-feasible size.</p>
<div class="mt-2 font-mono text-sm">${cfgStr(best.cfg)}</div>
<div class="stats stats-vertical lg:stats-horizontal shadow mt-4 w-full">
  <div class="stat"><div class="stat-title">Max size</div><div class="stat-value text-lg">$sz</div></div>
  <div class="stat"><div class="stat-title">Months to target</div><div class="stat-value text-lg text-success">${"%.1f".format(best.monthsToTarget)}</div></div>
  <div class="stat"><div class="stat-title">Projected annual</div><div class="stat-value text-lg">${'$'}${"%,.0f".format(best.projAnnual)}</div></div>
  <div class="stat"><div class="stat-title">Peak DD (1 NQ)</div><div class="stat-value text-lg">${'$'}${"%,.0f".format(best.peakEquityDD)}</div></div>
  <div class="stat"><div class="stat-title">Worst day (1 NQ)</div><div class="stat-value text-lg">${'$'}${"%,.0f".format(best.worstDay)}</div></div>
  <div class="stat"><div class="stat-title">PF / trades / win</div><div class="stat-value text-lg">${"%.2f".format(best.pf)} / ${best.trades} / ${"%.1f".format(best.winRate)}%</div></div>
</div></div></section>""")
    }

    sb.append("""
<section class="card bg-base-100 shadow"><div class="card-body">
<h2 class="card-title">Top 50 by fastest months-to-target</h2>
<div class="overflow-x-auto"><table class="table table-zebra table-sm">
<thead><tr><th>#</th><th>lb</th><th>buf</th><th>minRR</th><th>tpFrac</th><th>skip</th><th>pyr</th><th>gate</th><th>rej</th>
<th class="text-right">trades</th><th class="text-right">win%</th><th class="text-right">PF</th>
<th class="text-right">peakDD\$</th><th class="text-right">worstDay\$</th>
<th>size</th><th class="text-right">ann\$</th><th class="text-right">mo→target</th></tr></thead>
<tbody>""")
    topFeasible.forEachIndexed { i, r ->
      val c = r.cfg
      val nq = r.maxUnitsX10 / 10
      val mnq = r.maxUnitsX10 % 10
      val sz = if (mnq == 0) "${nq} NQ" else "${nq}NQ+${mnq}MNQ"
      val rowCls = if (best != null && r === best) "bg-success/20 font-semibold" else ""
      val pfCls = if (r.pf >= 1.3) "text-success" else if (r.pf >= 1.0) "text-warning" else "text-error"
      sb.append("<tr class=\"$rowCls\">")
      sb.append("<td>${i + 1}</td>")
      sb.append("<td>${c.lookback}</td>")
      sb.append("<td>${"%.0f".format(c.bufferPts)}</td>")
      sb.append("<td>${"%.1f".format(c.minRR)}</td>")
      sb.append("<td>${"%.2f".format(c.tpFrac)}</td>")
      sb.append("<td>${"%.2f".format(c.skipBand)}</td>")
      sb.append("<td>${c.pyramidingLimit}</td>")
      sb.append("<td>${if (c.requireProfitToPyramid) "on" else "off"}</td>")
      sb.append("<td>${if (c.requireRejection) "on" else "off"}</td>")
      sb.append("<td class=\"text-right tabular-nums\">${r.trades}</td>")
      sb.append("<td class=\"text-right tabular-nums\">${"%.1f".format(r.winRate)}</td>")
      sb.append("<td class=\"text-right tabular-nums $pfCls\">${"%.2f".format(r.pf)}</td>")
      sb.append("<td class=\"text-right tabular-nums\">${'$'}${"%,.0f".format(r.peakEquityDD)}</td>")
      sb.append("<td class=\"text-right tabular-nums text-error\">${'$'}${"%,.0f".format(r.worstDay)}</td>")
      sb.append("<td>$sz</td>")
      sb.append("<td class=\"text-right tabular-nums text-success\">${'$'}${"%,.0f".format(r.projAnnual)}</td>")
      sb.append("<td class=\"text-right tabular-nums\">${"%.1f".format(r.monthsToTarget)}</td>")
      sb.append("</tr>")
    }
    sb.append("</tbody></table></div></div></section>")

    sb.append("</main></body></html>")
    File(OUT_HTML).apply { parentFile?.mkdirs(); writeText(sb.toString()) }
  }
}
