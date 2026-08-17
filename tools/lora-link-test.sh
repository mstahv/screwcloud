#!/usr/bin/env bash
#
# Drives a LoRa link test on two remote devices from this machine.
#
#   tools/lora-link-test.sh <receiving-host> <transmitting-host> [seconds]
#
# A radio cannot hear itself, so proving the driver needs two of them and the
# listener has to be up before anything is sent. That choreography is the whole
# reason this script exists — the pieces underneath are just the driver project's
# own Lr11xxLinkCheck, which has a main for exactly this.
#
# What it does:
#
#   1. builds the driver project here, with its test classpath
#   2. rsyncs the classes and jars to both devices
#   3. starts the listener, waits for it to settle, then starts the transmitter
#   4. prints both logs and reports what arrived
#
# Nothing is installed on the devices beyond a JRE. No checkout, no Maven, no
# warm ~/.m2 — which matters, because Maven on a Pi is slow enough to discourage
# running the test at all, and a test that is tedious is a test nobody runs.
#
# The devices need the usual group memberships for Pi4J's FFM provider:
#
#   sudo usermod -aG spi,gpio,dialout,i2c <user>
#
# and the antennas on before power.

set -euo pipefail

RECEIVER=${1:?usage: $0 <receiving-host> <transmitting-host> [seconds]}
TRANSMITTER=${2:?usage: $0 <receiving-host> <transmitting-host> [seconds]}
SECONDS_TO_RUN=${3:-30}

DRIVERS_DIR=${DRIVERS_DIR:-pi4j-drivers}
REMOTE_DIR=${REMOTE_DIR:-/tmp/lr11xx}
MAIN=com.pi4j.drivers.radio.lora.lr11xx.Lr11xxLinkCheck

# Both ends must agree on these. There is no negotiation in LoRa and no error
# when two ends disagree, so they are set once here and sent to both.
RADIO_OPTS=${RADIO_OPTS:--Dlr11xx.frequency=868000000 -Dlr11xx.sf=7}

say() { printf '\n=== %s ===\n' "$1"; }

say "Building $DRIVERS_DIR"
(cd "$DRIVERS_DIR" && ./mvnw -q test-compile dependency:copy-dependencies -DincludeScope=test)

for host in "$RECEIVER" "$TRANSMITTER"; do
    say "Copying to $host"
    ssh "$host" "mkdir -p $REMOTE_DIR"
    rsync -a --delete \
        "$DRIVERS_DIR/target/classes" \
        "$DRIVERS_DIR/target/test-classes" \
        "$DRIVERS_DIR/target/dependency" \
        "$host:$REMOTE_DIR/"
done

run_role() {
    local host=$1 role=$2
    # shellcheck disable=SC2029  # the remote command is meant to expand here
    ssh "$host" "cd $REMOTE_DIR && java --enable-native-access=ALL-UNNAMED \
        $RADIO_OPTS -Dlr11xx.seconds=$SECONDS_TO_RUN \
        -cp 'classes:test-classes:dependency/*' $MAIN $role"
}

say "Listening on $RECEIVER for ${SECONDS_TO_RUN}s"
run_role "$RECEIVER" receive > /tmp/lora-receive.log 2>&1 &
receiver_pid=$!

# The receiver has a Pi4J context to build and a radio to configure before it is
# actually listening. Sending into that gap is the easiest way to conclude the
# link is broken when it is not.
sleep 5

say "Transmitting from $TRANSMITTER"
run_role "$TRANSMITTER" transmit 2>&1 | sed 's/^/  [tx] /' || true

wait "$receiver_pid" && received=0 || received=$?

say "Receiver log"
sed 's/^/  [rx] /' /tmp/lora-receive.log

if [ "$received" -eq 0 ]; then
    say "Link is up"
else
    say "Nothing arrived"
    cat <<'EOF'
  Compare the two ends before suspecting the radio: frequency, spreading factor
  and CRC have to match exactly, and a mismatch sounds exactly like a missing
  antenna. Run the single-radio check on each device first:

      ssh <host> "cd /tmp/lr11xx && java -cp 'classes:test-classes:dependency/*' \
          com.pi4j.drivers.radio.lora.lr11xx.Lr11xxLinkCheck check"
EOF
    exit 1
fi
