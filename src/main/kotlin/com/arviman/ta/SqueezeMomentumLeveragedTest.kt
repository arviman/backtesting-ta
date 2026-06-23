package com.arviman.ta

import com.arviman.ta.strategy.SqueezeParams
import com.arviman.ta.strategy.SqzEntryMode
import com.arviman.ta.strategy.SqzExitMode
import com.arviman.ta.strategy.makeSqueezeMomentumStrategy
import com.arviman.ta.strategy.SignalType
import com.arviman.ta.timeseries.Bar
import com.arviman.ta.timeseries.TimeSeriesManager
import com.arviman.ta.timeseries.UnstablePeriodException
import com.arviman.ta.timeseries.readCsvBars
import java.time.ZoneOffset
import kotlin.math.max
import kotlin.math.min

/**
 * Squeeze Momentum on ETHUSD H1 — **combined-direction, leveraged**
 * runner that matches the user's cTrader setup:
 *
 *   - One account, longs + shorts share the same balance.
 *   - Pyramid cap = 6 total open positions across both directions.
 *   - Each entry opens at a fixed notional ($13k) regardless of balance,
 *     i.e. margin trading. Pyramid 6 × $13k = $78k peak notional on
 *     $13k → ≈ 6× leverage.
 *   - SL/TP fixed in absolute price points (replicates "49 pips").
 *   - Opposite-direction signal closes existing position, then opens new.
 *
 * Runs OS slice only (last 40% of data).
 */
object SqueezeMomentumLeveragedTest {

  private const val DATA_PATH = "sampledata/ETHUSD_1h_Bitstamp.csv"

  // ───── User's prop-firm winning config ─────
  private const val BB_LENGTH = 11
  private const val BB_MULT = 2.3
  private const val KC_LENGTH = 22
  private const val KC_MULT = 1.4              // wide enough that sqzOff gate matters

  private const val STARTING_BAL = 13_000.0
  // cTrader uses fixed lots, not fixed notional. Match that:
  // 0.8 lots × 10 ETH/lot = 8 ETH per entry, regardless of ETH price.
  // At $47 SL × 8 ETH = ~$376 per trade SL (matches user's "$400" report).
  private const val COINS_PER_ENTRY = 5.0
  private const val SL_ABS_PTS = 47.0
  private const val PYRAMID = 4
  private const val FEE = 0.0004

  /**
   * Staggering controls — defaults match the user's cTrader bot.
   * Each successive pyramid position widens SL and raises TP multiplier.
   */
  private data class Stagger(
    val minEntryDistancePts: Double = 15.0,
    val requireProfit: Boolean = true,
    val slStaggerPts: Double = 5.0,
    val tpStaggerMult: Double = 0.2,
  )

  private data class Variant(
    val label: String,
    val entryMode: SqzEntryMode,
    val exitMode: SqzExitMode,
  )

  private val variants = listOf(
    Variant("D ctrader-rep  ", SqzEntryMode.ContinuationLime, SqzExitMode.ZeroCrossPlusContinuation),
  )

  // Base TP ratio fixed at the winner; we're testing structural SL only.
  private val tpRatios = listOf(1.0)

  // Lock to the winning stagger config (requireProfit on, everything else off).
  private val staggers: List<Pair<String, Stagger>> = listOf(
    "profit gate ON" to Stagger(minEntryDistancePts = 0.0, requireProfit = true,
                                slStaggerPts = 0.0, tpStaggerMult = 0.0),
  )

  // Level 1 SL-lookback sweep. 0 = static 47-pip SL (baseline).
  // Focus on the winner (80) plus neighbours for confidence.
  private val slLookbackBars = listOf(0, 50, 80, 100)

  @JvmStatic
  fun main(args: Array<String>) {
    println("Loading bars from $DATA_PATH ...")
    val bars = readCsvBars(DATA_PATH)
    val splitIdx = (bars.size * 0.6).toInt()
    val osr = bars.subList(splitIdx, bars.size)
    println("OS bars: ${osr.size} (~${osr.size / (24 * 365)} years)\n")
    println("Combined-direction leveraged runner (cTrader fixed-lots model). " +
        "Balance $$STARTING_BAL, $COINS_PER_ENTRY ETH/entry, pyramid $PYRAMID, SL $SL_ABS_PTS pts.")
    println(header())
    println("-".repeat(120))

    val rows = mutableListOf<Pair<String, CombinedReport>>()
    for ((stagLabel, stag) in staggers) {
      for (v in variants) {
        for (tp in tpRatios) {
          for (slLb in slLookbackBars) {
            val r = runCombined(osr, v, tp, stag,
              tpLookbackBars = 0, slLookbackBars = slLb)
            val slLabel = if (slLb == 0) "SL static  " else "SL lb=%-3d ".format(slLb)
            val label = "$stagLabel $slLabel tp=$tp"
            println(row(label, r))
            rows += label to r
          }
          println()
        }
        println("-".repeat(120))
      }
      println("=".repeat(120))
    }

    println("=== Top 5 by NET profit $ ===")
    rows.sortedByDescending { it.second.profitDollars }.take(5)
      .forEach { (l, r) -> println(row(l, r)) }
    println("\n=== Top 5 by PROFIT FACTOR ===")
    rows.filter { it.second.tradeCount > 30 }
      .sortedByDescending { it.second.profitFactor }.take(5)
      .forEach { (l, r) -> println(row(l, r)) }
    println("\n=== Top 5 by PROFIT / DD ratio ===")
    rows.filter { it.second.tradeCount > 30 && it.second.maxDDDollars > 0 }
      .sortedByDescending { it.second.profitDollars / it.second.maxDDDollars }.take(5)
      .forEach { (l, r) -> println(row(l, r)) }

    // Account-size scenarios using the absolute profit + DD figures.
    val best = rows.maxByOrNull { it.second.profitDollars }!!
    println("\n=== Best config (${best.first}) scaled across account sizes ===")
    println("%-12s | %-10s %-10s %-8s %-8s".format("account", "profit", "peakDD", "DD%", "profit%"))
    listOf(5_000.0, 13_000.0, 50_000.0, 100_000.0, 200_000.0).forEach { acc ->
      println("%-12s | %-10s %-10s %6.1f%% %6.1f%%".format(
        "$%,.0f".format(acc),
        "$%+,.0f".format(best.second.profitDollars),
        "$%,.0f".format(best.second.maxDDDollars),
        100.0 * best.second.maxDDDollars / acc,
        100.0 * best.second.profitDollars / acc,
      ))
    }

    // ── Yearly breakdown for the best config ──
    println("\n=== Yearly P&L breakdown for best config (${best.first}) ===")
    val baseBalance = 13_000.0
    var runningBal = baseBalance
    println("%-6s | %-14s | %-14s | %-14s | %-14s".format("Year", "PnL", "Cumulative PnL", "Year-end Balance", "Return %"))
    println("-".repeat(70))
    val yearlyPnL = best.second.yearlyPnL
    val allYears = if (yearlyPnL.isEmpty()) emptyList()
      else (yearlyPnL.keys.min()..yearlyPnL.keys.max()).toList()
    for (year in allYears) {
      val pnl = yearlyPnL[year] ?: 0.0
      runningBal += pnl
      val cumPnL = runningBal - baseBalance
      val retPct = if (baseBalance > 0) 100.0 * cumPnL / baseBalance else 0.0
      println("%-6d | %+,14.0f | %+,14.0f | %,14.0f | %+,13.1f%%".format(year, pnl, cumPnL, runningBal, retPct))
    }

    val bnh = osr.last().close / osr.first().close
    println("\nBuy & hold OS: %.2fx (%+.1f%%)".format(bnh, 100.0 * (bnh - 1.0)))
  }

  // ─────────────────────────────────────────────────────────────────────
  //  Combined-direction leveraged runner
  // ─────────────────────────────────────────────────────────────────────

  private data class OpenPos(
    val side: Int,            // +1 long, -1 short
    val coins: Double,
    val entryPrice: Double,
    val slPrice: Double,
    val tpPrice: Double,
  )

  data class CombinedReport(
    val tradeCount: Int,
    val winRate: Double,
    val avgWinPct: Double,
    val avgLossPct: Double,
    val profitFactor: Double,
    val profitPct: Double,
    val maxDD: Double,
    val maxDDDollars: Double,
    val profitDollars: Double,
    val finalBalance: Double,
    val yearlyPnL: Map<Int, Double>,
  )

  private fun runCombined(
    bars: List<Bar>,
    v: Variant,
    tpRatio: Double,
    stag: Stagger,
    tpLookbackBars: Int = 0,    // 0 = static TP only (Level 0); >0 = Level 1 structural floor
    slLookbackBars: Int = 0,    // 0 = static SL only; >0 = structural SL (below recent low / above recent high)
    slBufferPts: Double = 5.0,  // pad past the structural level to avoid pixel-hunt stops
  ): CombinedReport {
    val tsm = TimeSeriesManager(bars[0].timeFrame)
    val requireSqzOff = v.entryMode == SqzEntryMode.ContinuationLime  // cTrader replica gates by sqzOff
    val params = SqueezeParams(
      bbLength = BB_LENGTH, bbMult = BB_MULT,
      kcLength = KC_LENGTH, kcMult = KC_MULT,
      entryMode = v.entryMode, exitMode = v.exitMode,
      requireSqzOff = requireSqzOff,
    )
    val longStrategy = makeSqueezeMomentumStrategy(tsm.h1, params, longSide = true)
    val shortStrategy = makeSqueezeMomentumStrategy(tsm.h1, params, longSide = false)

    var balance = STARTING_BAL
    var peakEquity = balance
    var maxDD = 0.0
    var maxDDDollars = 0.0
    val positions = mutableListOf<OpenPos>()
    val pnls = mutableListOf<Double>()
    val yearlyPnL = sortedMapOf<Int, Double>()

    fun closePos(pos: OpenPos, price: Double): Double {
      val gross = (price - pos.entryPrice) * pos.coins * pos.side
      val exitFee = price * pos.coins * FEE
      val net = gross - exitFee
      balance += net
      pnls += net
      return net
    }

    fun openPos(side: Int, price: Double, structuralHi: Double, structuralLo: Double): Double {
      val sameSideCount = positions.count { it.side == side }
      val staticSlPts = max(1.0, SL_ABS_PTS + sameSideCount * stag.slStaggerPts)
      // Level 1 structural SL: floor at static, widen to past low/high + buffer if farther.
      val slPts = if (slLookbackBars > 0) {
        if (side > 0) max(staticSlPts, price - structuralLo + slBufferPts)
        else          max(staticSlPts, structuralHi - price + slBufferPts)
      } else staticSlPts
      val tpMult = max(0.0, tpRatio + sameSideCount * stag.tpStaggerMult)
      val staticTpPts = slPts * tpMult
      // Level 1 structural TP: floor at static, raise to recent high/low if farther.
      val tpPts = if (tpLookbackBars > 0) {
        if (side > 0) max(staticTpPts, structuralHi - price)
        else          max(staticTpPts, price - structuralLo)
      } else staticTpPts
      val coins = COINS_PER_ENTRY                                    // fixed-lots model
      val slPrice = if (side > 0) price - slPts else price + slPts
      val tpPrice = if (side > 0) price + tpPts else price - tpPts
      val entryFee = price * coins * FEE
      balance -= entryFee
      positions += OpenPos(side, coins, price, slPrice, tpPrice)
      return entryFee
    }

    /** cTrader's `IsDistanceValid` — newest entry on same side must be ≥ minDist away. */
    fun distanceOk(side: Int, price: Double): Boolean {
      if (stag.minEntryDistancePts <= 0) return true
      val sameSide = positions.filter { it.side == side }
      if (sameSide.isEmpty()) return true
      return sameSide.all { kotlin.math.abs(price - it.entryPrice) >= stag.minEntryDistancePts }
    }

    /** cTrader's `IsPyramidProfitable` — open same-side book must be net positive (unrealized + entry fees). */
    fun profitOk(side: Int, price: Double): Boolean {
      if (!stag.requireProfit) return true
      val sameSide = positions.filter { it.side == side }
      if (sameSide.isEmpty()) return true
      val unrealized = sameSide.sumOf { (price - it.entryPrice) * it.coins * it.side }
      return unrealized > 0
    }

    for ((bIdx, bar) in bars.withIndex()) {
      val close = bar.close
      val high = bar.high
      val low = bar.low

      // 1) Check intrabar SL/TP hits on open positions.
      val iter = positions.iterator()
      while (iter.hasNext()) {
        val pos = iter.next()
        val hitSL = if (pos.side > 0) low <= pos.slPrice else high >= pos.slPrice
        val hitTP = if (pos.side > 0) high >= pos.tpPrice else low <= pos.tpPrice
        if (hitSL) { val net = closePos(pos, pos.slPrice); iter.remove(); yearlyPnL.merge(bar.openTime.atZone(ZoneOffset.UTC).year, net, Double::plus) }
        else if (hitTP) { val net = closePos(pos, pos.tpPrice); iter.remove(); yearlyPnL.merge(bar.openTime.atZone(ZoneOffset.UTC).year, net, Double::plus) }
      }

      // 2) Mark to market and update drawdown.
      val unrealized = positions.sumOf { (close - it.entryPrice) * it.coins * it.side }
      val equity = balance + unrealized
      peakEquity = max(peakEquity, equity)
      val dd = if (peakEquity > 0) (peakEquity - equity) / peakEquity else 0.0
      val ddDollars = peakEquity - equity
      if (dd > maxDD) maxDD = dd
      if (ddDollars > maxDDDollars) maxDDDollars = ddDollars

      // 3) Feed the timeseries and read signals.
      tsm += bar
      if (tsm.h1.latestBar?.closeTime != bar.closeTime) continue

      val longSig = try { longStrategy[0] } catch (_: UnstablePeriodException) { SignalType.NO_OP }
      val shortSig = try { shortStrategy[0] } catch (_: UnstablePeriodException) { SignalType.NO_OP }

      // Structural hi/lo over the prior max(tpLookback, slLookback) bars.
      val lookback = max(tpLookbackBars, slLookbackBars)
      val (structuralHi, structuralLo) = if (lookback > 0 && bIdx > 0) {
        val from = max(0, bIdx - lookback)
        val window = bars.subList(from, bIdx)
        window.maxOf { it.high } to window.minOf { it.low }
      } else Double.NEGATIVE_INFINITY to Double.POSITIVE_INFINITY

      // 4) cTrader order: entries FIRST (net mode — skip if opposite side has positions).
      if (longSig === SignalType.ENTRY) {
        val noOpposite = positions.none { it.side < 0 }
        val sameSide = positions.count { it.side > 0 }
        if (noOpposite && sameSide < PYRAMID && balance > 0
            && distanceOk(+1, close) && profitOk(+1, close)) {
          val fee = openPos(+1, close, structuralHi, structuralLo)
          yearlyPnL.merge(bar.openTime.atZone(ZoneOffset.UTC).year, -fee, Double::plus)
        }
      }
      if (shortSig === SignalType.ENTRY) {
        val noOpposite = positions.none { it.side > 0 }
        val sameSide = positions.count { it.side < 0 }
        if (noOpposite && sameSide < PYRAMID && balance > 0
            && distanceOk(-1, close) && profitOk(-1, close)) {
          val fee = openPos(-1, close, structuralHi, structuralLo)
          yearlyPnL.merge(bar.openTime.atZone(ZoneOffset.UTC).year, -fee, Double::plus)
        }
      }

      // 5) Then exits (same-direction only).
      if (longSig === SignalType.EXIT) {
        positions.filter { it.side > 0 }.forEach { val net = closePos(it, close); yearlyPnL.merge(bar.openTime.atZone(ZoneOffset.UTC).year, net, Double::plus) }
        positions.removeAll { it.side > 0 }
      }
      if (shortSig === SignalType.EXIT) {
        positions.filter { it.side < 0 }.forEach { val net = closePos(it, close); yearlyPnL.merge(bar.openTime.atZone(ZoneOffset.UTC).year, net, Double::plus) }
        positions.removeAll { it.side < 0 }
      }
    }

    // 6) Force-close anything left at the final close.
    val lastClose = bars.last().close
    val finalYear = bars.last().openTime.atZone(ZoneOffset.UTC).year
    positions.forEach {
      val net = closePos(it, lastClose)
      yearlyPnL.merge(finalYear, net, Double::plus)
    }
    positions.clear()

    val wins = pnls.filter { it > 0 }
    val losses = pnls.filter { it <= 0 }
    val winRate = if (pnls.isNotEmpty()) wins.size.toDouble() / pnls.size else 0.0
    // Use average ETH price across bars as the notional reference, since
    // notional scales with price in the fixed-lots model.
    val refNotional = bars.map { it.close }.average() * COINS_PER_ENTRY
    val avgWinPct = if (wins.isNotEmpty()) wins.average() / refNotional else 0.0
    val avgLossPct = if (losses.isNotEmpty()) losses.average() / refNotional else 0.0
    val grossWins = wins.sum()
    val grossLosses = -losses.sum()
    val pf = if (grossLosses > 0) grossWins / grossLosses else Double.POSITIVE_INFINITY
    return CombinedReport(
      tradeCount = pnls.size,
      winRate = winRate,
      profitDollars = balance - STARTING_BAL,
      maxDDDollars = maxDDDollars,
      avgWinPct = avgWinPct,
      avgLossPct = avgLossPct,
      profitFactor = pf,
      profitPct = (balance - STARTING_BAL) / STARTING_BAL,
      maxDD = maxDD,
      finalBalance = balance,
      yearlyPnL = yearlyPnL.toMap(),
    )
  }

  // ─────────────────────────────────────────────────────────────────────
  //  Formatting
  // ─────────────────────────────────────────────────────────────────────

  private fun header(): String =
    "%-32s | %5s %7s %9s %9s %5s".format(
      "variant", "#t", "win%", "profit $", "peakDD $", "PF",
    )

  private fun row(label: String, r: CombinedReport): String =
    "%-32s | %5d %7s %9s %9s %5s".format(
      label,
      r.tradeCount,
      pct(r.winRate),
      "$%.0f".format(r.profitDollars),
      "$%.0f".format(r.maxDDDollars),
      if (r.profitFactor.isFinite()) "%.2f".format(r.profitFactor) else "—",
    )

  private fun pct(v: Double): String = if (v.isFinite()) "%.1f%%".format(100.0 * v) else "—"
  private fun pctSigned(v: Double): String = if (v.isFinite()) "%+.1f%%".format(100.0 * v) else "—"
}
