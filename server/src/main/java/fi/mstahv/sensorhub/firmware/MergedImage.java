package fi.mstahv.sensorhub.firmware;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Trims the padding off a merged ESP32 image.
 *
 * <p>The ESP32 core's build writes {@code <sketch>.merged.bin}: the bootloader,
 * the partition table and the application at their offsets, padded with
 * {@code 0xFF} out to the size of the whole flash. The padding is what erased
 * flash reads as, so writing it changes nothing — it only takes time. On a four
 * megabyte board it is most of the file: the firmware is a megabyte and a bit,
 * and the browser would spend the rest of a minute writing blank.
 *
 * <p>So the tail is cut, back to the last byte that is not {@code 0xFF} and then
 * up to the next flash sector, which is what the flasher writes in anyway. The
 * result is still one image for address zero and still says nothing about the
 * layout inside it — it is the same file, shorter.
 *
 * <p>Reading backwards from the end rather than the whole file forwards, because
 * the file is mostly padding and the interesting byte is the last one before it.
 */
final class MergedImage {

    /** The erased-flash byte, which is what the padding consists of. */
    private static final byte PADDING = (byte) 0xFF;

    /** The flasher erases and writes in these; the cut lands on a boundary. */
    private static final int SECTOR = 4096;

    private MergedImage() {
    }

    /**
     * Copies the image without its trailing padding.
     *
     * @return how many bytes were written
     */
    static long writeTrimmed(Path merged, OutputStream out) throws IOException {
        long length = trimmedLength(merged);
        try (InputStream in = Files.newInputStream(merged)) {
            return in.transferTo(new LimitedOutput(out, length));
        }
    }

    /**
     * How long the image is once the padding is gone: the last non-padding byte,
     * rounded up to a sector. Never zero — an image that was nothing but padding
     * is kept as one sector rather than as no file at all, and a file shorter
     * than a sector is kept whole.
     */
    static long trimmedLength(Path merged) throws IOException {
        try (SeekableByteChannel channel = Files.newByteChannel(merged, StandardOpenOption.READ)) {
            long size = channel.size();
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(SECTOR);
            long position = size;
            while (position > 0) {
                long start = Math.max(0, position - SECTOR);
                buffer.clear();
                buffer.limit((int) (position - start));
                channel.position(start);
                while (buffer.hasRemaining() && channel.read(buffer) > 0) {
                    // fill the whole window
                }
                for (int i = buffer.position() - 1; i >= 0; i--) {
                    if (buffer.get(i) != PADDING) {
                        long lastContent = start + i + 1;
                        return Math.min(size, roundUpToSector(lastContent));
                    }
                }
                position = start;
            }
            return Math.min(size, SECTOR);
        }
    }

    private static long roundUpToSector(long length) {
        return (length + SECTOR - 1) / SECTOR * SECTOR;
    }

    /** Writes the first {@code limit} bytes and drops the rest. */
    private static final class LimitedOutput extends OutputStream {

        private final OutputStream out;
        private long remaining;

        LimitedOutput(OutputStream out, long limit) {
            this.out = out;
            this.remaining = limit;
        }

        @Override
        public void write(int b) throws IOException {
            if (remaining > 0) {
                out.write(b);
                remaining--;
            }
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            int allowed = (int) Math.min(length, remaining);
            if (allowed > 0) {
                out.write(bytes, offset, allowed);
                remaining -= allowed;
            }
        }
    }
}
