# Does the UDP protocol scale to new kinds of fields?

Prompted by the Ruuvi Air: a device that measures CO₂, particulate matter, VOC
and NOx alongside the temperature and humidity every other sensor has. The
pi-reader decodes all of it (see `DataFormat6`), the local page shows the
headline pair, and the temperature and humidity travel to the server through
the existing pipeline untouched. This memo is about the part that does not
travel: can protocol v1 carry fields it was not born with, and what should v2
look like when one is actually wanted end to end.

Written against `Protocol.h` version 1, 2026-08.

## What v1 is

```
header, 8 bytes            then 8 bytes per sensor, up to 8 sensors
  0     version              0..3  sensor id
  1..4  device id            4..5  temperature, 0.01 °C, signed
  5     sensor count         6..7  humidity, 0.01 %, unsigned
  6..7  sequence
```

72 bytes at its largest. Three encoders speak it (Pico C, ESP32 C, pi-reader
Java), one decoder reads it, and `ProtocolSyncTest` on both Java sides reads
`Protocol.h` so the constants cannot drift.

## The honest answer: no — and it does not need to

There is no room in the sensor record for a third value, no flags, no length
fields. That was the right call for what it is: a format three
microcontrollers with 264 kB of RAM emit from a dozen lines of C, with
"missing" sentinels as its only sophistication.

What makes it evolvable anyway is byte 0. The version is the first thing the
decoder reads, so a v2 can look like anything at all and coexist with v1 on
the same port — old firmware keeps sending v1 forever and is none the wiser.
Note that this has already been paid for once: even data format 5's pressure
never went into the packet. Air quality does not create the "local knows more
than the server" gap; it widens an existing one.

## Three shapes v2 could take

**A. A wider fixed record.** Add pressure, CO₂, PM2.5 (say 6 more bytes) to
every sensor record. Simplest possible C. But every plain RuuviTag then ships
six bytes of sentinels per reading forever, and the *next* new sensor kind
reopens the format — this option converts one migration into a subscription
to them.

**B. Type–length–value fields per sensor.** A sensor record becomes
`id(4) + field count(1) + fields`, each field `type(1) + value(2)`, types from
a shared registry in `Protocol.h` (1 = temperature 0.01 °C, 2 = humidity,
3 = pressure, 4 = CO₂ ppm, 5 = PM2.5 0.1 µg/m³, 6 = VOC, 7 = NOx, …). A plain
tag sends two fields and is *smaller* than option A; an Air sends seven. An
unknown type is skipped by length, so a new field needs a new decoder only
where somebody wants to read it. Worst case today: 8 sensors × (5 + 7×3) =
208 bytes + header — a third of the safe UDP payload (~508 bytes).

**C. A type byte per record**, selecting one of a few fixed layouts (Ruuvi's
own data-format approach). Middle ground: denser than B, but every new layout
is a coordinated three-firmware release, which is exactly the coordination B
exists to avoid.

**Recommendation: B**, when the day comes. The sentinel discipline carries
over unchanged (a field that would not survive the round trip is simply not
sent, which TLV makes natural), and `ProtocolSyncTest` extends to the type
registry the same way it covers the constants now.

## The server side is the larger half of the migration

The wire is the easy part. `measurement_sample` has `temperature` and
`humidity` columns, and every consumer — cards, sparkline, gauges, alerts,
degree-days — reads those two by name.

- **New columns** (`co2`, `pm25`, …): pragmatic while the fields are few and
  near-universal. Nullable, so plain tags cost ~1 byte of null bitmap. This is
  the same shape the entity already has for humidity-less tags.
- **A value table** (`sample_id, field, value`): scales to any field without
  migrations, but multiplies rows — an Air at a 5-minute cadence would write
  ~7 rows where it now writes 1, which lands straight on the retention memo's
  disk arithmetic (~0.25–0.3 kB per row with indexes).

Columns first, value table only if fields stop being enumerable. Either way
the retention sweep already covers them: they live in the same rows.

## What is deliberately not done yet

- v2 itself: no field is wanted on the server end to end yet. The first one
  that is (CO₂ alerting is the likely candidate) triggers the whole chain:
  wire type registry, decoder dispatch on the version byte, a column, and a
  place on the sensor card.
- Data format E1 (the Air's extended advertisement): finer PM classes and the
  full address. The pi-reader hears format 6 with any adapter; E1 is worth
  adding only when a value it alone carries is wanted.
