package fi.mstahv.sensorhub.ui;

import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;

import com.flowingcode.vaadin.addons.relativetime.RelativeTime;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.badge.Badge;
import com.vaadin.flow.component.badge.BadgeVariant;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.card.Card;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.card.CardVariant;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
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

        /*
           The width is in sunset-glass.css rather than here, because it depends on
           how much room there is: one of these fills a phone's screen and four sit
           in a row on a desktop. Java can only set it as an inline style, which no
           media query can then answer.
        */
        addClassName("device-card");

        /*
           No motif here, unlike the sensor cards. These carried one for a while, and
           the band it needed was the whole of the card's media slot — which on a card
           whose content is three short lines is a picture with nothing standing in
           it. A sensor card's motif is a background for the dial; a device card has
           no dial, so the band read as decoration that had wandered in.
        */

        setTitle(new RouterLink(deviceId, DashboardView.class, deviceId));
        latest.ifPresentOrElse(
                measurement -> setSubtitle(summarise(measurement)),
                () -> setSubtitle("No measurements yet"));

        Button remove = new Button(VaadinIcon.TRASH.create(), event -> onRemove.run());
        remove.addThemeVariants(ButtonVariant.TERTIARY, ButtonVariant.SMALL);
        remove.setAriaLabel("Remove device from the list");
        setHeaderSuffix(remove);

        Facts facts = new Facts(new Span(latest.map(DeviceLinkCard::sensorCount).orElse("—")));
        if (activity.silent()) {
            facts.add(new OfflineBadge(activity));
        }
        facts.add(new SilenceAlertToggle(silenceAlertEnabled, onSilenceAlertChanged));
        add(facts);
    }

    /**
     * The card's few facts, each on a line of its own.
     *
     * <p>A column rather than children added to the card directly, because a card's
     * content slot is a plain block and what goes into it flows the way text does:
     * while the card was 15rem wide the sensor count and the checkbox each took a
     * line only because two did not fit, and the first phone-width card put them
     * side by side. Which line something is on should not depend on how much room
     * there happens to be.
     *
     * <p>Nothing said about alignment: a vertical layout's own default is
     * flex-start, so the badge already keeps to the width of its text. An earlier
     * version set that by hand, guarding against a stretch that was never the
     * default.
     */
    private static class Facts extends VerticalLayout {
        Facts(Component... facts) {
            super(facts);
            setPadding(false);
        }
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
        int count = measurement.measuringPoints().size();
        return count == 1 ? "1 sensor" : count + " sensors";
    }

    /**
     * The device in one line: the reading that stands for it, and when that arrived.
     *
     * <p>The time is a component rather than a formatted string. The front page is a
     * page somebody leaves open — it refreshes only when they add or remove a device
     * — so an age written into the DOM once was simply wrong by however long they had
     * been looking at it. This one is rendered by the browser and stays right.
     */
    private static Component summarise(DeviceMeasurement measurement) {
        Span line = new Span();
        describe(measurement).ifPresent(reading -> line.add(new Span(reading + " · ")));
        line.add(new RelativeTime(measurement.receivedAt()));
        return line;
    }

    /**
     * Package private for its test: which reading stands for the device is the part
     * worth pinning down. Empty when the device measured no temperature at all —
     * the arrival time is then the whole of what there is to say, and the caller
     * shows that on its own.
     */
    static Optional<String> describe(DeviceMeasurement measurement) {
        return measurement.sensors().stream()
                .filter(sensor -> sensor.temperature() != null)
                .min(Comparator.comparingInt(DeviceLinkCard::previewRank))
                .map(sensor -> String.format(Locale.ROOT, "%.1f °C", sensor.temperature()));
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
