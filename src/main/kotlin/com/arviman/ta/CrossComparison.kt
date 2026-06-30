package com.arviman.ta

import com.arviman.ta.backtest.BackTestReport
import com.arviman.ta.backtest.BackTestSpec
import com.arviman.ta.backtest.BackTester
import com.arviman.ta.backtest.TpSlTier
import com.arviman.ta.backtest.TradeType
import com.arviman.ta.indicators.volatilityStop
import com.arviman.ta.strategy.SqueezeParams
import com.arviman.ta.strategy.SqzEntryMode
import com.arviman.ta.strategy.SqzExitMode
import com.arviman.ta.strategy.makeSqueezeMomentumStrategy
import com.arviman.ta.timeseries.Bar
import com.arviman.ta.timeseries.TimeFrame
import com.arviman.ta.timeseries.readCsvBars

/**
 * Cross-strategy / cross-asset comparison.
 *
 * Runs Rumers (prev-day range fade) and SqueezeMomentum (LazyBear squeeze)
 * on both NQ M15 and ETH H1 and prints a normalized metrics table.
 *
 * Metric units:
 *   - winRate, avgWinPct, avgLossPct, profitFactor — directly comparable.
 *   - annualReturnPct — sum of per-trade % returns ÷ years.
 *     Rumers: per-trade return = pointsPnL / entryPrice (1 unit traded).
 *     Squeeze: BackTester native profitability (return on $13k account).
 *   - maxDDPct — best-effort. Rumers uses maxYrDD$ ÷ avgTradeNotional$ as a
 *     proxy; Squeeze uses BackTester maxDrawDown directly.
 *
 * Absolute $ are NOT directly comparable across strategies because Rumers
 * trades 1 unit per signal while Squeeze sizes off a $13k account with
 * 2% bet and 6-position pyramid. PF and per-trade % are the fair signals.
 */
object CrossComparison {

  private const val NQ_DATA = "sampledata/NQ_5Years_8_11_2024.csv"
  private const val ETH_DATA = "sampledata/ETHUSD_1h_Bitstamp.csv"

  // Rumers params held constant across assets: sweep-B best from RumersTest.
  private const val LOOKBACK = 90
  private const val MIN_RR = 1.5
  private const val TP_FRAC = 1.0
  private const val SKIP_BAND = 0.0
  // Buffer scaled per asset (10 NQ pts ≈ 0.05% of price; symmetric for ETH).
  private const val NQ_BUFFER = 10.0
  private const val ETH_BUFFER = 1.5    // ~0.05% of $3000 ETH
  // Pyramiding levelled across both strategies for a fairer comparison.
  // Both: max 4 same-direction concurrent positions, profit-gated.
  private const val PYRAMID = 4

  // Squeeze params from SqueezeMomentumTest.kt (ETH-tuned baseline).
  private val SQZ_PARAMS = SqueezeParams(
    bbLength = 10, bbMult = 2.1, kcLength = 20, kcMult = 0.1,
    useHtf = true, htfLength = 47,
    entryMode = SqzEntryMode.ZeroCross, exitMode = SqzExitMode.Color,
  )
  // ETH H1 SL was 50 pts (~1.5% of $3k). Scale per asset.
  private const val ETH_SL_ABS = 50.0
  private const val NQ_SL_ABS = 300.0     // ~1.5% of $20k NQ
  private const val TP_RATIO = 1.0

  data class Row(
    val strategy: String,
    val asset: String,
    val tf: String,
    val years: Double,
    val trades: Int,
    val winRatePct: Double,
    val avgWinPct: Double,
    val avgLossPct: Double,
    val profitFactor: Double,
    val annualReturnPct: Double,
    val maxDDPct: Double,
    val totalNetDollars: Double,    // raw, instrument-native — see header note
    val annualNetDollars: Double,
  )

  @JvmStatic
  fun main(args: Array<String>) {
    println("=== Cross-strategy comparison: Rumers vs SqueezeMomentum, NQ M15 vs ETH H1 ===\n")

    val rows = mutableListOf<Row>()

    // ── Rumers / NQ M15
    println("Loading NQ 5m, aggregating to M15, building daily HL...")
    val nqBars5m = readCsvBars(NQ_DATA)
    val nqBars15m = RumersTest.aggregateTo15m(nqBars5m)
    val nqRthHL = RumersTest.buildRthDailyHL(nqBars5m)
    val nqYears = yearsBetween(nqBars15m.first().openTime.epochSecond, nqBars15m.last().openTime.epochSecond)
    val rumersNqCfg = RumersTest.Config(
      lookback = LOOKBACK, bufferPts = NQ_BUFFER, minRR = MIN_RR,
      tpFrac = TP_FRAC, skipBand = SKIP_BAND, is24x7 = false,
      pointValue = 20.0, contracts = 1.0,
      pyramidingLimit = PYRAMID, requireProfitToPyramid = true,
    )
    val rumersNq = RumersTest.runStrategy(nqBars15m, nqRthHL, rumersNqCfg)
    rows += rumersRow("Rumers", "NQ", "M15", nqYears, rumersNq, rumersNqCfg)

    // ── Rumers / ETH H1
    println("Loading ETH 1H, building 24/7 daily HL...")
    val ethBars = readCsvBars(ETH_DATA)
    val ethDailyHL = RumersTest.buildDailyHL(
      ethBars, RumersTest.ALWAYS_ON_GATES.dailyHLFirstMin, RumersTest.ALWAYS_ON_GATES.dailyHLLastMin)
    val ethYears = yearsBetween(ethBars.first().openTime.epochSecond, ethBars.last().openTime.epochSecond)
    val rumersEthCfg = RumersTest.Config(
      lookback = LOOKBACK, bufferPts = ETH_BUFFER, minRR = MIN_RR,
      tpFrac = TP_FRAC, skipBand = SKIP_BAND, is24x7 = true,
      pointValue = 1.0, contracts = 1.0,
      pyramidingLimit = PYRAMID, requireProfitToPyramid = true,
    )
    val rumersEth = RumersTest.runStrategy(ethBars, ethDailyHL, rumersEthCfg)
    rows += rumersRow("Rumers", "ETH", "H1", ethYears, rumersEth, rumersEthCfg)

    // ── Squeeze / NQ M15
    println("Running Squeeze on NQ M15 (HTF SMA on H1, SL=$NQ_SL_ABS pts)...")
    val sqzNq = runSqueeze(
      bars = nqBars5m,
      runTf = TimeFrame.M15,
      slAbs = NQ_SL_ABS,
    )
    val sqzNqYears = yearsBetween(nqBars5m.first().openTime.epochSecond, nqBars5m.last().openTime.epochSecond)
    rows += squeezeRow("Squeeze", "NQ", "M15", sqzNqYears, sqzNq)

    // ── Squeeze / ETH H1
    println("Running Squeeze on ETH H1 (HTF SMA on H1, SL=$ETH_SL_ABS pts)...")
    val sqzEth = runSqueeze(
      bars = ethBars,
      runTf = TimeFrame.H1,
      slAbs = ETH_SL_ABS,
    )
    val sqzEthYears = yearsBetween(ethBars.first().openTime.epochSecond, ethBars.last().openTime.epochSecond)
    rows += squeezeRow("Squeeze", "ETH", "H1", sqzEthYears, sqzEth)

    println()
    printTable(rows)
    writeHtml(rows)
    println("\nWrote .lavish/cross-comparison.html")
  }

  // ───────────────────────────────────────────────────────────────────────
  // Squeeze runner (long+short combined)
  // ───────────────────────────────────────────────────────────────────────

  private fun runSqueeze(bars: List<Bar>, runTf: TimeFrame, slAbs: Double): BackTestReport {
    // Mirror SqueezeMomentumTest: separate long and short runs, then we'll
    // pick the long side as the headline (it's the more comparable case to
    // Rumers' bidirectional logic). Short results are dropped for brevity;
    // see SqueezeMomentumTest for the full long/short sweep.
    val tier = TpSlTier(
      quantityFraction = 1.0,
      stopLossAbs = slAbs,
      takeProfitAbs = slAbs * TP_RATIO,
    )
    val spec = BackTestSpec(
      tradeType = TradeType.LONG,
      runTimeFrame = runTf,
      trailingStops = false,
      pyramidingLimit = PYRAMID,
      startingBalance = 13_000.0,
      betSize = 0.02,
      feePerTrade = 0.0004,
      inputBars = bars,
      strategyFactory = { sm -> makeSqueezeMomentumStrategy(sm.h1, SQZ_PARAMS, longSide = true) },
      stopLoss = { sm -> sm.h1.volatilityStop(length = 14, multiplier = 2.0) },
      entryLadder = listOf(tier),
      fixedPositionFraction = 0.15,
    )
    return BackTester.run(spec)
  }

  // ───────────────────────────────────────────────────────────────────────
  // Row builders
  // ───────────────────────────────────────────────────────────────────────

  private fun rumersRow(strategy: String, asset: String, tf: String, years: Double, r: RumersTest.Result, cfg: RumersTest.Config): Row {
    val trades = r.trades.size
    val perTradePcts = r.trades.map { (it.exit - it.entry) * it.side / it.entry * 100.0 }
    val wins = perTradePcts.filter { it > 0 }
    val losers = perTradePcts.filter { it <= 0 }
    val avgWinPct = if (wins.isNotEmpty()) wins.average() else 0.0
    val avgLossPct = if (losers.isNotEmpty()) losers.average() else 0.0
    val totalPct = perTradePcts.sum()
    // Per-trade notional $ = entry × pointValue × contracts. maxYearlyDD is in dollars.
    val avgEntryNotional = r.trades.map { it.entry * cfg.pointValue * cfg.contracts }.average()
    val maxDDPct = if (avgEntryNotional > 0) r.maxYearlyDD / avgEntryNotional * 100.0 else 0.0
    return Row(
      strategy = strategy,
      asset = asset, tf = tf, years = years, trades = trades,
      winRatePct = r.winRate,
      avgWinPct = avgWinPct,
      avgLossPct = avgLossPct,
      profitFactor = r.profitFactor,
      annualReturnPct = totalPct / years,
      maxDDPct = maxDDPct,
      totalNetDollars = r.total,
      annualNetDollars = r.total / years,
    )
  }

  private fun squeezeRow(strategy: String, asset: String, tf: String, years: Double, r: BackTestReport): Row {
    return Row(
      strategy = strategy,
      asset = asset, tf = tf, years = years, trades = r.tradeCount,
      winRatePct = 100.0 * r.winRate,
      avgWinPct = 100.0 * r.avgWinPct,
      avgLossPct = 100.0 * r.avgLossPct,
      profitFactor = r.profitFactor,
      annualReturnPct = 100.0 * r.profitability / years,
      maxDDPct = 100.0 * r.maxDrawDown,
      totalNetDollars = r.profitLoss,
      annualNetDollars = r.profitLoss / years,
    )
  }

  // ───────────────────────────────────────────────────────────────────────
  // Reporting
  // ───────────────────────────────────────────────────────────────────────

  private fun printTable(rows: List<Row>) {
    val header = "%-8s %-4s %-4s %5s %6s %6s %8s %8s %6s %10s %8s | %14s %14s".format(
      "strat", "ast", "tf", "yrs", "#t", "win%", "avgWin%", "avgLoss%", "PF",
      "annRet%/yr", "maxDD%", "totalNet$", "annNet$"
    )
    println(header)
    println("-".repeat(header.length))
    for (r in rows) {
      println("%-8s %-4s %-4s %5.1f %6d %5.1f%% %7.2f%% %7.2f%% %6.2f %9.1f%% %7.1f%% | %14s %14s".format(
        r.strategy, r.asset, r.tf, r.years, r.trades, r.winRatePct,
        r.avgWinPct, r.avgLossPct, r.profitFactor, r.annualReturnPct, r.maxDDPct,
        dollarFmt(r.totalNetDollars), dollarFmt(r.annualNetDollars)
      ))
    }
    println()
    println("Note: total/annual \$ are NATIVE units. Rumers = 1 unit per signal")
    println("(NQ \$20/pt × 1 contract; ETH 1 ETH). Squeeze = \$13k account × 0.02 bet.")
    println("BOTH STRATEGIES: pyramidingLimit=$PYRAMID, profit-gated.")
    println("Use PF, win%, avgWin%, avgLoss%, annRet%/yr for cross-comparison.")
  }

  private fun writeHtml(rows: List<Row>) {
    val sb = StringBuilder()
    sb.append("""<!doctype html><html lang="en" data-theme="synthwave"><head>
<meta charset="UTF-8"><title>Strategy comparison</title>
<link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/daisyui@5.5.19/daisyui.css">
<link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/daisyui@5.5.19/themes.css">
<script src="https://cdn.jsdelivr.net/npm/@tailwindcss/browser@4.2.4/dist/index.global.js"></script>
</head><body class="min-h-screen bg-base-300">
<div class="navbar bg-base-100 shadow-lg"><div class="navbar-start"><span class="text-2xl font-bold">📊 Strategy comparison</span><span class="badge badge-soft badge-accent ml-3">Rumers vs Squeeze · NQ M15 vs ETH H1</span></div></div>
<main class="mx-auto max-w-7xl p-6 lg:p-10 space-y-6">
<section class="alert">
<div><h3 class="font-bold">How to read this</h3>
<div class="text-sm">PF, win%, avgWin%, avgLoss%, and annRet%/yr are directly comparable.<br>
Absolute \$ amounts are NOT comparable: Rumers = 1 NQ contract / 1 ETH per signal; Squeeze = \$13k account × 0.02 bet.<br>
Both strategies use <code>pyramidingLimit=$PYRAMID</code> with the profit gate ON.</div></div></section>
<section class="card bg-base-100 shadow"><div class="card-body"><h2 class="card-title">Comparison</h2>
<div class="overflow-x-auto"><table class="table table-zebra"><thead><tr>
<th>Strategy</th><th>Asset</th><th>TF</th><th>Years</th>
<th class="text-right">Trades</th><th class="text-right">Win %</th>
<th class="text-right">Avg win %</th><th class="text-right">Avg loss %</th>
<th class="text-right">PF</th><th class="text-right">Ann ret %/yr</th>
<th class="text-right">Max DD %</th>
<th class="text-right">Total net \$ (native)</th>
<th class="text-right">Annual net \$ (native)</th></tr></thead><tbody>""")
    for (r in rows) {
      val pfCls = if (r.profitFactor >= 1.2) "text-success font-semibold"
                  else if (r.profitFactor >= 1.0) "text-warning"
                  else "text-error"
      val retCls = if (r.annualReturnPct > 0) "text-success" else "text-error"
      sb.append("<tr>")
      sb.append("<td><span class=\"badge badge-soft\">${r.strategy}</span></td>")
      sb.append("<td>${r.asset}</td>")
      sb.append("<td>${r.tf}</td>")
      sb.append("<td>${"%.1f".format(r.years)}</td>")
      sb.append("<td class=\"text-right tabular-nums\">${r.trades}</td>")
      sb.append("<td class=\"text-right tabular-nums\">${"%.1f".format(r.winRatePct)}%</td>")
      sb.append("<td class=\"text-right tabular-nums\">${"%+.2f".format(r.avgWinPct)}%</td>")
      sb.append("<td class=\"text-right tabular-nums\">${"%+.2f".format(r.avgLossPct)}%</td>")
      sb.append("<td class=\"text-right tabular-nums $pfCls\">${"%.2f".format(r.profitFactor)}</td>")
      sb.append("<td class=\"text-right tabular-nums $retCls\">${"%+.1f".format(r.annualReturnPct)}%</td>")
      sb.append("<td class=\"text-right tabular-nums\">${"%.1f".format(r.maxDDPct)}%</td>")
      sb.append("<td class=\"text-right tabular-nums\">${dollarFmt(r.totalNetDollars)}</td>")
      sb.append("<td class=\"text-right tabular-nums\">${dollarFmt(r.annualNetDollars)}</td>")
      sb.append("</tr>\n")
    }
    sb.append("""</tbody></table></div></div></section>
</main></body></html>""")
    java.io.File(".lavish/cross-comparison.html").apply {
      parentFile?.mkdirs()
      writeText(sb.toString())
    }
  }

  private fun dollarFmt(v: Double) =
    if (v >= 0) "$%,.0f".format(v) else "-$%,.0f".format(-v)

  private fun yearsBetween(epochSecStart: Long, epochSecEnd: Long): Double =
    (epochSecEnd - epochSecStart) / (365.25 * 24 * 3600.0)
}
