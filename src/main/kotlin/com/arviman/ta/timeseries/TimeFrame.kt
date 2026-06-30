package com.arviman.ta.timeseries

import java.time.Duration

enum class TimeFrame(val duration: Duration) {
  M1(Duration.ofMinutes(1)),
  M5(Duration.ofMinutes(5)),
  M10(Duration.ofMinutes(10)),
  M15(Duration.ofMinutes(15)),
  M30(Duration.ofMinutes(30)),
  H1(Duration.ofHours(1)),
  H2(Duration.ofHours(2)),
  H4(Duration.ofHours(4)),
  D(Duration.ofDays(1)),
  D3(Duration.ofDays(3)),
  W(Duration.ofDays(7));

  companion object {
    @JvmStatic
    fun of(duration: Duration): TimeFrame = when (duration.toMinutes().toInt()) {
      1 -> M1
      5 -> M5
      10 -> M10
      15 -> M15
      30 -> M30
      60 -> H1
      120 -> H2
      240 -> H4
      1440 -> D
      4320 -> D3
      10080 -> W
      else -> throw IllegalArgumentException("Unknown timeframe of ${duration.toMinutes()} minutes")
    }
  }
}