package fi.mstahv.sensorhub.firmware;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import fi.mstahv.sensorhub.validation.DeviceId;
import fi.mstahv.sensorhub.validation.Ssid;
import fi.mstahv.sensorhub.validation.WifiPassphrase;

/**
 * What somebody asked to have built.
 *
 * <p>The constraints here are for the reader's benefit rather than the server's:
 * a password of three characters is a typo, and the right moment to say so is on
 * the field before a build is queued, not by way of a device that never connects.
 * They are not what keeps the build host safe — {@link ConfigHeader} is, by
 * emitting every value as bytes, and that is deliberately a separate job from
 * this one. A rule tight enough to make source text safe would have to reject
 * passwords people genuinely have.
 *
 * <p>The password is the one field here that is a secret. It lives in this record
 * for as long as a build takes and goes nowhere else — not to the database, not
 * to a log line, and not into {@link #toString()}, which is overridden below for
 * exactly that reason.
 *
 * @param board which board the firmware is compiled for
 * @param deviceId what the server will file the readings under
 * @param ssid the network the device should join
 * @param password its passphrase, or empty for an open network
 * @param sendIntervalMinutes how often the device reports
 * @param transport which radio to use, or to decide at boot — the Pico's
 *        question; a board with one radio ignores it
 * @param sleepBetweenSends whether the chip sleeps between sends — the ESP32's
 *        question; see {@link Board#offersSleep()}
 */
public record FirmwareRequest(
        @NotNull Board board,
        @NotBlank @DeviceId String deviceId,
        @NotBlank @Ssid String ssid,
        @WifiPassphrase String password,
        @Min(1) @Max(60) int sendIntervalMinutes,
        @NotNull FirmwareTransport transport,
        boolean sleepBetweenSends) {

    /** What the device's config.h ships with, and a sensible default here too. */
    public static final int DEFAULT_SEND_INTERVAL_MINUTES = 5;

    public FirmwareRequest {
        deviceId = deviceId == null ? null : deviceId.strip().toUpperCase();
        password = password == null ? "" : password;
    }

    /**
     * Without the password.
     *
     * <p>Records generate a {@code toString} that prints every component, which
     * for this one would put a WiFi password into the first log line or exception
     * message that touched it. That is not a thing to leave to whoever writes the
     * next {@code log.debug}.
     */
    @Override
    public String toString() {
        return "FirmwareRequest[board=%s, deviceId=%s, ssid=%s, password=%s, "
                .formatted(board, deviceId, ssid, password.isEmpty() ? "<none>" : "<hidden>")
                + "sendIntervalMinutes=%d, transport=%s, sleepBetweenSends=%s]"
                .formatted(sendIntervalMinutes, transport, sleepBetweenSends);
    }
}
