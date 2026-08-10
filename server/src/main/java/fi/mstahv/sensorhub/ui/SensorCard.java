package fi.mstahv.sensorhub.ui;

import java.util.List;

import org.vaadin.firitin.components.details.VDetails;
import org.vaadin.firitin.components.popover.PopoverButton;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.card.Card;
import com.vaadin.flow.component.card.CardVariant;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.dom.Style;

import fi.mstahv.sensorhub.protocol.SensorMeasurement;
import fi.mstahv.sensorhub.alerts.HeatSum;
import fi.mstahv.sensorhub.store.HistoryPoint;
import fi.mstahv.sensorhub.store.SensorSettingsStore;

/**
 * One sensor's readings and its temperature curve. The temperature is the gauge
 * in the media slot, the humidity a line of text under it.
 *
 * <p>A card is created once per sensor and updated via {@link #update}.
 * Recreating the components on every refresh would force Vaadin to resend the
 * whole DOM structure; swapping text is a fraction of that.
 *
 * <p>Colours use the {@code --vaadin-*} tokens, which are theme agnostic. The
 * {@code --lumo-*} tokens do not exist in the Aura theme, so styles referring to
 * them silently have no effect. Font sizes are given directly in rem, because
 * there is no theme agnostic font size token.
 */
class SensorCard extends Card {

    private final TemperatureBandGauge gauge = new TemperatureBandGauge();
    /*
       Only shown when there is no temperature to gauge. The gauge itself renders
       the value with its unit ("20.53°C"), so a text copy of a reading that
       exists would be the same number twice — and at 2rem it competed with the
       gauge for the same job.
    */
    private final Reading noTemperature = new Reading("0.875rem", "var(--vaadin-text-color-secondary)");
    private final Reading humidity = new Reading("0.875rem", "var(--vaadin-text-color-secondary)");
    private final TemperatureSparkLine sparkLine = new TemperatureSparkLine();

    /*
       VDetails takes a supplier rather than a component: the grid is built when
       the section is opened and thrown away when it is closed. With one card per
       sensor, building every grid up front would mean rows nobody asked to see —
       and rows are the expensive part of a Grid.
    */
    private final VDetails measurements =
            new VDetails("All measurements", this::createMeasurementGrid);

    /** The open grid, or null when the section is closed. */
    private MeasurementGrid openMeasurements;

    /**
     * The degree-day counters. Replaced rather than updated in place: the number of
     * counters changes, and a handful of spans is nothing next to the grid this card
     * already avoids rebuilding.
     */
    private HeatSumPanel heatSums = new HeatSumPanel(List.of());

    private final String deviceId;
    private final String sensorId;
    private final SensorCardContext context;
    private final SensorSettingsStore settings;
    private final PopoverButton settingsButton;

    SensorCard(SensorCardContext context, String deviceId, String sensorId) {
        this.context = context;
        this.deviceId = deviceId;
        this.sensorId = sensorId;
        this.settings = context.settings();

        addThemeVariants(CardVariant.OUTLINED);
        setWidth("15rem");
        applyName();

        settingsButton = new PopoverButton(this::createSettingsForm);
        settingsButton.withIcon(VaadinIcon.COG.create());
        settingsButton.addThemeVariants(ButtonVariant.TERTIARY, ButtonVariant.SMALL);
        settingsButton.setAriaLabel("Sensor settings");
        setHeaderSuffix(settingsButton);

        /*
           The gauge goes in the Card's media slot, which is meant for exactly
           this: a visual that belongs to the card rather than being part of its
           text content. It has no HasSize — ReactAdapterComponent extends plain
           Component — so the width is set through the style.
        */
        gauge.getStyle().setWidth("100%");
        gauge.setThresholds(settings.thresholdsFor(deviceId, sensorId));
        setMedia(gauge);
        addThemeVariants(CardVariant.COVER_MEDIA);

        noTemperature.setText(Readings.MISSING + " °C");

        // Closing discards the grid, so the reference must go with it.
        measurements.addOpenedChangeListener(event -> {
            if (!event.isOpened()) {
                openMeasurements = null;
            }
        });

        add(noTemperature, humidity, sparkLine, heatSums, measurements);
    }

    /*
       The content is built only when opened, so the field always shows the
       stored name without any separate synchronisation.
    */
    private Component createSettingsForm() {
        String clientId = context.clientId().get();
        return new SensorSettingsForm(
                sensorId,
                settings.nameFor(deviceId, sensorId),
                settings.thresholdsFor(deviceId, sensorId),
                alertOptions(clientId),
                createCounterForm(),
                values -> save(clientId, values));
    }

    /*
       Rebuilt on every change so it always shows what was stored, in the same
       spirit as the settings form itself. Cheap: the popover is open, the reader is
       looking at a handful of rows.
    */
    /**
     * The last temperature shown, so the counter panel can be rebuilt after a change
     * without waiting for the next packet. Null when the sensor has no reading.
     */
    private Double lastTemperature;

    private Component createCounterForm() {
        return new HeatSumCounterForm(
                context.heatSums().countersFor(deviceId, sensorId),
                started -> {
                    try {
                        context.heatSums().start(deviceId, sensorId, started.comment(),
                                started.target(), started.startedAt());
                    } catch (IllegalArgumentException e) {
                        Notification.show(e.getMessage());
                        return;
                    }
                    afterCounterChange();
                },
                changed -> {
                    try {
                        context.heatSums().update(changed.id(), changed.comment(), changed.target(),
                                changed.alertBeforeTarget(), changed.alertAtTarget());
                    } catch (IllegalArgumentException e) {
                        Notification.show(e.getMessage());
                        return;
                    }
                    showHeatSums(lastTemperature);
                },
                id -> {
                    context.heatSums().stop(id);
                    afterCounterChange();
                });
    }

    /*
       Closing the popover is what refreshes it: its content is built on open, so
       reopening shows the new list. Reaching into the open popover to patch it would
       be more code for the same result.
    */
    private void afterCounterChange() {
        showHeatSums(lastTemperature);
        settingsButton.close();
    }

    /*
       Alerts are per browser, so they need the browser token. Without it — the
       token has not come back yet, or notifications are not configured on this
       server — the section is left out rather than shown as something that would
       not work.
    */
    private SensorSettingsForm.AlertOptions alertOptions(String clientId) {
        if (!context.webPush().isEnabled() || clientId == null) {
            return SensorSettingsForm.AlertOptions.unavailable();
        }
        return new SensorSettingsForm.AlertOptions(
                context.alerts().preferencesFor(clientId, deviceId, sensorId),
                context.alerts().hasPushSubscription(clientId));
    }

    private void save(String clientId, SensorSettingsForm.Values values) {
        /*
           The bands are saved first: they are the ones that can be rejected, and
           a rejected save should not have renamed the sensor as a side effect.
        */
        try {
            settings.setThresholds(deviceId, sensorId, values.thresholds());
        } catch (IllegalArgumentException e) {
            Notification.show(e.getMessage());
            return;
        }
        settings.rename(deviceId, sensorId, values.name());
        if (values.alerts() != null && clientId != null) {
            context.alerts().setPreferences(clientId, deviceId, sensorId, values.alerts());
        }

        applyName();
        gauge.setThresholds(values.thresholds());
        settingsButton.close();
    }

    /** The title is the name if one was given, otherwise the sensor id. */
    private void applyName() {
        String name = settings.nameFor(deviceId, sensorId);
        setTitle(name != null ? name : sensorId);
    }

    void update(SensorMeasurement sensor, List<HistoryPoint> history) {
        /*
           A gauge showing 0 would be indistinguishable from a real zero reading,
           so it is hidden rather than zeroed when the sensor has no temperature.
           The dash then takes its place: a card with neither would leave the
           absence looking like a rendering fault.
        */
        boolean hasTemperature = sensor.temperature() != null;
        gauge.setVisible(hasTemperature);
        noTemperature.setVisible(!hasTemperature);
        if (hasTemperature) {
            gauge.setTemperature(sensor.temperature());
        }

        humidity.setText(Readings.format(sensor.humidity(), "%.1f %% RH"));
        sparkLine.setHistory(history);
        lastTemperature = sensor.temperature();
        showHeatSums(lastTemperature);

        /*
           A section left open has to keep up, otherwise it silently stays at the
           rows it had when it was opened. This runs only when a new packet has
           arrived — the view skips the update entirely when nothing changed — so
           it is one refresh per measurement, not one per poll.
        */
        if (openMeasurements != null) {
            openMeasurements.refresh();
        }
    }

    /*
       Each counter's sum is integrated from its own start, which may be weeks back,
       so this runs only when a new packet has arrived — the view skips the update
       entirely when nothing has changed.
    */
    private void showHeatSums(Double currentTemperature) {
        List<HeatSumPanel.CounterProgress> progress =
                context.heatSums().countersFor(deviceId, sensorId).stream()
                        .map(counter -> new HeatSumPanel.CounterProgress(counter,
                                HeatSum.of(context.measurements().history(
                                                deviceId, sensorId, counter.getStartedAt()),
                                        currentTemperature)))
                        .toList();

        HeatSumPanel replacement = new HeatSumPanel(progress);
        replace(heatSums, replacement);
        heatSums = replacement;
    }

    private Component createMeasurementGrid() {
        openMeasurements = new MeasurementGrid(context.measurements(), deviceId, sensorId);
        return openMeasurements;
    }

    private static class Reading extends Span {
        Reading(String fontSize, String color) {
            Style style = getStyle();
            style.setFontSize(fontSize);
            style.setColor(color);
            style.setDisplay(Style.Display.BLOCK);
        }
    }
}
