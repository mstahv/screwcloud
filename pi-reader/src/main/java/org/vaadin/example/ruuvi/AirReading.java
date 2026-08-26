package org.vaadin.example.ruuvi;

import java.time.Instant;

import org.vaadin.example.sensor.Reading;

/**
 * One advertisement from a Ruuvi Air, decoded from
 * <a href="https://docs.ruuvi.com/communication/bluetooth-advertisements">data
 * format 6</a>.
 *
 * <p>Temperature and humidity make it a {@link Reading} like any other, so the
 * registry, the history, the card and the packet to the server all work
 * unchanged. The air quality — particulate matter, CO₂, the VOC and NOx
 * indices — rides along for whoever asks for this type specifically; the wire
 * format to the server has no room for it yet, and what that would take is
 * worked through in {@code protocol-evolution.md}.
 *
 * <p>A value is null when the device sent the format's "invalid" marker for it,
 * which it does for every air sensor while warming up.
 *
 * @param mac              the <em>lower three bytes</em> of the address — all
 *                         the legacy advertisement has room for
 * @param temperature      degrees Celsius, or null
 * @param humidity         relative humidity in percent, or null
 * @param pressure         hectopascals, or null
 * @param pm25             PM2.5 in µg/m³, or null
 * @param co2              CO₂ in ppm, or null
 * @param voc              VOC index 0–500, or null
 * @param nox              NOx index 0–500, or null
 * @param luminosity       lux, or null; sent logarithmically, so coarse
 * @param soundAvgDba      A-weighted average sound level in dB, or null
 * @param calibrating      true while the device says its sensors are settling
 * @param sequence         increments per measurement, so the same advertisement
 *                         read twice can be told from a new one
 * @param rssi             signal strength as the receiver saw it, or null
 * @param receivedAt       when this reader heard it
 */
public record AirReading(byte[] mac, Double temperature, Double humidity, Double pressure,
                         Double pm25, Double co2, Double voc, Double nox,
                         Double luminosity, Double soundAvgDba, boolean calibrating,
                         int sequence, Short rssi, Instant receivedAt) implements Reading {

    /**
     * The identifier the server ties readings to: {@code R} and the low twelve
     * bits of the address — the same rule as every other Ruuvi device, applied
     * to the three bytes this format carries. The bits used are the lowest, so
     * the id is identical to what the full address would give.
     */
    @Override
    public String sensorId() {
        return "R%03X".formatted(((mac[1] & 0x0F) << 8) | (mac[2] & 0xFF));
    }

    /**
     * The three known bytes of the address, honestly short: the legacy
     * advertisement does not carry the rest. Everything local only needs the
     * key to be stable and distinct within one house, which this is.
     */
    @Override
    public String macAddress() {
        return "%02X:%02X:%02X".formatted(mac[0], mac[1], mac[2]);
    }
}
