package fi.mstahv.sensorhub.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import fi.mstahv.sensorhub.protocol.DeviceMeasurement;
import fi.mstahv.sensorhub.protocol.SensorMeasurement;

/**
 * Which reading stands for a whole device in the front page list.
 *
 * <p>The one line under a device name is what the list is scanned for, so it has
 * to be the reading somebody would look for. It used to be whichever sensor the
 * packet happened to list first, which on a device with the chip temperature
 * enabled was often the chip: a diagnostic that reads well above the air around
 * it, making a device in a cold room look like a warm one.
 */
class DeviceLinkCardTest {

    private static final Instant AT = Instant.parse("2026-08-14T09:00:00Z");

    @Test
    void aTagIsPreferredOverEverythingElse() {
        String summary = DeviceLinkCard.describe(packet(
                new SensorMeasurement("DHT", 22.0, 45.0),
                new SensorMeasurement("CPU", 48.0, null),
                new SensorMeasurement("REBF", 4.0, 80.0)));

        assertTrue(summary.startsWith("4.0 °C"), summary);
    }

    /** Even when the tag is last in the packet, which is where the firmware puts it. */
    @Test
    void theOrderInThePacketDoesNotDecide() {
        String summary = DeviceLinkCard.describe(packet(
                new SensorMeasurement("CPU", 48.0, null),
                new SensorMeasurement("REBF", 4.0, 80.0)));

        assertTrue(summary.startsWith("4.0 °C"), summary);
    }

    /** With no tag, anything measuring a room still beats the box measuring itself. */
    @Test
    void theChipComesAfterAWiredSensor() {
        String summary = DeviceLinkCard.describe(packet(
                new SensorMeasurement("CPU", 48.0, null),
                new SensorMeasurement("DHT", 22.0, 45.0)));

        assertTrue(summary.startsWith("22.0 °C"), summary);
    }

    /** And is shown when it is all there is, rather than showing nothing. */
    @Test
    void theChipIsBetterThanNoReadingAtAll() {
        String summary = DeviceLinkCard.describe(packet(
                new SensorMeasurement("CPU", 48.0, null)));

        assertTrue(summary.startsWith("48.0 °C"), summary);
    }

    /** Two tags: the first the device reported, as before. */
    @Test
    void betweenTwoTagsThePacketOrderStillDecides() {
        String summary = DeviceLinkCard.describe(packet(
                new SensorMeasurement("REBF", 4.0, 80.0),
                new SensorMeasurement("R1AC", 21.0, 45.0)));

        assertTrue(summary.startsWith("4.0 °C"), summary);
    }

    /** A sensor with no temperature is not a candidate, whatever it is. */
    @Test
    void aSensorWithNoTemperatureIsSkipped() {
        String summary = DeviceLinkCard.describe(packet(
                new SensorMeasurement("REBF", null, 80.0),
                new SensorMeasurement("DHT", 22.0, 45.0)));

        assertTrue(summary.startsWith("22.0 °C"), summary);
    }

    /** With nothing measured at all, the age is still worth saying. */
    @Test
    void aDeviceWithNoTemperaturesShowsOnlyItsAge() {
        String summary = DeviceLinkCard.describe(packet(
                new SensorMeasurement("REBF", null, 80.0)));

        assertTrue(summary.contains("ago") || summary.contains("now"), summary);
    }

    private static DeviceMeasurement packet(SensorMeasurement... sensors) {
        return new DeviceMeasurement("LAHT", 1, AT, List.of(sensors));
    }
}
