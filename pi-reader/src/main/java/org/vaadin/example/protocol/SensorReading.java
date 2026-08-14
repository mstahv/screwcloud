package org.vaadin.example.protocol;

/**
 * One sensor's reading as the packet carries it: an identifier and the two values
 * the format has room for.
 *
 * <p>Null means the sensor did not report the value, which is not the same as zero
 * — a RuuviTag Pro 2in1 measures no humidity at all, and zero degrees is a real
 * temperature.
 *
 * @param id at most {@value Protocol#ID_SIZE} characters, for example "R0BF"
 * @param temperature degrees Celsius, or null
 * @param humidity relative humidity in percent, or null
 */
public record SensorReading(String id, Double temperature, Double humidity) {

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
