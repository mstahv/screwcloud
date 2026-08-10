package fi.mstahv.sensorhub.ui;

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

        add(new Span(latest
                .map(measurement -> measurement.sensors().size() + " sensors")
                .orElse("—")));

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
            getStyle().setFontSize("0.8125rem");
            addValueChangeListener(event -> {
                if (event.isFromClient()) {
                    onChanged.accept(event.getValue());
                }
            });
        }
    }

    private static String describe(DeviceMeasurement measurement) {
        String age = Ages.format(measurement.receivedAt());
        return measurement.sensors().stream()
                .map(SensorMeasurement::temperature)
                .filter(temperature -> temperature != null)
                .findFirst()
                .map(temperature -> String.format(Locale.ROOT, "%.1f °C · %s", temperature, age))
                .orElse(age);
    }
}
