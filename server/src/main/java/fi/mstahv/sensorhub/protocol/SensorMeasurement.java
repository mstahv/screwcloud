package fi.mstahv.sensorhub.protocol;

/**
 * One sensor's reading. A value is null when the sensor did not provide it — a
 * RuuviTag Pro 2in1, for instance, does not measure humidity at all.
 *
 * @param sensorId at most 4 characters, for example "DHT" or "RBF"
 * @param temperature in degrees Celsius, null if missing
 * @param humidity relative humidity in percent, null if missing
 */
public record SensorMeasurement(String sensorId, Double temperature, Double humidity) {
}
