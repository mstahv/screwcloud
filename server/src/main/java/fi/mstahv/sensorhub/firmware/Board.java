package fi.mstahv.sensorhub.firmware;

import java.util.Arrays;
import java.util.List;

/**
 * A board this server can build firmware for, and everything that differs
 * between two of them.
 *
 * <p>The differences are all here, so that {@link FirmwareBuilds} and the page
 * can be written once. What varies is the sketch that gets compiled, the core and
 * the fully qualified board name arduino-cli wants for it, what the compiler
 * leaves behind and how that file gets onto the board — a Pico takes a
 * {@code .uf2} by drag and drop, an ESP32 takes a binary over its serial port.
 *
 * <p>The ESP32 image is a <b>merged</b> binary rather than the bare application:
 * the bootloader, the partition table and the application at their own offsets,
 * laid out so that the whole thing is written at address zero. That is what makes
 * it one file, which is what lets a browser flash it without knowing anything
 * about ESP32 memory layout. See {@link MergedImage} for the one thing done to it
 * on the way.
 */
public enum Board {

    /**
     * The full firmware: Ruuvi devices, the optional wired sensor and display,
     * WiFi or NB-IoT. The radio is chosen at build time.
     */
    PICO_2_W("Raspberry Pi Pico 2 W", "temperature-reader",
            "rp2040:rp2040", "rp2040",
            "rp2040:rp2040:rpipico2w:ipbtstack=ipv4btcble",
            ".uf2", "screwcloud-%s.uf2", true, false, Flashing.UF2_DRIVE),

    /**
     * The minimal firmware: Ruuvi devices and WiFi, nothing wired to the board.
     *
     * <p>The board options are the ones a Waveshare ESP32-S3-Zero wants and any
     * ESP32-S3 with 4 MB of flash accepts: the USB serial console on from boot,
     * because the board has no separate USB-UART chip and the serial monitor
     * would otherwise be silent; and the large application partition, because
     * BLE and WiFi together do not fit the default 1.2 MB one with anything to
     * spare.
     */
    ESP32_S3("ESP32-S3", "esp32-s3-reader",
            "esp32:esp32", "esp32",
            "esp32:esp32:esp32s3:CDCOnBoot=cdc,PartitionScheme=huge_app,FlashSize=4M",
            ".merged.bin", "screwcloud-%s-esp32-s3.bin", false, true, Flashing.SERIAL);

    /** How a built image gets onto the board. */
    public enum Flashing {
        /** Hold BOOTSEL, plug in, copy the file onto the drive that appears. */
        UF2_DRIVE,
        /** Over the serial port, from the browser or with esptool. */
        SERIAL
    }

    private final String caption;
    private final String sketch;
    private final String core;
    private final String packageDirectory;
    private final String fqbn;
    private final String imageSuffix;
    private final String fileNamePattern;
    private final boolean choosesTransport;
    private final boolean padded;
    private final Flashing flashing;

    Board(String caption, String sketch, String core, String packageDirectory, String fqbn,
          String imageSuffix, String fileNamePattern, boolean choosesTransport, boolean padded,
          Flashing flashing) {
        this.caption = caption;
        this.sketch = sketch;
        this.core = core;
        this.packageDirectory = packageDirectory;
        this.fqbn = fqbn;
        this.imageSuffix = imageSuffix;
        this.fileNamePattern = fileNamePattern;
        this.choosesTransport = choosesTransport;
        this.padded = padded;
        this.flashing = flashing;
    }

    /** What the form calls it. */
    public String caption() {
        return caption;
    }

    /** The sketch directory in the repository, which is also the sketch's name. */
    public String sketch() {
        return sketch;
    }

    /** The arduino-cli core a usable install has, as {@code core list} names it. */
    public String core() {
        return core;
    }

    /**
     * The directory under {@code packages/} in arduino-cli's data directory that
     * exists once the core is installed — the cheapest honest check that it is.
     */
    public String packageDirectory() {
        return packageDirectory;
    }

    /** What {@code arduino-cli compile --fqbn} is given. */
    public String fqbn() {
        return fqbn;
    }

    /** How the file the compiler leaves in the build directory ends. */
    public String imageSuffix() {
        return imageSuffix;
    }

    /** What the reader's downloaded file is called, given the device identifier. */
    public String fileName(String deviceId) {
        return fileNamePattern.formatted(deviceId.toLowerCase());
    }

    /**
     * Whether the sketch has the {@code TRANSPORT_*} choice in its config.h.
     *
     * <p>The Pico can carry an NB-IoT modem and asks; the ESP32 has WiFi and
     * nothing else, so its template has no such lines and {@link ConfigHeader}
     * must not go looking for them.
     */
    public boolean choosesTransport() {
        return choosesTransport;
    }

    /**
     * Whether the compiler pads the image out to the size of the whole flash.
     *
     * <p>The ESP32 core does — its merged binary is four megabytes of which about
     * a third is firmware and the rest is the erased-flash byte. The padding
     * flashes correctly and slowly, and is trimmed off; see {@link MergedImage}.
     */
    public boolean padded() {
        return padded;
    }

    public Flashing flashing() {
        return flashing;
    }

    /** In the order the form lists them, which is the order declared here. */
    public static List<Board> all() {
        return Arrays.asList(values());
    }
}
