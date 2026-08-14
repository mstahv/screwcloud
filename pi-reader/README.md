# ScrewCloud Pi reader

The same job as the [Pico](../temperature-reader) and [ESP32](../esp32-s3-reader)
firmwares, in Java: listen for RuuviTags over Bluetooth, show them on a page, and
forward them to the ScrewCloud server in the same packet the microcontrollers
send.

It exists because a Raspberry Pi is often already there doing something else, and
because a reader that runs on the same network as the tags can keep showing the
temperature when the internet does not.

**Quarkus** for the runtime, **Vaadin** for the page, **bluez-dbus** for the radio.
Java 21, no database.

The page uses the Aura theme and the same cards the server draws — the same gauge
and the same sparkline, from the same libraries — because the two are looked at by
the same person about the same sensors. What is missing here is what this
application does not have: no thresholds colouring the gauge, no alert settings,
no degree-day counters.

## What it is not

No notifications, no accounts, no database, no history beyond the last day. The
server has all of that and is the place for it. This is the thing on the local
network that still answers when the server cannot be reached.

The last day it does keep, in memory, because a temperature means little without
it: 4 °C on the way up is a different story from 4 °C on the way down. One point a
minute is finer than a four hundred pixel curve can show and finer than the server
stores, and a day of it for eight tags is a few hundred kilobytes. It is lost on a
restart, which costs a day of a picture the server has anyway.

## Running it

```bash
mvn quarkus:dev                       # http://localhost:8080
mvn package                           # frontend bundle included
java -jar target/quarkus-app/quarkus-run.jar
```

No `production` profile and no `vaadin-maven-plugin`: with Vaadin 25 the Quarkus
extension builds the frontend as part of the ordinary package.

Configure it from the environment, which is the point of every value in
`application.properties`:

```bash
SCREWCLOUD_DEVICE_ID=PI01 \
SCREWCLOUD_UPLOAD_HOST=r.pakast.in \
java -jar target/quarkus-app/quarkus-run.jar
```

| Setting | Default | |
|---|---|---|
| `screwcloud.device-id` | `PI01` | 1–4 characters, as a firmware's `DEVICE_ID` |
| `screwcloud.upload.host` / `.port` | `r.pakast.in` / `5555` | where the packets go |
| `screwcloud.upload.interval` | `5m` | the same pace the firmware keeps |
| `screwcloud.upload.enabled` | `true` | `false` to watch locally and report nowhere |
| `screwcloud.ble.enabled` | `true` | `false` on a machine with no Bluetooth |
| `screwcloud.names.file` | `~/.screwcloud-sensors.csv` | where the names live |

### On the Pi

BlueZ has to be running and the process needs to be allowed to talk to it. On
Raspberry Pi OS that means the user is in the `bluetooth` group:

```bash
sudo usermod -aG bluetooth $USER   # log out and back in
```

No pairing and no `bluetoothctl`: tags are never connected to, only listened for.

## How it works

**Scanning.** `BleScanner` starts a low energy discovery through BlueZ and then
reads each device's `ManufacturerData` property. Ruuvi's payload sits under
company id `0x0499` and carries the whole measurement — temperature, humidity,
pressure, battery, and the tag's own address — so nothing is ever connected to and
a new tag appears within a couple of seconds of being switched on.

The catch is that BlueZ keeps the last advertisement in that property after a tag
stops transmitting. Polled naively, one broadcast would be counted many times and
a tag with a flat battery would look alive forever. Data Format 5 carries a
measurement sequence number for exactly this, and a payload whose sequence has not
moved is discarded as the same broadcast read again.

**Identifiers.** A tag's sensor id is `R` and the low twelve bits of its address,
derived exactly as both firmwares derive it — the same tag may be heard by this
reader and by a microcontroller, and if the two disagreed the server would file it
as two sensors.

**Names** are a local matter and live in a hidden CSV file in the home directory
of whoever runs this. A file rather than a database because there is nothing else
to keep, and a handful of lines a person can edit is the right size for it:

```
# ScrewCloud sensor names: <sensor id>,<name>
R0BF,Cold room
R1AC,"Greenhouse, south end"
```

**Uploading** is UDP and fire and forget, like the firmware's — there is no reply
to wait for, and a lost packet is replaced by the next one five minutes later. It
is deliberately not what keeps the page working: everything shown comes from
memory, so a server that cannot be reached costs the history and nothing else.

Where the firmware silently drops sensors past the eighth, this refuses to build
such a packet, and the sender picks the eight heard most recently and logs which
ones it left out.

## Tests

```bash
mvn test
```

Sixty of them, and they are the part that can be checked without a Pi and a
tag on the table:

| | |
|---|---|
| `DataFormat5Test` | decoding, against **Ruuvi's own published test vectors** — this is the third implementation of that format in this repository, and a test written from my own reading of the spec would agree with my own misreading of it |
| `MeasurementPacketTest` | the bytes that leave the machine, against a packet written out by hand |
| `ProtocolSyncTest` | the constants and scaling, read out of the firmware's `Protocol.h`, so the three implementations cannot drift apart quietly |
| `TagRegistryTest` | out of order advertisements, staleness, and two tags landing on one identifier |
| `SensorNamesTest` | the file, including commas, quotes and hand edits |
| `ReadingHistoryTest` | the sampling, the day long window, and one curve per tag |
| `BleScannerBytesTest` | every shape D-Bus might hand the advertisement over in |
| `LocalViewTest` | the page itself, browserless: naming a tag, a missing value, a tag gone quiet, and when a curve is worth drawing |

`ProtocolSyncTest` already earned its keep. Java's `Math.round` rounds a half
towards positive infinity and C's `lroundf` rounds it away from zero, so -12.345 °C
was about to be reported as -12.34 here and -12.35 by a microcontroller in the same
room.

## When it hears nothing

Everything above the radio is covered by tests; the radio itself can only be
tested on a Pi with a tag in the room. Two things went wrong on the first
deployment, and both are worth knowing about because neither announces itself.

**Permissions.** BlueZ refuses `StartDiscovery` to a user outside the `bluetooth`
group, and `bluez-dbus` turns that refusal into a plain `false` with the exception
swallowed. A service running as its own user needs the group, and systemd fixes
the supplementary groups when the unit starts, not when `usermod` runs:

```ini
[Service]
User=ereader1
SupplementaryGroups=bluetooth
```

```bash
sudo systemctl daemon-reload && sudo systemctl restart <service>
```

The decisive check, without deploying anything, is to do what the application does
as the user it runs as:

```bash
sudo -u ereader1 busctl --system call \
    org.bluez /org/bluez/hci0 org.bluez.Adapter1 StartDiscovery
```

Silence means the permissions are right — follow it with `StopDiscovery` so it does
not sit there scanning. `InProgress` is also fine. `Access denied` is the answer.

**The shape of the bytes.** The advertisement's payload has D-Bus signature `ay`,
and what arrives on the Java side depends on the transport and the marshalling: a
`byte[]`, a boxed `Byte[]`, or a list of numbers, any of them possibly inside a
`Variant`, possibly nested. All of those are read; anything else is logged by
type rather than dropped. `BleScannerBytesTest` covers each shape.

So the scanner says what it sees. Once a minute, while it has decoded nothing:

```
Heard 18 device(s), none of them a RuuviTag.
Manufacturer ids seen: [0x004C, 0x012D, 0x0499]
```

That line separates three cases that otherwise look identical from the outside:
BlueZ hearing nothing at all, BlueZ hearing plenty but no Ruuvi (`0x0499` absent),
and Ruuvi data arriving but not being read (`0x0499` present, as above). A failure
in the polling loop is logged in full the first time and quietly after that.

The rest is real and verified: the application starts, the page renders, and a
packet built here decodes correctly with the server's own `PacketDecoder`.
