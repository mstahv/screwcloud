package fi.mstahv.sensorhub.ui;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.ClientCallable;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dependency.JsModule;
import com.vaadin.flow.component.dependency.NpmPackage;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Section;
import com.vaadin.flow.component.progressbar.ProgressBar;
import com.vaadin.flow.dom.Style;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import fi.mstahv.sensorhub.firmware.BuildJob;

/**
 * Writes a built ESP32 image onto a board plugged into the reader's computer,
 * from the browser.
 *
 * <p>The browser does the talking: the Web Serial API opens the port the reader
 * picks, and esptool-js — the flasher Espressif publishes for exactly this —
 * speaks the ESP32's bootloader protocol over it. The server's part is to hand
 * over the image, which it does the same way the download link does, through a
 * handler bound to this session. Nothing about the board's memory layout lives
 * here: the image is written at address zero and knows its own layout.
 *
 * <h2>The click stays in the browser</h2>
 *
 * <p>Asking for a serial port is allowed only while the browser is handling a
 * user gesture, and a click that has been to the server and back is not one any
 * more — Chrome shows no dialog and says why only in the console. So the button
 * has no server-side click listener. The script attaches its own to the button's
 * element when this attaches, asks for the port first thing inside it, and only
 * then tells the server that a flash has started. Every word the reader sees
 * still comes from here; the browser just gets to ask its question in time.
 *
 * <h2>Chrome and Edge, on a computer</h2>
 *
 * <p>Web Serial is what makes this possible and it is not everywhere: Safari does
 * not have it and neither does any browser on a phone. Where it is missing the
 * button is not offered; the reader is told so and pointed at the download,
 * which is the same file and the same result by way of a command line.
 *
 * <h2>The image stays for as long as the page does</h2>
 *
 * <p>A flash can fail halfway — a cable pulled, a port picked wrong — and the
 * reader will want to press the button again; and having flashed one board they
 * may still want the file. So nothing here deletes the image. The view does,
 * when the reader leaves it, and the sweep catches a tab left open.
 */
@NpmPackage(value = "esptool-js", version = "0.6.1")
@JsModule("./esp-flasher.ts")
class SerialFlasher extends Section {

    private static final Logger log = LoggerFactory.getLogger(SerialFlasher.class);

    private final BuildJob job;

    private final Button flash = new Button("Connect and flash");
    private final ProgressBar bar = new ProgressBar(0, 100);
    private final Paragraph status = new Paragraph();
    private final Anchor image = new Anchor();

    SerialFlasher(BuildJob job) {
        this.job = job;

        flash.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        // No click listener here: the browser's own handles the click, see above.
        flash.setVisible(false);  // until the browser has said it can

        bar.setVisible(false);
        status.setVisible(false);

        /*
           Where the image is fetched from: a handler in this session, like the
           download link's. Not shown, because the reader never clicks it — the
           script reads its address. Kept out of sight with display:none rather
           than setVisible(false): Vaadin withholds an invisible element's
           attributes from the browser until it is shown again, href included,
           and the script would have fetched the page itself and written that.
        */
        image.getStyle().setDisplay(Style.Display.NONE);
        image.setHref(event -> {
            event.setFileName(job.fileName());
            event.setContentType("application/octet-stream");
            job.writeTo(event.getOutputStream());
        });

        add(new SectionHeading("Flash it from this browser"),
                new Hint("Plug the board into this computer and press the button. The browser "
                        + "asks which port to use; pick the one that appeared when you plugged "
                        + "it in."),
                flash, bar, status, image);
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        /*
           Asked rather than assumed from the user agent: the API is a property
           the browser either has or has not, and that is the only thing that
           decides whether the button would work.
        */
        getElement().executeJs("return 'serial' in navigator")
                .then(Boolean.class, this::offer);
    }

    private void offer(boolean supported) {
        flash.setVisible(supported);
        log.info("Flasher for {}: the browser {} Web Serial", job.deviceId(),
                supported ? "has" : "has no");
        if (!supported) {
            say("This browser cannot open serial ports, so it cannot flash the board. "
                    + "Use Chrome or Edge on a computer — or download the file below and write "
                    + "it with esptool, as described further down.");
            return;
        }
        /*
           Hands the button to the script, which listens for the click itself.
           If the module is not there — the bundle was built without it, say —
           the promise rejects and the reader is told rather than left with a
           button that does nothing.
        */
        getElement().executeJs("return window.ScrewCloud.armFlasher(this, $0, $1, $2)",
                        flash.getElement(), image.getElement(), job.board().chip())
                .then(armed -> log.info("Flasher armed for {}", job.deviceId()),
                        failure -> {
                            log.warn("The flasher script could not be armed for {}: {}",
                                    job.deviceId(), failure);
                            flash.setVisible(false);
                            say("The flasher could not be started in this browser. "
                                    + "Download the file below and write it with esptool instead.");
                        });
    }

    /** The browser has asked for a port; from here on the button is out of play. */
    @ClientCallable
    void started() {
        log.info("Flashing {} from the browser", job.deviceId());
        flash.setEnabled(false);
        bar.setValue(0);
        bar.setVisible(true);
        say("Choose the board's port in the dialog the browser opens.");
    }

    /** Where the flasher is, in the reader's words. */
    @ClientCallable
    void stage(String text) {
        log.info("Flashing {}: {}", job.deviceId(), text);
        say(text);
    }

    @ClientCallable
    void progress(int percent) {
        bar.setValue(Math.max(0, Math.min(100, percent)));
        say("Writing — %d %%".formatted(percent));
    }

    @ClientCallable
    void flashed() {
        log.info("Flashed {} from the browser", job.deviceId());
        bar.setValue(100);
        say("Done. The board is restarting; within a minute or so its light should "
                + "settle into two green blinks and a pause.");
    }

    /**
     * @param message what the reader is told
     * @param detail what the browser actually said, for the log — the reader's
     *        sentence is chosen for acting on, this one for finding out
     */
    @ClientCallable
    void failed(String message, String detail) {
        log.warn("Flashing {} failed: {} ({})", job.deviceId(), message, detail);
        bar.setVisible(false);
        flash.setEnabled(true);
        say(message);
    }

    private void say(String text) {
        status.setText(text);
        status.setVisible(true);
    }
}
