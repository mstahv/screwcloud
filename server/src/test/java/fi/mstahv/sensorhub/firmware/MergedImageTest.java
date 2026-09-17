package fi.mstahv.sensorhub.firmware;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The trimming has one job — cut the erased-flash padding and nothing else — and
 * one way to be dangerous, which is cutting a byte that was firmware. So the
 * cases are about where the last real byte falls against the sector boundary.
 */
class MergedImageTest {

    @TempDir
    Path directory;

    private static final int SECTOR = 4096;

    private Path image(byte[] content) throws IOException {
        Path file = directory.resolve("sketch.ino.merged.bin");
        Files.write(file, content);
        return file;
    }

    private static byte[] padded(int totalLength, byte[] content) {
        byte[] image = new byte[totalLength];
        Arrays.fill(image, (byte) 0xFF);
        System.arraycopy(content, 0, image, 0, content.length);
        return image;
    }

    private static byte[] firmwareOf(int length) {
        byte[] firmware = new byte[length];
        for (int i = 0; i < length; i++) {
            firmware[i] = (byte) (i * 31 + 7);
        }
        // The last byte is what the trim looks for; make sure it is not padding.
        firmware[length - 1] = 0x42;
        return firmware;
    }

    private static byte[] trimmed(Path file) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        MergedImage.writeTrimmed(file, out);
        return out.toByteArray();
    }

    @Test
    void thePaddingGoesAndTheFirmwareStays() throws IOException {
        byte[] firmware = firmwareOf(3 * SECTOR + 100);
        Path file = image(padded(4 * 1024 * 1024, firmware));

        byte[] result = trimmed(file);

        assertEquals(4 * SECTOR, result.length, "cut on the next sector boundary");
        assertArrayEquals(firmware, Arrays.copyOf(result, firmware.length));
        for (int i = firmware.length; i < result.length; i++) {
            assertEquals((byte) 0xFF, result[i], "the rest of the last sector is padding");
        }
    }

    @Test
    void aLastByteExactlyOnTheBoundaryIsNotRoundedUpAgain() throws IOException {
        byte[] firmware = firmwareOf(2 * SECTOR);
        Path file = image(padded(64 * SECTOR, firmware));

        assertEquals(2 * SECTOR, trimmed(file).length);
    }

    @Test
    void firmwareThatEndsInErasedBytesKeepsThemIfTheyAreInsideTheLastSector() throws IOException {
        /*
           Flash padding inside the application — a section aligned with 0xFF —
           is indistinguishable from the tail, and cutting into the last sector
           would lose it. The rounding is what protects it: a run of 0xFF that
           ends inside a sector is kept up to the sector's end.
        */
        byte[] firmware = firmwareOf(SECTOR + 10);
        Path file = image(padded(8 * SECTOR, firmware));

        byte[] result = trimmed(file);
        assertEquals(2 * SECTOR, result.length);
    }

    @Test
    void anImageWithNoPaddingIsCopiedWhole() throws IOException {
        byte[] firmware = firmwareOf(5 * SECTOR + 3);
        Path file = image(firmware);

        assertArrayEquals(firmware, trimmed(file));
    }

    @Test
    void aFileShorterThanASectorIsCopiedWhole() throws IOException {
        byte[] firmware = firmwareOf(300);
        Path file = image(firmware);

        assertArrayEquals(firmware, trimmed(file));
    }

    @Test
    void nothingButPaddingLeavesOneSector() throws IOException {
        Path file = image(padded(3 * SECTOR, new byte[0]));

        assertEquals(SECTOR, trimmed(file).length);
    }
}
