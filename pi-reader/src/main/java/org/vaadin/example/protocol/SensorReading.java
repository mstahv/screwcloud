package org.vaadin.example.protocol;

/**
 * One sensor's reading as the packet carries it: an identifier and whichever of
 * these the sensor actually measured.
 *
 * <p>Null means the sensor did not report the value, which is not the same as zero
 * — a RuuviTag Pro 2in1 measures no humidity at all, and zero degrees is a real
 * temperature. A null costs nothing on the wire now: the packet leaves the field
 * out entirely rather than sending a sentinel in its place.
 *
 * @param id at most {@value Protocol#ID_SIZE} characters, for example "R0BF"
 * @param temperature degrees Celsius, or null
 * @param humidity relative humidity in percent, or null
 * @param co2 carbon dioxide in ppm, or null — only a Ruuvi Air has it
 * @param pm25 particulates under 2.5 µm in µg/m³, or null
 * @param batteryVoltage the sensor's own battery in volts, or null — a Ruuvi Air
 *        runs off the mains and has none to report
 */
public record SensorReading(String id, Double temperature, Double humidity,
                            Double co2, Double pm25, Double batteryVoltage) {

    /** A sensor that measures only the two things most sensors measure. */
    public SensorReading(String id, Double temperature, Double humidity) {
        this(id, temperature, humidity, null, null, null);
    }

    /** The air fields and no battery, which is what a Ruuvi Air has. */
    public SensorReading(String id, Double temperature, Double humidity, Double co2, Double pm25) {
        this(id, temperature, humidity, co2, pm25, null);
    }

    public SensorReading {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("A sensor reading needs an identifier");
        }
        if (id.length() > Protocol.ID_SIZE) {
            throw new IllegalArgumentException(
                    "Sensor id \"%s\" is longer than the %d characters the packet has"
                            .formatted(id, Protocol.ID_SIZE));
        }
    }
}
