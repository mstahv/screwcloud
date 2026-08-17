package org.vaadin.example.thingy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import org.vaadin.example.ruuvi.RuuviReading;

/**
 * What can be checked without a Thingy on the desk: the two encodings and the
 * identifier.
 *
 * <p>The connecting, subscribing and reconnecting in {@link ThingyReader} cannot
 * be tested here — that needs BlueZ and the device — but the decoding is where a
 * wrong answer would be silent. A temperature read with the halves the wrong way
 * round is still a plausible-looking number.
 */
class ThingyReadingTest {

    /**
     * Nordic sends a signed whole degree and an unsigned hundredths part, in that
     * order. 21 and 50 is 21.50 °C.
     */
    @Test
    void temperatureIsAWholeDegreeAndHundredths() {
        assertEquals(Optional.of(21.5), ThingyReading.decodeTemperature(new byte[] {21, 50}));
        assertEquals(Optional.of(25.17), ThingyReading.decodeTemperature(new byte[] {25, 17}));
    }

    /** A whole number of degrees has no hundredths, and must not gain any. */
    @Test
    void aWholeNumberOfDegreesStaysWhole() {
        assertEquals(Optional.of(0.0), ThingyReading.decodeTemperature(new byte[] {0, 0}));
        assertEquals(Optional.of(20.0), ThingyReading.decodeTemperature(new byte[] {20, 0}));
    }

    /**
     * Below zero the hundredths are a distance from the whole number rather than
     * something added to it, so -5 and 25 is -5.25 rather than -4.75.
     *
     * <p>Nordic's own firmware is ambiguous here — it truncates towards zero and
     * then casts a negative remainder to an unsigned byte — so this is a choice,
     * and it is the one a person reading those two numbers would make.
     */
    @Test
    void belowZeroTheHundredthsMoveAwayFromZero() {
        assertEquals(Optional.of(-5.25), ThingyReading.decodeTemperature(new byte[] {-5, 25}));
        assertEquals(Optional.of(-1.0), ThingyReading.decodeTemperature(new byte[] {-1, 0}));
    }

    /**
     * A temperature above 127 °C cannot be sent, but one below -128 cannot either,
     * and the byte is signed — so the freezer end is what matters and it reaches
     * far enough.
     */
    @Test
    void theColdEndOfTheRangeSurvives() {
        assertEquals(Optional.of(-40.0), ThingyReading.decodeTemperature(new byte[] {-40, 0}));
    }

    /** Nothing has been notified yet, which is the state for one poll after connecting. */
    @Test
    void anEmptyOrShortValueIsNoReadingRatherThanZero() {
        assertTrue(ThingyReading.decodeTemperature(new byte[] {}).isEmpty());
        assertTrue(ThingyReading.decodeTemperature(new byte[] {21}).isEmpty(),
                "one byte is half a temperature, and half of one is not a reading");
        assertTrue(ThingyReading.decodeTemperature(null).isEmpty());
        assertTrue(ThingyReading.decodeHumidity(new byte[] {}).isEmpty());
    }

    /** Humidity is one unsigned byte of whole percent, so it reaches 100. */
    @Test
    void humidityIsWholePercent() {
        assertEquals(Optional.of(45.0), ThingyReading.decodeHumidity(new byte[] {45}));
        assertEquals(Optional.of(100.0), ThingyReading.decodeHumidity(new byte[] {100}));
    }

    /** The same twelve bits a RuuviTag uses, with a T in front. */
    @Test
    void theIdentifierIsTAndTwelveBitsOfTheAddress() {
        assertEquals("T45F", thingyAt("E5:6C:AB:12:34:5F").sensorId());
        assertEquals("T000", thingyAt("00:00:00:00:00:00").sensorId());
        assertEquals("TFFF", thingyAt("00:00:00:00:FF:FF").sensorId());
    }

    /**
     * The letter is the whole point of the letter.
     *
     * <p>A Thingy and a RuuviTag whose addresses end in the same twelve bits would
     * otherwise arrive at the server under one identifier, which it would file as
     * one sensor that could not make up its mind about the temperature.
     */
    @Test
    void aThingyAndARuuviTagOnTheSameTwelveBitsStayApart() {
        String address = "E5:6C:AB:12:34:5F";
        RuuviReading ruuvi = new RuuviReading(
                new byte[] {(byte) 0xE5, 0x6C, (byte) 0xAB, 0x12, 0x34, 0x5F},
                21.0, null, null, null, null, 0, 1, (short) -60, Instant.now());

        assertEquals("R45F", ruuvi.sensorId());
        assertEquals("T45F", thingyAt(address).sensorId());
        assertNotEquals(ruuvi.sensorId(), thingyAt(address).sensorId());
    }

    /** An address in some shape BlueZ would never produce must not throw on a poll thread. */
    @Test
    void anAddressThatIsNotAnAddressIsStillAReading() {
        assertEquals("T000", thingyAt("not-an-address").sensorId());
        assertEquals("T000", thingyAt("ZZ:ZZ:ZZ:ZZ:ZZ:ZZ").sensorId());
    }

    private static ThingyReading thingyAt(String address) {
        return new ThingyReading(address, 21.0, 45.0, (short) -60, Instant.now());
    }
}
