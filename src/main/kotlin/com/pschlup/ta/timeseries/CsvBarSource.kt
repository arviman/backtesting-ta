@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.pschlup.ta.timeseries

import com.opencsv.CSVReader
import java.io.*
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * A simple source of time series bars, useful for initialization and backtesting.
 *
 * The header row is used to detect column positions by name. The following column names
 * are recognised (case-insensitive):
 *   - Date column:  `date`, `time`, `timestamp`, `datetime`
 *   - Open column:  `open`
 *   - High column:  `high`
 *   - Low column:   `low`
 *   - Close column: `close`, `price`
 *   - Volume col:   `volume`, `vol`, `vol.`
 *
 * Timestamps can be either ISO-8601 (e.g. `2019-04-19T18:35:00Z`) or `M/d/yyyy`
 * (e.g. `06/14/2026` — interpreted as UTC midnight).
 *
 * Volume values may include a suffix: `K` (thousands), `M` (millions), `B` (billions),
 * e.g. `"2.62M"` → 2,620,000.
 *
 * Bars are sorted chronologically before being returned, so the source data may be
 * in either ascending or descending order.
 *
 * Example formats:
 *    "date","open","high","low","close","volume"
 *    "2019-04-19T18:35:00Z","5268.85","5270.56","5268.85","5270.56","552.51"
 *
 *    "Date","Price","Open","High","Low","Vol.","Change %"
 *    "06/14/2026","68.349","68.808","69.070","67.919","2.62M","-0.78%"
 */
fun readCsvBars(fileName: String): List<Bar> = readCsvBars(FileReader(File(fileName)))
fun readCsvBars(input: InputStream): List<Bar> = readCsvBars(InputStreamReader(input))
fun readCsvBars(reader: Reader): List<Bar> =
  CSVReader(reader).use { csvReader ->
    val rows = csvReader.readAll()
    if (rows.size < 4) return emptyList()

    val header = rows[0].map { it.trim().lowercase().replace("\"", "").replace("\uFEFF", "") }
    val cols = ColumnIndex(header)

    // Detects the timeframe from two consecutive data rows.
    val t1 = parseDateTime(rows[2][cols.date])
    val t2 = parseDateTime(rows[3][cols.date])
    val timeFrame = TimeFrame.of(Duration.between(t1, t2).abs())

    rows
      .drop(1) // Skips header
      .map { row ->
        Bar(
          timeFrame = timeFrame,
          openTime = parseDateTime(row[cols.date]),
          open = row[cols.open].toDouble(),
          high = row[cols.high].toDouble(),
          low = row[cols.low].toDouble(),
          close = row[cols.close].toDouble(),
          volume = parseVolume(row[cols.volume]),
        )
      }
      .sortedBy { it.openTime } // Ensure chronological order
  }

// ===========================================================
// Column-mapping from header names
// ===========================================================

private class ColumnIndex(header: List<String>) {
  val date: Int = header.indexOfFirst { it in dateNames }
  val open: Int = header.indexOfFirst { it in openNames }
  val high: Int = header.indexOfFirst { it in highNames }
  val low: Int = header.indexOfFirst { it in lowNames }
  val close: Int = header.indexOfFirst { it in closeNames }
  val volume: Int = header.indexOfFirst { it in volumeNames }

  init {
    require(date >= 0) { "Could not find date/time column in header: $header" }
    require(open >= 0) { "Could not find open column in header: $header" }
    require(high >= 0) { "Could not find high column in header: $header" }
    require(low >= 0) { "Could not find low column in header: $header" }
    require(close >= 0) { "Could not find close/price column in header: $header" }
    require(volume >= 0) { "Could not find volume column in header: $header" }
  }

  companion object {
    private val dateNames = setOf("date", "time", "timestamp", "datetime")
    private val openNames = setOf("open")
    private val highNames = setOf("high")
    private val lowNames = setOf("low")
    private val closeNames = setOf("close", "price")
    private val volumeNames = setOf("volume", "vol", "vol.")
  }
}

// ===========================================================
// Date / volume parsing
// ===========================================================

private val DATE_FORMAT_MM_DD_YYYY = DateTimeFormatter.ofPattern("M/d/yyyy")

private fun parseDateTime(value: String): Instant {
  val cleaned = value.trim().replace("\"", "")
  return try {
    Instant.parse(cleaned)
  } catch (_: Exception) {
    try {
      LocalDate.parse(cleaned, DATE_FORMAT_MM_DD_YYYY)
        .atStartOfDay(ZoneOffset.UTC)
        .toInstant()
    } catch (e: Exception) {
      throw IllegalArgumentException("Cannot parse date: $cleaned", e)
    }
  }
}

private fun parseVolume(value: String): Double {
  val cleaned = value.trim().replace("\"", "").replace(",", "")
  val multiplier = when {
    cleaned.endsWith("B", ignoreCase = true) -> 1_000_000_000.0
    cleaned.endsWith("M", ignoreCase = true) -> 1_000_000.0
    cleaned.endsWith("K", ignoreCase = true) -> 1_000.0
    else -> 1.0
  }
  val numericPart = if (multiplier != 1.0) cleaned.dropLast(1) else cleaned
  return numericPart.toDouble() * multiplier
}
