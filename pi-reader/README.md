# ScrewCloud Pi reader

The same job as the [Pico](../temperature-reader) and [ESP32](../esp32-s3-reader)
firmwares, in Java: listen for RuuviTags over Bluetooth, show them on a page, and
forward them to the ScrewCloud server in the same packet the microcontrollers
send.

It exists because a Raspberry Pi is often already there doing something else, and
because a reader that runs on the same network as the tags can keep showing the
temperature when the internet does not.

**Quarkus** for the runtime, **Vaadin** for the page, **bluez-dbus** for the
Bluetooth radio and [pi4j-drivers](https://github.com/mstahv/pi4j-drivers/tree/lr11xx-lora-driver)
for the LoRa one. Java 25, no database.

Java 25 rather than 21 because the LoRa driver reaches spidev and the GPIO
character device through the Foreign Function and Memory API, which became final
in 22 — which is also why there is no native library to build or install
alongside this.

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

### The LoRa driver needs a local build, for now

The `com.pi4j:pi4j-drivers:1.1.1-SNAPSHOT` dependency is not on Maven Central. The
LR11xx driver it carries is in review as a pull request, so until that is merged and
released it has to be built and installed by hand:

```bash
git clone -b lr11xx-lora-driver https://github.com/mstahv/pi4j-drivers.git
mvn -f pi4j-drivers/pom.xml install -DskipTests
```

<https://github.com/mstahv/pi4j-drivers/tree/lr11xx-lora-driver>

It is needed whether or not the LoRa radio is switched on, because the dependency is
compiled against either way. When the driver is released, the version in `pom.xml`
becomes an ordinary one and this section goes away.

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
| `screwcloud.thingy.enabled` | `false` | `true` to also read a Nordic Thingy:52 |
| `screwcloud.thingy.address` | *(empty)* | which Thingy, when more than one is in range |
| `screwcloud.lora.enabled` | `false` | `true` on a Pi with a Core1121 wired to it |
| `screwcloud.lora.frequency` | `868000000` | must match the sending firmware exactly |
| `screwcloud.lora.spreading-factor` | `7` | the same |
| `screwcloud.lora.crc` | `false` | the same |
| `screwcloud.names.file` | `~/.screwcloud-sensors.csv` | where the names live |

### Deploying to the Pi: boot2vm

Probably the easiest way to run this on a Pi — especially without a Java toolchain
on the Pi or much appetite for one anywhere — is
[boot2vm](https://github.com/mstahv/boot2vm). It is a single-file JBang tool: the
build runs on your workstation, and only the application itself goes over to the
Pi, where it runs as a systemd service that starts on boot and restarts on failure.

The workstation side needs a JDK, Maven and JBang. If none of that is installed
yet, [SDKMAN](https://sdkman.io) covers all three with one installer (macOS,
Linux, or WSL on Windows):

```bash
curl -s "https://get.sdkman.io" | bash     # then open a new terminal
sdk install java && sdk install maven && sdk install jbang
```

Then:

```bash
jbang app install https://github.com/mstahv/boot2vm/blob/main/Deploy.java

cd pi-reader
Deploy init        # asks for the Pi's address, sets it up, deploys
Deploy             # redeploys after a change: build here, rsync, restart
```

`Deploy init` asks a handful of questions; the app type is detected as Quarkus
from the build. For a Pi on a home network, answering `none` to the reverse proxy
is the sensible choice — the local page is then <http://pi-address:8080/>, and
there is no public DNS name for automatic HTTPS to work against anyway.
Redeployments are quick because only changed files are transferred: the dependency
jars in `quarkus-app/lib` rarely change, so an ordinary code change moves a few
hundred kilobytes.

The configuration above goes in through the same tool, and lives on the Pi rather
than in this repository:

```bash
Deploy env set SCREWCLOUD_DEVICE_ID=PI01 SCREWCLOUD_THINGY_ENABLED=true
Deploy env set JDK_JAVA_OPTIONS=--enable-native-access=ALL-UNNAMED   # for the LoRa driver
Deploy logs        # journalctl over SSH, to see what it thinks
```

Two things boot2vm cannot know about this application: the app user it creates
needs the Bluetooth and LoRa group memberships described in
[When it hears nothing](#when-it-hears-nothing) — `usermod` on the Pi, once — and
the LoRa build still needs the pi4j-drivers branch installed locally first, as
above. `Deploy init` writes its connection details to `vmhosting.conf`, which this
repository's `.gitignore` already keeps out of version control.

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

**The Thingy:52** is read too, when `screwcloud.thingy.enabled` is on, and
reported as a sensor of its own under `T` and twelve bits of its address — the
same derivation a RuuviTag gets, with a different letter so the two can never
collide.

It is the one device here that has to be **connected** to. Its environment
characteristics are notify-only in Nordic's firmware: not readable, and not in the
advertisement either, so there is no listen-only way to get the temperature. This
connects once, subscribes to temperature and humidity, and from then on polls the
value BlueZ caches from each notification — which looks exactly like polling
`ManufacturerData` for a Ruuvi advertisement and costs as little.

Three consequences, none of which announce themselves:

- **One host at a time.** A connected Thingy is not available to the nRF Thingy
  phone app. If the app takes it, this reader loses it and reconnects on the next
  poll; the two cannot both have it.
- **Connecting is slow and can fail**, especially while a discovery is running —
  and one always is, because the Ruuvi scanner needs it. That is why the Thingy has
  a thread of its own: a connect that blocks for seconds must not stall the
  advertisement polling.
- **A Thingy asleep does not advertise.** Press its button if nothing is found.

Everything downstream of the two radios works on a `Reading`, which is what let a
second kind of sensor arrive without the registry, the history, the cards or the
packet learning anything about it. Ruuvi's sequence number, movement counter,
battery voltage and pressure stay on `RuuviReading`, where the scanner that
understands them can use them.

**Relaying LoRa.** With `screwcloud.lora.enabled`, a second radio listens for
measurement packets over the air and passes them to the server **byte for byte**.
A device out of WiFi range therefore appears on the server as itself — its own
identifier, its own sensors, its own sequence numbers — rather than as readings
attributed to this machine, and nothing on the server had to be taught anything
for that to work. It is also why the packet is not decoded on the way through:
re-encoding it is a way to introduce a difference between what was sent and what
arrives, and the server already refuses what it cannot read.

The page shows what has arrived and how strongly, which is the number a field test
is made of — it is what changes as somebody walks away from the Pi with a node in
their hand.

The three radio settings must match the sending firmware **exactly**. There is no
negotiation in LoRa and no error when two ends disagree: a mismatched spreading
factor, a mismatched CRC setting and a missing antenna all sound alike, which is
silence. The defaults are the ones the link has been made to work with — the
values in `lora-node.ino`, which are Waveshare's example's — so change one end and
the other in the same breath, and one thing at a time. The sending end is
`pico-sleeper`, with `TRANSPORT` and the `LORA_*` values in its `config.h`.

Like the Bluetooth scanner, it degrades to a warning: a Pi with no radio wired to
it still runs everything else.

## Tests

```bash
mvn test
```

Seventy-three of them, and they are the part that can be checked without a Pi and
a tag on the table:

| | |
|---|---|
| `DataFormat5Test` | decoding, against **Ruuvi's own published test vectors** — this is the third implementation of that format in this repository, and a test written from my own reading of the spec would agree with my own misreading of it |
| `MeasurementPacketTest` | the bytes that leave the machine, against a packet written out by hand |
| `ProtocolSyncTest` | the constants and scaling, read out of the firmware's `Protocol.h`, so the three implementations cannot drift apart quietly |
| `TagRegistryTest` | out of order advertisements, staleness, two tags landing on one identifier, and a Thingy kept alongside them |
| `ThingyReadingTest` | the Thingy's two encodings, including below zero, and the identifier that keeps it apart from a RuuviTag |
| `SensorNamesTest` | the file, including commas, quotes and hand edits |
| `ReadingHistoryTest` | the sampling, the day long window, and one curve per tag |
| `BleScannerBytesTest` | every shape D-Bus might hand the advertisement over in |
| `StartupTest` | that the application boots at all, with the real `application.properties` — the one test that would have caught a config value which cannot be converted, and did not exist until one took the service down |
| `LocalViewTest` | the page itself, browserless: naming a tag, a missing value, a tag gone quiet, when a curve is worth drawing, and what a LoRa arrival says about its signal strength |

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

**The LoRa radio needs real group membership, not this.** Four groups, and by
`usermod`:

```bash
sudo usermod -aG spi,gpio,dialout,i2c ereader1
sudo systemctl restart <service>
```

Only two of those are about the radio: the kernel wants `spi` for
`/dev/spidev0.0` and `gpio` for `/dev/gpiochip0`, where reset, busy and DIO9 live.
The other two are Pi4J's, which the driver uses to reach both — it registers its
providers as one batch and refuses the batch unless the user is in every group any
of them might want, so an application using nothing but SPI and three GPIO lines
still needs the serial and I²C groups. `i2c` was found the hard way, after Pi4J's
own error message named only `gpio` and `dialout`.

And `SupplementaryGroups=` does **not** work for these, which is the trap:
Pi4J reads the group database looking for the user's name rather than asking what
groups the process is actually running with. The unit file satisfies the kernel and
not Pi4J. `usermod` writes `/etc/group` and satisfies both.

The failure looks like a classpath problem and is not one:

```
Pi4J provider [ffm-spi] could not be found.
Please include this 'provider' JAR in the classpath.
```

The definitive answer is a few lines earlier, from `FFMPermissionHelper` at ERROR.
See `lr1121-java/pi4j-migration.md` for the full account.

The other launch requirement is a JVM flag, since the driver reaches the kernel
through the Foreign Function and Memory API:

```bash
java --enable-native-access=ALL-UNNAMED -jar target/quarkus-app/quarkus-run.jar
```

Without it every start prints four warnings, and a future JDK will refuse the call
outright.

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
