# OpeningRangeBreakoutBot — cTrader cBot

A classic Opening Range Breakout (ORB) for H4 forex.

## The idea (one paragraph)

Mark the high and low of the first N bars of the broker's trading day
(default 2 H4 bars = the first 8 hours, roughly the Asian session).
Once the range is locked in, take the first bar that *closes* above
the range high (long) or below the range low (short). Trade once per
day — later breaks on the same day are ignored. The intuition: Asian
ranges are tight, London/NY opens drive the directional break, and
catching the first break of the day rides the impulse before chop sets
in. ATR-based stops/targets; one position per label.

## Parameters

| Group | Parameter | Default | Notes |
|---|---|---|---|
| Sizing | `Volume (lots)` | 1.0 | Per-trade lot size |
| Sizing | `Label` | `ORB` | Position identifier |
| Range | `Day start hour` | 0 | Broker server hour the "day" starts at |
| Range | `Opening bars` | 2 | Bars from day-start used to mark the opening range |
| Filters | `Use HTF trend filter` | true | Long only above Daily SMA, short only below |
| Filters | `HTF MA length` | 50 | Daily SMA length for the bias |
| Filters | `Use breakout-window filter` | false | If true, ignore breakouts after a cutoff hour |
| Filters | `Breakout window end hour` | 20 | The cutoff (broker server time) |
| Risk | `SL × ATR(14)` | 2.0 | Stop distance |
| Risk | `TP : SL ratio` | 2.0 | Target distance as multiple of SL |

## Optimization grid (start here on EURUSD H4)

| param | values |
|---|---|
| `DayStartHour` | 0, 7, 22 |
| `OpeningBars` | 1, 2, 3 |
| `UseHtfFilter` | false, true |
| `HtfMaLength` | 20, 50, 100 |
| `UseBreakoutWindow` | false, true |
| `BreakoutWindowEnd` | 16, 20 |
| `SlAtrMult` | 1.5, 2, 3 |
| `TpSlRatio` | 1.5, 2, 3 |

## Notes

- Day key is computed by anchoring `OpenTime - DayStartHour` to a date,
  so `DayStartHour=22` puts the day boundary at 22:00 server time. This
  is useful when you want the "day" to roll at NY close instead of
  midnight.
- `OpeningBars=2` on H4 = first 8 hours of the day used to mark the
  range. On the next bar onward, the bot watches for the first close
  that escapes the range.
- One position per `Label`. Run multiple instances with different
  labels to keep ORB books separate (e.g. EURUSD-ORB vs GBPUSD-ORB).
