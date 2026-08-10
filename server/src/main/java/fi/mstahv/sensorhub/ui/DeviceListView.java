package fi.mstahv.sensorhub.ui;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
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

import fi.mstahv.sensorhub.alerts.ConnectionMonitor;
import fi.mstahv.sensorhub.alerts.WebPushService;
import fi.mstahv.sensorhub.store.ClientDeviceStore;
import fi.mstahv.sensorhub.store.MeasurementStore;

/**
 * Front page: the browser's own devices and adding a new one by identifier.
 *
 * <p>The device list is per browser, identified by a random token in
 * localStorage rather than by logging in. Several people can therefore share one
 * server without user accounts, each seeing their own devices.
 *
 * <p>The token is only read from the browser after attach, so the list is built
 * in a callback rather than in the constructor.
 */
@Route("")
@PageTitle("ScrewCloud")
@StyleSheet(Aura.STYLESHEET)
public class DeviceListView extends VerticalLayout {

    private final MeasurementStore measurements;
    private final ClientDeviceStore clientDevices;
    private final ConnectionMonitor connections;

    private final NotificationSwitch notifications;
    private final FlexLayout deviceCards = new FlexLayout();
    private final Span emptyState = new Span("No devices yet. Add one by its identifier.");
    private final TextField deviceIdField = new TextField();

    private String clientId;

    public DeviceListView(MeasurementStore measurements, ClientDeviceStore clientDevices,
                          WebPushService webPush, ConnectionMonitor connections) {
        this.measurements = measurements;
        this.clientDevices = clientDevices;
        this.connections = connections;
        this.notifications = new NotificationSwitch(webPush, () -> clientId);

        setSizeFull();
        deviceCards.setFlexWrap(FlexLayout.FlexWrap.WRAP);
        deviceCards.setWidthFull();
        deviceCards.getStyle().setGap("var(--vaadin-gap-m)");

        /*
           The switch goes above the device list rather than after it: the list
           grows, and a setting that ends up below a screenful of cards is a
           setting nobody finds.
        */
        add(new BrandHeader(), new H2("Devices"), createAddForm(), notifications,
                emptyState, deviceCards);
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        ClientId.resolve(attachEvent.getUI(), resolved -> {
            clientId = resolved;
            refresh();
            // Needs the token, and asks the browser for the real state of its
            // subscription rather than trusting this server's table.
            notifications.refresh(attachEvent.getUI());
        });
    }

    private HorizontalLayout createAddForm() {
        deviceIdField.setPlaceholder("Device ID");
        deviceIdField.setMaxLength(ClientDeviceStore.MAX_DEVICE_ID_LENGTH);
        deviceIdField.setHelperText("The same 4 characters as DEVICE_ID in config.h");

        Button add = new Button("Add", event -> addDevice());
        add.addThemeVariants(ButtonVariant.PRIMARY);
        add.addClickShortcut(Key.ENTER);

        HorizontalLayout form = new HorizontalLayout(deviceIdField, add);
        form.setAlignItems(Alignment.BASELINE);
        return form;
    }

    private void addDevice() {
        if (clientId == null) {
            return;  // the token has not come back from the browser yet
        }
        try {
            String added = clientDevices.add(clientId, deviceIdField.getValue());
            deviceIdField.clear();
            Notification.show("Device %s added".formatted(added));
            refresh();
        } catch (IllegalArgumentException e) {
            Notification.show(e.getMessage());
        }
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
