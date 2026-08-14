package org.vaadin.example.ruuvi;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.freedesktop.dbus.types.Variant;
import org.junit.jupiter.api.Test;

/**
 * Reading an advertisement's bytes out of whatever D-Bus hands over.
 *
 * <p>Written after a Pi reported hearing thirteen devices, one of them advertising
 * under Ruuvi's company id, and finding no RuuviTags: the id was read correctly
 * and the payload beside it was not a {@code byte[]}, so it was dropped without a
 * word. The signature says {@code ay}; what arrives on the Java side depends on
 * the transport and the marshalling, and a guess that is wrong here is invisible
 * everywhere else.
 */
class BleScannerBytesTest {

    private static final byte[] EXPECTED = {0x05, 0x12, (byte) 0xFC};

    @Test
    void aByteArrayIsTakenAsItIs() {
        assertArrayEquals(EXPECTED, BleScanner.toBytes(EXPECTED).orElseThrow());
    }

    @Test
    void aBoxedArrayIsUnboxed() {
        Byte[] boxed = {0x05, 0x12, (byte) 0xFC};

        assertArrayEquals(EXPECTED, BleScanner.toBytes(boxed).orElseThrow());
    }

    @Test
    void aListOfBytesIsCollected() {
        List<Byte> list = List.of((byte) 0x05, (byte) 0x12, (byte) 0xFC);

        assertArrayEquals(EXPECTED, BleScanner.toBytes(list).orElseThrow());
    }

    /** Values above 127 must survive a list typed as something wider than a byte. */
    @Test
    void aListOfWiderNumbersKeepsTheHighBytes() {
        List<Integer> list = List.of(0x05, 0x12, 0xFC);

        assertArrayEquals(EXPECTED, BleScanner.toBytes(list).orElseThrow());
    }

    @Test
    void aVariantIsUnwrapped() {
        assertArrayEquals(EXPECTED,
                BleScanner.toBytes(new Variant<>(EXPECTED)).orElseThrow());
    }

    /** Variants can nest, and one level of unwrapping was not enough. */
    @Test
    void nestedVariantsAreUnwrapped() {
        assertArrayEquals(EXPECTED,
                BleScanner.toBytes(new Variant<>(new Variant<>(EXPECTED))).orElseThrow());
    }

    @Test
    void variantsInsideAListAreUnwrappedToo() {
        List<Variant<Byte>> list = List.of(
                new Variant<>((byte) 0x05), new Variant<>((byte) 0x12), new Variant<>((byte) 0xFC));

        assertArrayEquals(EXPECTED, BleScanner.toBytes(list).orElseThrow());
    }

    @Test
    void anEmptyPayloadIsStillAnAnswer() {
        assertArrayEquals(new byte[0], BleScanner.toBytes(new byte[0]).orElseThrow());
    }

    /** Anything else is empty rather than a guess — and the scanner logs the type. */
    @Test
    void somethingThatIsNotBytesIsNotInvented() {
        assertTrue(BleScanner.toBytes("not bytes").isEmpty());
        assertTrue(BleScanner.toBytes(null).isEmpty());
        assertTrue(BleScanner.toBytes(List.of("a", "b")).isEmpty());
    }
}
