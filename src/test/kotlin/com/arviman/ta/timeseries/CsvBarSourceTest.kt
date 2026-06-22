package com.arviman.ta.timeseries

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class CsvBarSourceTest {

  @Test
  fun `parses SOL daily CSV with alternate column order and volume suffixes`() {
    val bars = readCsvBars("sampledata/sol_d_02012023_14062026.csv")

    assertThat(bars.size).isGreaterThan(1200)

    // Oldest bar first (sorted chronologically)
    val first = bars.first()
    assertThat(first.timeFrame).isEqualTo(TimeFrame.D)
    assertThat(first.openTime.toString()).startsWith("2023-01-02T00:00:00Z")
    assertThat(first.close).isGreaterThan(0.0)
    assertThat(first.volume).isGreaterThan(1_000_000.0) // "M" suffix parsed

    // Newest bar last
    val last = bars.last()
    assertThat(last.openTime.toString()).startsWith("2026-06-14T00:00:00Z")
  }

  @Test
  fun `parses existing BTC intraday CSV`() {
    val bars = readCsvBars("sampledata/chart_data_BTC_USDT_p5_90d.csv")

    assertThat(bars).isNotEmpty()
    assertThat(bars.first().timeFrame).isEqualTo(TimeFrame.M5)
    assertThat(bars.first().openTime.toString()).startsWith("2019-04-19T18:35:00Z")
  }
}
