package fi.mstahv.sensorhub.ui;

import java.util.concurrent.RejectedExecutionException;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.UIDetachedException;
import com.vaadin.flow.component.dependency.StyleSheet;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Section;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.progressbar.ProgressBar;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.theme.aura.Aura;

import org.vaadin.firitin.form.BeanValidationForm;
import org.vaadin.firitin.layouts.NavigationView;

import fi.mstahv.sensorhub.firmware.BuildJob;
import fi.mstahv.sensorhub.firmware.DeviceIdSuggester;
import fi.mstahv.sensorhub.firmware.FirmwareBuilds;
import fi.mstahv.sensorhub.firmware.FirmwareRequest;
import fi.mstahv.sensorhub.firmware.FirmwareTransport;
import fi.mstahv.sensorhub.validation.DeviceId;
import fi.mstahv.sensorhub.validation.Ssid;
import fi.mstahv.sensorhub.validation.WifiPassphrase;

/**
 * Builds a device's firmware here, so nobody has to install a toolchain.
 *
 * <p>The errand is short and the page is shaped like it: a form, a wait, a file,
 * and then the six steps for getting the file onto the board. Nothing else — a
 * reader arriving here has a Pico in one hand.
 *
 * <p>If this server has no toolchain the page says so plainly instead of
 * offering a button that cannot work. That is not an error state; the feature is
 * optional, and a server without it is a server that simply does not do this.
 *
 * <p>A {@link NavigationView}, like the device and settings views: this is one
 * level down from the list of devices and the reader needs the way back. It is
 * reached from "Add a device", and somebody who arrives, reads what board it
 * builds for and decides it is not their errand should not have to find the
 * browser's own back button.
 */
@Route("firmware")
@PageTitle("Build firmware · ScrewCloud")
@StyleSheet(Aura.STYLESHEET)
@StyleSheet("/styles/sunset-glass.css")
public class FirmwareBuildView extends NavigationView {

    private final FirmwareBuilds builds;
    private final DeviceIdSuggester deviceIds;

    private final Section outcome = new Section();
    private BuildJob job;

    public FirmwareBuildView(FirmwareBuilds builds, DeviceIdSuggester deviceIds) {
        /*
           The arrow carries "Devices" as its accessible name rather than as
           text: the destination has to be said, but not shown. The same wording
           as the device view's, because it is the same destination.
        */
        super("Build firmware", DeviceListView.class, "Devices");
        this.builds = builds;
        this.deviceIds = deviceIds;

        if (!builds.isAvailable()) {
            add(new Unavailable());
            return;
        }
        add(new SupportedBoard(), new RequestForm(), outcome);
    }

    /**
     * What a server without a toolchain says. Worded as a fact about this
     * installation rather than as something the reader did wrong, because it is.
     */
    private static class Unavailable extends Section {
        Unavailable() {
            add(new SectionHeading("Not available on this server"),
                    new Paragraph("This server has not been set up to build firmware. "
                            + "Everything else works as usual — see \"Building firmware on the "
                            + "server\" in the README for what it takes."));
        }
    }

    /**
     * Which board this builds for, said before the form rather than after it.
     *
     * <p>The firmware is compiled for one target and there is no choosing it
     * here, so somebody holding a different board should find that out while
     * they still have their hands free — not from a file that copies onto the
     * drive and does nothing, which is what an RP2040 board does with an RP2350
     * image.
     *
     * <p>Named for the board rather than for the limitation. "Only supports X"
     * reads as an apology for a missing feature; this is simply what the thing
     * is for.
     */
    private static class SupportedBoard extends Section {
        SupportedBoard() {
            add(new Hint("Builds for the Raspberry Pi Pico 2 W. Other boards — including "
                    + "the original Pico W — need a different build, and an image for the "
                    + "wrong chip copies across without doing anything."));
        }
    }

    /**
     * The one form.
     *
     * <p>The device identifier comes pre-filled with a free one, so choosing is
     * optional and the common case is filling in a network and pressing the
     * button. It is still a field: a name somebody picked is worth more than a
     * name a machine picked, and this only saves the reader from inventing one
     * and then finding out it was taken.
     */
    private class RequestForm extends BeanValidationForm<FirmwareRequest> {

        /*
           Named after the record's components, which is how FormBinder finds
           them. They live here rather than on the view because the binder
           reflects over the fields of the component it is given.
        */
        private final TextField deviceId = new TextField("Device ID");
        private final TextField ssid = new TextField("WiFi network");
        private final PasswordField password = new PasswordField("WiFi password");
        private final IntegerField sendIntervalMinutes = new IntegerField("Send interval");
        private final Select<FirmwareTransport> transport = new Select<>();

        RequestForm() {
            super(FirmwareRequest.class);
            asSection();

            /*
               By hand, like DeviceListView's: @DeviceId is a constraint of this
               application's own, and the binder only passes on the ones whose
               meaning a field can state exactly.
            */
            deviceId.setMaxLength(DeviceId.MAX_LENGTH);
            deviceId.setWidth("8rem");
            /*
               No helper text: it is as wide as the field, and this field is
               eight characters wide. The sentence goes under the row instead —
               the same lesson DeviceListView's add form records.
            */

            ssid.setMaxLength(Ssid.MAX_BYTES);
            password.setMaxLength(WifiPassphrase.MAX_LENGTH);
            password.setHelperText("Leave empty for a network without a password");

            sendIntervalMinutes.setMin(1);
            sendIntervalMinutes.setMax(60);
            sendIntervalMinutes.setStepButtonsVisible(true);
            sendIntervalMinutes.setSuffixComponent(new SecondaryText("min"));
            sendIntervalMinutes.setWidth("10rem");

            transport.setLabel("Radio");
            transport.setItems(FirmwareTransport.values());
            transport.setItemLabelGenerator(FirmwareTransport::caption);

            setSaveCaption("Build");
            setSavedHandler(this::startBuild);
            setEntity(new FirmwareRequest(deviceIds.suggest().orElse(""), "", "",
                    FirmwareRequest.DEFAULT_SEND_INTERVAL_MINUTES, FirmwareTransport.AUTOMATIC));
        }

        private void startBuild(FirmwareRequest request) {
            /*
               An identifier already in use is worth saying and must not be
               refused. Building again for a device that exists is not a mistake
               to be guarded against — it is how a WiFi password gets changed and
               how firmware gets updated, which are two of the three reasons
               anybody comes to this page.

               So the check decides what to say, not whether to proceed. The one
               case it is really about is somebody typing a neighbour's
               identifier by accident, and a sentence naming what they are about
               to do is the right weight for that.
            */
            boolean existing = deviceIds.isTaken(request.deviceId());
            try {
                show(builds.submit(request), existing);
            } catch (RejectedExecutionException busy) {
                Notification.show("The server is building something else just now. "
                        + "Try again in a minute.");
            }
        }

        @Override
        protected Component createContent() {
            /*
               No heading of its own: the bar above already says what this view
               is, and a section that is the whole view does not need naming
               twice.
            */
            return new Section(new FieldRow(ssid, password),
                    new FieldRow(deviceId, sendIntervalMinutes, transport),
                    /*
                       What the identifier is *for*, which is not what it looks
                       like. A reader meeting this field has no reason to care
                       about it until the device is running and they want to see
                       the readings — and by then it is on a board in a shed.
                    */
                    new Hint("Write the device ID down. It is the only way to find this "
                            + "device here once it starts sending. To change a device's WiFi "
                            + "password or update its firmware, build again with the same ID."),
                    getSaveButton(),
                    new Hint("The file is built for this network and this identifier alone, "
                            + "and it carries the password — so it belongs to one device."));
        }
    }

    /** Replaces whatever the last build left on screen. */
    private void show(BuildJob started, boolean existing) {
        release();
        job = started;
        Progress progress = new Progress();
        outcome.removeAll();
        if (existing) {
            outcome.add(rebuildNotice(started.deviceId()));
        }
        outcome.add(progress);
        job.onChange(progress::update);
    }

    /**
     * Said once the build is under way rather than as a question before it.
     *
     * <p>Nothing is lost by rebuilding — the readings continue under the same
     * identifier and the history stays — so there is nothing here worth stopping
     * somebody to confirm. The sentence exists for the reader who typed the wrong
     * four characters, and for them the useful thing is knowing which device they
     * just built for.
     */
    private static Paragraph rebuildNotice(String deviceId) {
        Paragraph notice = new Paragraph(
                "%s already exists. This build replaces the firmware on that device; "
                        .formatted(deviceId)
                        + "its readings and history continue under the same ID.");
        notice.getStyle().setColor("var(--lumo-primary-text-color)");
        return notice;
    }

    /**
     * The wait, and then the file.
     *
     * <p>Updates arrive on the build's own thread, so every one of them goes
     * through {@link UI#access} — the same arrangement {@code DeviceUpdates} uses
     * for measurements, and for the same reason: touching components from another
     * thread corrupts state slowly and unreproducibly, which is the worst way for
     * it to be wrong.
     */
    private class Progress extends Section {

        private final ProgressBar bar = new ProgressBar();
        private final Paragraph status = new Paragraph();

        Progress() {
            bar.setIndeterminate(true);
            add(new SectionHeading("Building"), bar, status);
        }

        void update(BuildJob updated) {
            UI ui = getUI().orElse(UI.getCurrent());
            if (ui == null) {
                return;
            }
            try {
                ui.access(() -> render(updated));
            } catch (UIDetachedException gone) {
                // The reader closed the tab; the sweep will clear what was built.
                updated.onChange(null);
            }
        }

        private void render(BuildJob updated) {
            if (updated != job) {
                return;  // a later build has already replaced this one
            }
            switch (updated.state()) {
                case QUEUED -> status.setText(queuedText(updated));
                case RUNNING -> status.setText(updated.message());
                case SUCCEEDED -> ready(updated);
                default -> failed(updated);
            }
        }

        private String queuedText(BuildJob updated) {
            long ahead = builds.queuePosition(updated);
            return ahead == 0
                    ? "Waiting for a free moment"
                    : "Waiting — %d build(s) ahead".formatted(ahead);
        }

        private void ready(BuildJob updated) {
            outcome.removeAll();
            outcome.add(new Section(new SectionHeading("Ready"),
                    downloadLink(updated),
                    new Hint("The file is kept for a few minutes and then deleted, "
                            + "because it contains your WiFi password.")),
                    new FlashingInstructions());
        }

        private void failed(BuildJob updated) {
            bar.setIndeterminate(false);
            bar.setValue(0);
            status.setText(updated.message());
        }
    }

    /**
     * The download, as a plain anchor with a handler.
     *
     * <p>Nothing is written to a shared location and no URL is minted that
     * anybody could guess or pass on: the handler runs inside this session and
     * streams the file straight out of the job. Once it has been read the job is
     * discarded, because the file holds a WiFi password and the errand is over.
     */
    private Anchor downloadLink(BuildJob built) {
        Anchor link = new Anchor();
        link.setText("Download %s".formatted(built.fileName()));
        link.setDownload(true);
        link.setHref(event -> {
            event.setFileName(built.fileName());
            event.setContentType("application/octet-stream");
            built.writeTo(event.getOutputStream());
            builds.discard(built);
        });
        return link;
    }

    private void release() {
        if (job != null) {
            job.onChange(null);
            job = null;
        }
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        release();
        super.onDetach(detachEvent);
    }
}
