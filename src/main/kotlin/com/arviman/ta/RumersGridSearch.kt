package com.arviman.ta

import com.arviman.ta.timeseries.readCsvBars
import java.io.File

/**
 * Grid search over Rumers parameters on NQ M15.
 *
 * 7-dim sweep:
 *   lookback        ∈ {30, 45, 60, 90, 120}
 *   bufferPts       ∈ {5, 10, 20}
 *   minRR           ∈ {1.0, 1.5, 2.0}
 *   tpFrac          ∈ {0.5, 0.75, 1.0}
 *   skipBand        ∈ {0.0, 0.2}
 *   pyramidingLimit ∈ {1, 2, 4}
 *   profitGate      ∈ {true, false}
 *
 * 5 × 3 × 3 × 3 × 2 × 3 × 2 = 1,620 configs.
 *
 * Ranking (printed top 20 of each):
 *   - by PF
 *   - by annualReturn$
 *   - by Calmar = annualReturn$ / maxYearlyDD$
 *   - by Composite = PF × log10(1 + trades) − (maxYearlyDD$ / 5000)
 *     (rewards edge × sample size, penalizes DD)
 *
 * Recommends a single "best deployable" config: the highest Composite
 * config with ≥150 trades. Writes a sortable HTML report at
 * .lavish/rumers-grid.html with every config and its score.
 */
object RumersGridSearch {

  private const val NQ_DATA = "sampledata/NQ_5Years_8_11_2024.csv"
  private const val OUT_HTML = ".lavish/rumers-grid.html"

  private val lookbackGrid = listOf(30, 45, 60, 90, 120)
  private val bufferGrid   = listOf(5.0, 10.0, 20.0)
  private val rrGrid       = listOf(1.0, 1.5, 2.0)
  private val tpGrid       = listOf(0.5, 0.75, 1.0)
  private val skipGrid     = listOf(0.0, 0.2)
  private val pyrGrid      = listOf(1, 2, 4)
  private val gateGrid     = listOf(true, false)

  internal data class GridRow(
    val cfg: RumersTest.Config,
    val r: RumersTest.Result,
    val years: Double,
  ) {
    val annualReturn: Double get() = r.total / years
    val calmar: Double get() = if (r.maxYearlyDD > 0) annualReturn / r.maxYearlyDD else 0.0
    val composite: Double get() {
      val sampleBonus = Math.log10((1 + r.trades.size).toDouble())
      val ddPenalty = r.maxYearlyDD / 5000.0
      return r.profitFactor * sampleBonus - ddPenalty
    }
  }

  @JvmStatic
  fun main(args: Array<String>) {
    println("=== Rumers grid search (NQ M15, 5y) ===")
    println("Loading $NQ_DATA ...")
    val bars5m = readCsvBars(NQ_DATA)
    val bars15m = RumersTest.aggregateTo15m(bars5m)
    val rthHL = RumersTest.buildRthDailyHL(bars5m)
    val years = (bars15m.last().openTime.epochSecond - bars15m.first().openTime.epochSecond) /
        (365.25 * 24 * 3600.0)
    println("Loaded ${bars15m.size} 15m bars, ${rthHL.size} RTH days, span=%.1fy.".format(years))

    val totalConfigs = lookbackGrid.size * bufferGrid.size * rrGrid.size *
        tpGrid.size * skipGrid.size * pyrGrid.size * gateGrid.size
    println("Sweeping $totalConfigs configs...\n")

    val rows = mutableListOf<GridRow>()
    var done = 0
    val startMs = System.currentTimeMillis()
    for (lb in lookbackGrid) for (buf in bufferGrid) for (rr in rrGrid)
    for (tp in tpGrid) for (sk in skipGrid) for (pyr in pyrGrid) for (gate in gateGrid) {
      val cfg = RumersTest.Config(
        lookback = lb, bufferPts = buf, minRR = rr, tpFrac = tp,
        skipBand = sk, is24x7 = false,
        pointValue = 20.0, contracts = 1.0,
        pyramidingLimit = pyr, requireProfitToPyramid = gate,
      )
      val result = RumersTest.runStrategy(bars15m, rthHL, cfg)
      rows += GridRow(cfg, result, years)
      done++
      if (done % 200 == 0) {
        val ms = System.currentTimeMillis() - startMs
        println("  ... $done / $totalConfigs configs (${ms / 1000}s)")
      }
    }
    val elapsedSec = (System.currentTimeMillis() - startMs) / 1000
    println("\nDone. ${rows.size} configs in ${elapsedSec}s.\n")

    val MIN_TRADES = 150
    val qualified = rows.filter { it.r.trades.size >= MIN_TRADES }
    println("=== TOP 20 by PF (min $MIN_TRADES trades) ===")
    printTopN(qualified.sortedByDescending { it.r.profitFactor }.take(20))

    println("\n=== TOP 20 by Annual Return $ (min $MIN_TRADES trades) ===")
    printTopN(qualified.sortedByDescending { it.annualReturn }.take(20))

    println("\n=== TOP 20 by Calmar (annReturn/maxDD, min $MIN_TRADES trades) ===")
    printTopN(qualified.sortedByDescending { it.calmar }.take(20))

    println("\n=== TOP 20 by Composite score (min $MIN_TRADES trades) ===")
    val byComposite = qualified.sortedByDescending { it.composite }
    printTopN(byComposite.take(20))

    val best = byComposite.firstOrNull()
    if (best != null) {
      println("\n=== RECOMMENDED for deployment (best Composite, ≥$MIN_TRADES trades) ===")
      println("  ${cfgStr(best.cfg)}")
      println("  → PF=%.2f  annReturn=%s/yr  maxDD=%s  trades=%d  win=%.1f%%  composite=%.2f"
        .format(best.r.profitFactor, dollarFmt(best.annualReturn), dollarFmt(best.r.maxYearlyDD),
                best.r.trades.size, best.r.winRate, best.composite))
    }

    writeHtml(rows, best, MIN_TRADES)
    println("\nWrote $OUT_HTML")
  }

  private fun printTopN(rows: List<GridRow>) {
    println("%-7s %-6s %-5s %-6s %-5s %-3s %-5s | %5s %5s %6s %12s %12s %7s %8s".format(
      "lb", "buf", "rr", "tpFrac", "skip", "pyr", "gate",
      "#t", "win%", "PF", "annRet$", "maxDD$", "Calmar", "Comp"))
    println("-".repeat(115))
    for (row in rows) {
      val c = row.cfg
      println("%-7d %-6.0f %-5.1f %-6.2f %-5.2f %-3d %-5s | %5d %5.1f %6.2f %12s %12s %7.2f %8.2f".format(
        c.lookback, c.bufferPts, c.minRR, c.tpFrac, c.skipBand,
        c.pyramidingLimit, if (c.requireProfitToPyramid) "on" else "off",
        row.r.trades.size, row.r.winRate, row.r.profitFactor,
        dollarFmt(row.annualReturn), dollarFmt(row.r.maxYearlyDD),
        row.calmar, row.composite))
    }
  }

  private fun cfgStr(c: RumersTest.Config): String =
    "lookback=${c.lookback} buffer=${c.bufferPts} minRR=%.1f tpFrac=%.2f skipBand=%.2f pyramid=${c.pyramidingLimit} profitGate=${c.requireProfitToPyramid}"
      .format(c.minRR, c.tpFrac, c.skipBand)

  private fun dollarFmt(v: Double) =
    if (v >= 0) "$%,.0f".format(v) else "-$%,.0f".format(-v)

  private fun writeHtml(rows: List<GridRow>, best: GridRow?, minTrades: Int) {
    val sb = StringBuilder()
    sb.append("""<!doctype html><html lang="en" data-theme="synthwave"><head>
<meta charset="UTF-8"><title>Rumers grid search</title>
<link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/daisyui@5.5.19/daisyui.css">
<link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/daisyui@5.5.19/themes.css">
<script src="https://cdn.jsdelivr.net/npm/@tailwindcss/browser@4.2.4/dist/index.global.js"></script>
<style>
  th { cursor: pointer; user-select: none; }
  th.sort-asc::after { content: ' ▲'; }
  th.sort-desc::after { content: ' ▼'; }
</style>
</head><body class="min-h-screen bg-base-300">
<div class="navbar bg-base-100 shadow-lg">
  <div class="navbar-start"><span class="text-2xl font-bold">🔬 Rumers grid search</span>
  <span class="badge badge-soft badge-accent ml-3">NQ M15 · 5y · ${rows.size} configs</span></div>
</div>
<main class="mx-auto max-w-7xl p-6 lg:p-10 space-y-6">""")

    if (best != null) {
      sb.append("""
<section class="card bg-success/15 border border-success/40 shadow"><div class="card-body">
<h2 class="card-title">Recommended for deployment</h2>
<p class="text-sm">Highest Composite score with ≥$minTrades trades. Composite = PF × log10(1+trades) − maxDD/\$5,000.</p>
<div class="mt-2 font-mono text-sm">${cfgStr(best.cfg)}</div>
<div class="stats stats-horizontal shadow mt-4">
  <div class="stat"><div class="stat-title">PF</div><div class="stat-value text-lg">${"%.2f".format(best.r.profitFactor)}</div></div>
  <div class="stat"><div class="stat-title">Annual return</div><div class="stat-value text-lg">${dollarFmt(best.annualReturn)}/yr</div></div>
  <div class="stat"><div class="stat-title">Max yearly DD</div><div class="stat-value text-lg">${dollarFmt(best.r.maxYearlyDD)}</div></div>
  <div class="stat"><div class="stat-title">Trades</div><div class="stat-value text-lg">${best.r.trades.size}</div></div>
  <div class="stat"><div class="stat-title">Win rate</div><div class="stat-value text-lg">${"%.1f".format(best.r.winRate)}%</div></div>
</div></div></section>""")
    }

    sb.append("""
<section class="card bg-base-100 shadow"><div class="card-body">
<h2 class="card-title">All ${rows.size} configs</h2>
<p class="text-sm text-base-content/60">Click any column header to sort. Rows with &lt; $minTrades trades shown dimmed.</p>
<div class="overflow-x-auto"><table id="grid" class="table table-zebra table-sm">
<thead><tr>
<th data-sort="num">lookback</th><th data-sort="num">buffer</th><th data-sort="num">minRR</th>
<th data-sort="num">tpFrac</th><th data-sort="num">skipBand</th>
<th data-sort="num">pyramid</th><th data-sort="text">profitGate</th>
<th class="text-right" data-sort="num">trades</th>
<th class="text-right" data-sort="num">win %</th>
<th class="text-right sort-desc" data-sort="num">PF</th>
<th class="text-right" data-sort="num">annRet$</th>
<th class="text-right" data-sort="num">maxYrDD$</th>
<th class="text-right" data-sort="num">Calmar</th>
<th class="text-right" data-sort="num">Composite</th>
</tr></thead><tbody>""")

    val sorted = rows.sortedByDescending { it.r.profitFactor }
    for (row in sorted) {
      val c = row.cfg
      val dim = if (row.r.trades.size < minTrades) "opacity-40" else ""
      val isBest = best != null && row === best
      val rowCls = if (isBest) "bg-success/20 font-semibold" else dim
      val pfCls = if (row.r.profitFactor >= 1.3) "text-success font-semibold"
                  else if (row.r.profitFactor >= 1.0) "text-warning"
                  else "text-error"
      val retCls = if (row.annualReturn > 0) "text-success" else "text-error"
      sb.append("<tr class=\"$rowCls\">")
      sb.append("<td>${c.lookback}</td>")
      sb.append("<td>${"%.0f".format(c.bufferPts)}</td>")
      sb.append("<td>${"%.1f".format(c.minRR)}</td>")
      sb.append("<td>${"%.2f".format(c.tpFrac)}</td>")
      sb.append("<td>${"%.2f".format(c.skipBand)}</td>")
      sb.append("<td>${c.pyramidingLimit}</td>")
      sb.append("<td>${if (c.requireProfitToPyramid) "on" else "off"}</td>")
      sb.append("<td class=\"text-right tabular-nums\">${row.r.trades.size}</td>")
      sb.append("<td class=\"text-right tabular-nums\">${"%.1f".format(row.r.winRate)}</td>")
      sb.append("<td class=\"text-right tabular-nums $pfCls\">${"%.2f".format(row.r.profitFactor)}</td>")
      sb.append("<td class=\"text-right tabular-nums $retCls\">${dollarFmt(row.annualReturn)}</td>")
      sb.append("<td class=\"text-right tabular-nums\">${dollarFmt(row.r.maxYearlyDD)}</td>")
      sb.append("<td class=\"text-right tabular-nums\">${"%.2f".format(row.calmar)}</td>")
      sb.append("<td class=\"text-right tabular-nums\">${"%.2f".format(row.composite)}</td>")
      sb.append("</tr>\n")
    }
    sb.append("</tbody></table></div></div></section>")

    // Light JS for column sort.
    sb.append("""
<script>
(function() {
  const tbl = document.getElementById('grid');
  const ths = tbl.querySelectorAll('th');
  ths.forEach((th, i) => th.addEventListener('click', () => {
    const dir = th.classList.contains('sort-desc') ? 'asc' : 'desc';
    ths.forEach(h => h.classList.remove('sort-asc','sort-desc'));
    th.classList.add(dir === 'asc' ? 'sort-asc' : 'sort-desc');
    const type = th.dataset.sort;
    const rows = Array.from(tbl.tBodies[0].rows);
    rows.sort((a, b) => {
      const av = a.cells[i].innerText.trim();
      const bv = b.cells[i].innerText.trim();
      let cmp;
      if (type === 'num') {
        const an = parseFloat(av.replace(/[$,]/g,''));
        const bn = parseFloat(bv.replace(/[$,]/g,''));
        cmp = (isNaN(an) ? -Infinity : an) - (isNaN(bn) ? -Infinity : bn);
      } else cmp = av.localeCompare(bv);
      return dir === 'asc' ? cmp : -cmp;
    });
    rows.forEach(r => tbl.tBodies[0].appendChild(r));
  }));
})();
</script>
</main></body></html>""")

    File(OUT_HTML).apply {
      parentFile?.mkdirs()
      writeText(sb.toString())
    }
  }
}
