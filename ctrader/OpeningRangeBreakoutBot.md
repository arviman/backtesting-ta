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
| Entries | `Use breakout entries` | true | Trade close beyond the OR (standard breakout) |
| Entries | `Use liquidity sweep entries` | false | Trade *failed* breaks (false-break reversal) |
| Entries | `Sweep wick × ATR(14)` | 0.4 | Min wick beyond the OR for a sweep to count |
| Time | `Use time-of-day filter` | false | Restrict entries to a server-time hour window |
| Time | `Active start hour` | 7 | Window start (inclusive) |
| Time | `Active end hour (exclusive)` | 21 | Window end. If end < start, wraps midnight |
| Risk | `SL × ATR(14)` | 2.0 | Stop distance |
| Risk | `TP : SL ratio` | 2.0 | Target distance as multiple of SL |

### Sweep vs breakout

Same V2 dichotomy: a breakout takes the *successful* clear of the level
(close beyond), a sweep takes the *failed* clear (wicks beyond then
closes back inside). Both can be on simultaneously and the optimizer
will tell you which the asset prefers. Sweep requires a meaningful
wick (`WickAtrMult × ATR`) to filter noise.

## Optimization grid (start here on EURUSD H4)

| param | values | n |
|---|---|---|
| `DayStartHour` | 0, 7, 22 | 3 |
| `OpeningBars` | 1, 2, 3 | 3 |
| `UseHtfFilter` | false, true | 2 |
| `HtfMaLength` | 20, 50, 100 | 3 |
| `UseBreakoutEntries` | false, true | 2 |
| `UseSweepEntries` | false, true | 2 |
| `WickAtrMult` | 0.25, 0.4, 0.6 | 3 |
| `UseTimeFilter` | false, true | 2 |
| `StartHour` | 0, 7 | 2 |
| `EndHour` | 16, 20, 23 | 3 |
| `SlAtrMult` | 1.5, 2, 3 | 3 |
| `TpSlRatio` | 1.5, 2, 3 | 3 |

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
