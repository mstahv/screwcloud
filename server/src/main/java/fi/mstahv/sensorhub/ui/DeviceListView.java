package fi.mstahv.sensorhub.ui;


import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.dependency.StyleSheet;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexLayout;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.theme.aura.Aura;

import jakarta.validation.constraints.NotBlank;

import fi.mstahv.sensorhub.alerts.ConnectionMonitor;
import fi.mstahv.sensorhub.alerts.WebPushService;
import fi.mstahv.sensorhub.store.ClientDeviceStore;
import fi.mstahv.sensorhub.store.MeasurementStore;
import fi.mstahv.sensorhub.updates.DeviceUpdates;
import fi.mstahv.sensorhub.validation.DeviceId;
import org.vaadin.firitin.form.BeanValidationForm;
import org.vaadin.firitin.util.style.VaadinCssProps;

/**
 * Front page: the browser's own devices and adding a new one by identifier.
 *
 * <p>The device list is per browser, identified by a random token in
 * localStorage rather than by logging in. Several people can therefore share one
 * server without user accounts, each seeing their own devices.
 *
 * <p>The token is only read from the browser after attach, so the list is built
 * in a callback rather than in the constructor.
 *
 * <p>Three regions, in that order: the devices, adding one, and the settings that
 * belong to this browser. Content first, occasional action next, configuration last
 * — and the heading sits with the thing it names.
 */
@Route("")
@PageTitle("ScrewCloud")
@StyleSheet(Aura.STYLESHEET)
// After Aura, because it sets the tokens Aura reads. See the file.
@StyleSheet("/styles/sunset-glass.css")
public class DeviceListView extends VerticalLayout {

    private final MeasurementStore measurements;
    private final ClientDeviceStore clientDevices;
    private final ConnectionMonitor connections;
    private final DeviceUpdates updates;

    private final NotificationSwitch notifications;
    private final FlexLayout deviceCards = new FlexLayout();
    private final Span emptyState = new SecondaryText("No devices yet — add one below.");

    private String clientId;

    /**
     * The only thing this page asks for, and the rules about it.
     *
     * <p>{@link DeviceId} is the same constraint the store's {@code add} and every
     * table holding an identifier carry. Written here as well not to repeat the rule
     * but to reach it: this is what lets the field say "1 to 4 letters or digits"
     * while it is being typed, instead of the store saying it afterwards.
     */
    record NewDevice(@NotBlank(message = "Give the device identifier") @DeviceId String deviceId) {
    }

    public DeviceListView(MeasurementStore measurements, ClientDeviceStore clientDevices,
                          WebPushService webPush, ConnectionMonitor connections,
                          DeviceUpdates updates) {
        this.measurements = measurements;
        this.clientDevices = clientDevices;
        this.connections = connections;
        this.updates = updates;
        this.notifications = new NotificationSwitch(webPush, () -> clientId);

        /*
           A floor rather than a fixed height: a view the exact height of the viewport
           ends there, and its bottom padding with it, so anything past one screenful
           finishes flush against the end of the document. See DashboardView.
        */
        setWidthFull();
        setMinHeight("100%");
        /*
           Full width and left aligned, like the measurement view. A centred column
           left a wide left margin with an empty half beside it, and it made the
           whole page jump sideways when navigating to a device. The cards wrap
           across whatever width there is, which is what the wrapping layout is for;
           the blocks of text carry their own limits.
        */

        deviceCards.setFlexWrap(FlexLayout.FlexWrap.WRAP);
        deviceCards.setWidthFull();
        deviceCards.getStyle().setGap(VaadinCssProps.GAP_M.var());

        /*
           Three regions, in the order they are needed: what you have, how to add
           more, and how this browser is configured.

           The previous order had the heading, then the add form, then the
           notification switch, and only then the devices — so "Devices" headed a
           form, and a per-browser setting sat in the middle of the content. The
           switch was moved up there to stop it hiding under a screenful of cards;
           the fix for that is a section of its own, not a place in the queue.
        */
        add(new BrandHeader(),
                new Devices(),
                new AddDevice(),
                new BrowserSettings());
    }

    /** The content: the heading and the devices it heads, with nothing in between. */
    private class Devices extends VerticalLayout {
        Devices() {
            setPadding(false);

            add(new SectionHeading("Devices"), emptyState, deviceCards);
        }
    }

    /**
     * The occasional action, below the list it adds to. It is the only thing to do
     * on a first visit, which is what the empty state points at.
     *
     * <p>A bound form rather than a field and a click listener. The difference the
     * reader sees: an identifier that cannot exist is refused at the field, while
     * they are typing it and with the reason under it, instead of being accepted by
     * a button that then produces a toast.
     */
    private class AddDevice extends BeanValidationForm<NewDevice> {

        /*
           Named after the record's component, which is how FormBinder finds it. It
           lives here rather than on the view for the same reason: the binder
           reflects over the fields of the component it is given.
        */
        private final TextField deviceId = new TextField();

        AddDevice() {
            super(NewDevice.class);
            // One section of the page, not the page.
            asSection();

            deviceId.setPlaceholder("Device ID");
            /*
               By hand, unlike the other forms here: @DeviceId is a constraint of
               this application's own, and the binder only passes on the ones whose
               meaning a field can state exactly. @Size would say it — but that same
               annotation sizes the columns these identifiers are stored in, which
               are wider.
            */
            deviceId.setMaxLength(DeviceId.MAX_LENGTH);
            deviceId.setWidth("10rem");

            setSaveCaption("Add");
            setSavedHandler(this::add);
            setEntity(new NewDevice(""));
        }

        private void add(NewDevice device) {
            if (clientId == null) {
                return;  // the token has not come back from the browser yet
            }
            String added = clientDevices.add(clientId, device.deviceId());
            // Ready for the next one, and the Add button disabled until there is one.
            setEntity(new NewDevice(""));
            Notification.show("Device %s added".formatted(added));
            refresh();
        }

        @Override
        protected Component createContent() {
            HorizontalLayout form = new HorizontalLayout(deviceId, getSaveButton());
            form.setAlignItems(Alignment.BASELINE);

            /*
               The explanation goes under the row rather than into the field's helper
               text, which is as wide as the field and broke the sentence across two
               lines mid-phrase.
            */
            VerticalLayout layout = new VerticalLayout(new SectionHeading("Add a device"), form,
                    new Hint("The same 4 characters as DEVICE_ID in config.h"));
            layout.setPadding(false);
            return layout;
        }
    }

    /**
     * Settings that belong to whoever is looking, last: configuration rather than
     * content, with no reason to compete with the devices.
     *
     * <p>Headed "Your settings" rather than "This browser". The distinction the old
     * wording was reaching for — that these live in this browser rather than in an
     * account — is true but not what a reader is looking for in a heading; the switch
     * itself says where they apply.
     */
    private class BrowserSettings extends VerticalLayout {
        BrowserSettings() {
            setPadding(false);

            add(new SectionHeading("Your settings"), notifications);
        }
    }

    /**
     * One heading style for all three sections, because they are siblings: the
     * devices, adding one, and your settings. An {@code H2} for the first and small
     * bold text for the other two made one section look like a page and the other two
     * like footnotes — and left the document outline saying the same.
     *
     * <p>Smaller than the default H2, which at nearly the size of the brand name
     * left no visible hierarchy between them.
     */
    private static class SectionHeading extends H2 {
        SectionHeading(String text) {
            super(text);
            // The only kept size: the default H2 is nearly the size of the brand
            // name above it, which leaves no hierarchy between them.
            getStyle().setFontSize("1.25rem");
        }
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        /*
           Any device, not one: this page shows several, and a device reporting for
           the first time is exactly the case where there is no identifier here to
           have subscribed to. The subscription ends with the page, so there is
           nothing to undo in onDetach.
        */
        updates.forEveryDevice(this, this::refresh);

        ClientId.resolve(attachEvent.getUI(), resolved -> {
            clientId = resolved;
            refresh();
            // Needs the token, and asks the browser for the real state of its
            // subscription rather than trusting this server's table.
            notifications.refresh(attachEvent.getUI());
        });
    }

    /*
       The list is rebuilt from scratch. Unlike the measurement view this only
       happens on a user action rather than on a timer, so reusing components
       would buy nothing here.
    */
    private void refresh() {
        deviceCards.removeAll();
        var devices = clientDevices.devicesFor(clientId);
        emptyState.setVisible(devices.isEmpty());
        devices.forEach(deviceId -> deviceCards.add(new DeviceLinkCard(
                deviceId,
                measurements.findLatest(deviceId),
                connections.activityOf(deviceId),
                clientDevices.isSilenceAlertEnabled(clientId, deviceId),
                enabled -> {
                    clientDevices.setSilenceAlert(clientId, deviceId, enabled);
                    Notification.show(enabled
                            ? "You will be notified if %s stops reporting".formatted(deviceId)
                            : "Silence alerts for %s are off".formatted(deviceId));
                },
                () -> {
                    clientDevices.remove(clientId, deviceId);
                    refresh();
                })));
    }
}
