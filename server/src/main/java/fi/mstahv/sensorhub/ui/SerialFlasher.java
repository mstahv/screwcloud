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

import fi.mstahv.sensorhub.firmware.BuildJob;
import fi.mstahv.sensorhub.firmware.FirmwareBuilds;

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
 * <h2>Chrome and Edge, on a computer</h2>
 *
 * <p>Web Serial is what makes this possible and it is not everywhere: Safari does
 * not have it and neither does any browser on a phone. Where it is missing the
 * button is not offered; the reader is told so and pointed at the download,
 * which is the same file and the same result by way of a command line.
 *
 * <h2>The image outlives a failed attempt, not a successful one</h2>
 *
 * <p>The download link deletes the file the moment it has been read, because it
 * holds a WiFi password. A flash can fail halfway — a cable pulled, a port picked
 * wrong — and the reader will want to press the button again, so this handler
 * does not delete on read. It deletes when the board reports the write complete,
 * and the sweep catches the rest.
 */
@NpmPackage(value = "esptool-js", version = "0.6.1")
@JsModule("./esp-flasher.ts")
class SerialFlasher extends Section {

    private final FirmwareBuilds builds;
    private final BuildJob job;
    private final Runnable onFlashed;

    private final Button flash = new Button("Connect and flash");
    private final ProgressBar bar = new ProgressBar(0, 100);
    private final Paragraph status = new Paragraph();
    private final Anchor image = new Anchor();

    /**
     * @param onFlashed told once the board has been written, so the page can take
     *        away a download link to a file that no longer exists
     */
    SerialFlasher(FirmwareBuilds builds, BuildJob job, Runnable onFlashed) {
        this.builds = builds;
        this.job = job;
        this.onFlashed = onFlashed;

        flash.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        flash.addClickListener(click -> start());
        flash.setVisible(false);  // until the browser has said it can

        bar.setVisible(false);
        status.setVisible(false);

        /*
           Where the image is fetched from: a handler in this session, like the
           download link's, but one that leaves the file in place. Hidden, because
           the reader never clicks it — the script reads its address.
        */
        image.setVisible(false);
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
        if (!supported) {
            say("This browser cannot open serial ports, so it cannot flash the board. "
                    + "Use Chrome or Edge on a computer — or download the file below and write "
                    + "it with esptool, as described further down.");
        }
    }

    private void start() {
        flash.setEnabled(false);
        bar.setValue(0);
        bar.setVisible(true);
        say("Choose the board's port in the dialog the browser opens.");
        getElement().executeJs("return window.ScrewCloud.flashEsp32(this, $0)", image.getElement())
                .then(done -> { }, failure -> failed(
                        "The flasher could not be started in this browser. " + failure));
    }

    /** Where the flasher is, in the reader's words. */
    @ClientCallable
    void stage(String text) {
        say(text);
    }

    @ClientCallable
    void progress(int percent) {
        bar.setValue(Math.max(0, Math.min(100, percent)));
        say("Writing — %d %%".formatted(percent));
    }

    @ClientCallable
    void flashed() {
        bar.setValue(100);
        say("Done. The board is restarting; within a minute or so its light should "
                + "settle into two green blinks and a pause.");
        /*
           The file has done its job and holds a password; the same rule as the
           download link, at the moment the equivalent of a download has happened.
        */
        builds.discard(job);
        onFlashed.run();
    }

    @ClientCallable
    void failed(String message) {
        bar.setVisible(false);
        flash.setEnabled(true);
        say(message);
    }

    private void say(String text) {
        status.setText(text);
        status.setVisible(true);
    }
}
