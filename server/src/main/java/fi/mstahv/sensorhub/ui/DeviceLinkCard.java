package fi.mstahv.sensorhub.ui;

import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;

import com.vaadin.flow.component.badge.Badge;
import com.vaadin.flow.component.badge.BadgeVariant;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.card.Card;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.card.CardVariant;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.router.RouterLink;

import fi.mstahv.sensorhub.alerts.DeviceActivity;
import fi.mstahv.sensorhub.alerts.Elapsed;
import fi.mstahv.sensorhub.protocol.DeviceMeasurement;
import fi.mstahv.sensorhub.protocol.SensorMeasurement;

/**
 * One device in the front page list: a link to its measurements, whether it is
 * still reporting, and a remove button.
 *
 * <p>The subtitle summarises the first sensor's reading and the update time. An
 * age alone is ambiguous though — "3 h ago" is normal for an hourly device and
 * alarming for one that reports every five minutes — so a device judged silent
 * says so outright.
 */
class DeviceLinkCard extends Card {

    DeviceLinkCard(String deviceId, Optional<DeviceMeasurement> latest, DeviceActivity activity,
                   boolean silenceAlertEnabled, Consumer<Boolean> onSilenceAlertChanged,
                   Runnable onRemove) {
        addThemeVariants(CardVariant.OUTLINED);
        setWidth("15rem");

        setTitle(new RouterLink(deviceId, DashboardView.class, deviceId));
        setSubtitle(latest.map(DeviceLinkCard::describe).orElse("No measurements yet"));

        Button remove = new Button(VaadinIcon.TRASH.create(), event -> onRemove.run());
        remove.addThemeVariants(ButtonVariant.TERTIARY, ButtonVariant.SMALL);
        remove.setAriaLabel("Remove device from the list");
        setHeaderSuffix(remove);

        add(new Span(latest.map(DeviceLinkCard::sensorCount).orElse("—")));

        if (activity.silent()) {
            add(new OfflineBadge(activity));
        }
        add(new SilenceAlertToggle(silenceAlertEnabled, onSilenceAlertChanged));
    }

    /**
     * Shown only when the device is actually late. A green "online" badge on every
     * card would be noise: the interesting state is the exceptional one.
     */
    private static class OfflineBadge extends Badge {
        OfflineBadge(DeviceActivity activity) {
            super("Offline · nothing for " + Elapsed.approximate(activity.sinceLast()));
            addThemeVariants(BadgeVariant.ERROR, BadgeVariant.SMALL);
        }
    }

    private static class SilenceAlertToggle extends Checkbox {
        SilenceAlertToggle(boolean enabled, Consumer<Boolean> onChanged) {
            super("Notify if it stops reporting");
            setValue(enabled);
            addValueChangeListener(event -> {
                if (event.isFromClient()) {
                    onChanged.accept(event.getValue());
                }
            });
        }
    }

    /**
     * How many measuring points this device has, which is not the same as how many
     * values it sends: the chip's own temperature is the box reporting on itself,
     * and counting it made a device with one thermometer say "2 sensors" while its
     * dashboard showed one card.
     *
     * <p>Unless it is the only thing there is. A board measuring nothing but its own
     * die is still a device worth listing, and "0 sensors" would read as broken.
     */
    static String sensorCount(DeviceMeasurement measurement) {
        long count = measurement.sensors().stream()
                .filter(sensor -> !sensor.isDeviceInternal())
                .count();
        if (count == 0) {
            count = measurement.sensors().size();
        }
        return count == 1 ? "1 sensor" : count + " sensors";
    }

    /** Package private for its test: the ordering is the part worth pinning down. */
    static String describe(DeviceMeasurement measurement) {
        String age = Ages.format(measurement.receivedAt());
        return measurement.sensors().stream()
                .filter(sensor -> sensor.temperature() != null)
                .min(Comparator.comparingInt(DeviceLinkCard::previewRank))
                .map(sensor -> String.format(Locale.ROOT, "%.1f °C · %s",
                        sensor.temperature(), age))
                .orElse(age);
    }

    /**
     * Which reading stands for the whole device in one line.
     *
     * <p>A RuuviTag first: it is a place somebody chose to measure, and on a device
     * with several sensors it is the one the list is being scanned for. The box's
     * own chip temperature last, because it is a diagnostic — it reads well above
     * the air around it and moves with the load rather than the weather, so a
     * device summarised by it looks like it is in a room nobody would sit in.
     *
     * <p>Anything else in between: a wired DHT22 measures air, just wherever the
     * box happens to be.
     *
     * <p>Ties are broken by packet order, which {@code min} keeps — so with two
     * tags this is the first one the device reported, as before.
     */
    private static int previewRank(SensorMeasurement sensor) {
        if (sensor.isRuuviTag()) {
            return 0;
        }
        return sensor.isDeviceInternal() ? 2 : 1;
    }
}
