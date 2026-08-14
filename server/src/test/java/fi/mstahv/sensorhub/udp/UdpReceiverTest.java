package fi.mstahv.sensorhub.udp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;

/**
 * What a rejected packet looks like in the log.
 *
 * <p>Written after a field failure that this line would have explained in a
 * second: a device reported "FAIL!" for an hour while the server logged "Unknown
 * protocol version 65" every five minutes. 65 is the letter A, and the packet was
 * an AT command that a modem had been fed as a payload — but the number alone does
 * not say that, and a stray packet off the internet looks exactly the same.
 */
class UdpReceiverTest {

    @Test
    void aPacketIsShownAsHexAndAsText() {
        byte[] packet = "AT+CIPSEND=0,16".getBytes(StandardCharsets.US_ASCII);

        String described = UdpReceiver.describe(packet, packet.length);

        assertTrue(described.startsWith("15 bytes: 41 54 2B "), described);
        assertTrue(described.contains("| AT+CIPSEND=0,16"), described);
    }

    /** A real measurement packet is recognisable by its shape alone. */
    @Test
    void aMeasurementPacketIsRecognisable() {
        byte[] packet = HexFormat.of().parseHex("0150493031010007523042460866" + "11AD");

        String described = UdpReceiver.describe(packet, packet.length);

        assertTrue(described.contains("01 50 49 30 31"), described);
        assertTrue(described.contains("| .PI01"), described);
    }

    /** Long junk is truncated: a flood of it must not become a flood of log. */
    @Test
    void aLongPacketIsCutShort() {
        byte[] packet = new byte[512];

        String described = UdpReceiver.describe(packet, packet.length);

        assertTrue(described.startsWith("512 bytes: "), described);
        assertTrue(described.endsWith("..."), described);
        assertEquals(24, described.chars().filter(c -> c == '.').count() - 3,
                "24 bytes shown, each unprintable one a dot, plus the ellipsis");
    }

    @Test
    void anEmptyPacketDoesNotBreakTheLine() {
        assertEquals("0 bytes: | ", UdpReceiver.describe(new byte[0], 0));
    }
}
