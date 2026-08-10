package fi.mstahv.sensorhub.ui;

import java.util.Locale;
import java.util.Optional;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.card.Card;
import com.vaadin.flow.component.card.CardVariant;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.router.RouterLink;

import fi.mstahv.sensorhub.protocol.DeviceMeasurement;
import fi.mstahv.sensorhub.protocol.SensorMeasurement;

/**
 * One device in the front page list: a link to its measurements and a remove
 * button.
 *
 * <p>The subtitle summarises the first sensor's reading and the update time, so
 * a glance at the list shows which device has gone quiet.
 */
class DeviceLinkCard extends Card {

    DeviceLinkCard(String deviceId, Optional<DeviceMeasurement> latest, Runnable onRemove) {
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
