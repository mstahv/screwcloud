package fi.mstahv.sensorhub.ui;

import com.vaadin.flow.component.html.ListItem;
import com.vaadin.flow.component.html.OrderedList;
import com.vaadin.flow.component.html.Section;
import com.vaadin.flow.component.html.UnorderedList;

import fi.mstahv.sensorhub.firmware.Board;

/**
 * How an ESP32 takes its firmware, for somebody who has never done it.
 *
 * <p>An ESP32 has no drive to drop a file on. It takes firmware over its serial
 * port, which used to mean installing a tool and typing a command with an address
 * in it — and now means pressing a button on this page, because a browser can
 * open a serial port itself ({@link SerialFlasher}). The steps here are the ones
 * around that button: what to plug in, what to pick when the browser asks, and
 * what to do when the board is not listed.
 *
 * <p>The command-line way is kept, in one line, for the reader on a browser that
 * cannot do this — Safari and every phone — or who would rather. It is what the
 * downloaded file is for.
 *
 * <p>The light is the RGB one on the board, and the colours are named because on
 * this board they carry the meaning: the same three rhythms as the Pico's single
 * light, each in its own colour.
 */
class Esp32FlashingInstructions extends Section {

    /** @param board which ESP32 this is for; the command line names the chip */
    Esp32FlashingInstructions(Board board) {
        OrderedList steps = new OrderedList(
                new ListItem("Plug the board into this computer with a USB cable. On a "
                        + "Waveshare Zero board, use the USB-C port."),
                new ListItem("Press \"Connect and flash\" above. The browser asks which serial "
                        + "port to use — pick the one that appeared when you plugged the board "
                        + "in. It is often called something like \"USB JTAG/serial debug unit\"."),
                new ListItem("Wait for the bar to fill. It takes about half a minute, and the "
                        + "board restarts by itself when it is done."));

        UnorderedList trouble = new UnorderedList(
                new ListItem("Nothing in the list, or the connection fails: hold the board's "
                        + "BOOT button while plugging in the cable, then try again. That puts "
                        + "it into download mode, which is what a board with a broken firmware "
                        + "needs."),
                new ListItem("Still nothing: try another cable. A charge-only cable looks the "
                        + "same and carries no data."),
                new ListItem("The button is not offered at all: this browser cannot open serial "
                        + "ports. Use Chrome or Edge on a computer, or download the file and "
                        + "write it with esptool:"));

        SecondaryText command = new SecondaryText(
                "esptool.py --chip %s write_flash 0x0 %s".formatted(
                        board.esptoolChip(), board.fileName("<id>")));
        // No dedicated Style method for the font family, so the raw property.
        command.getStyle().set("font-family", "monospace");
        command.getStyle().setDisplay(com.vaadin.flow.dom.Style.Display.BLOCK);
        command.getStyle().setMarginLeft("2.5rem");

        UnorderedList light = new UnorderedList(
                new ListItem("Green, two quick blinks then a pause — it is sending. All well."),
                new ListItem("Blue, a slow steady blink — nothing sent yet. Normal for the "
                        + "first minute or so after starting."),
                new ListItem("Red, fast flickering — it cannot send. Usually the WiFi name or "
                        + "password; build again and check them."));

        add(new SectionHeading("Getting it onto the device"), steps,
                new SectionHeading("If it does not go smoothly"), trouble, command,
                new SectionHeading("What the light tells you"),
                new Hint("The small coloured light on the board itself, once it has started up."),
                light);
    }
}
