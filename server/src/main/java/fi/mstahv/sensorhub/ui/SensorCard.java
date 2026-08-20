package fi.mstahv.sensorhub.ui;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.card.Card;
import com.vaadin.flow.component.card.CardVariant;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.dom.Style;
import fi.mstahv.sensorhub.alerts.HeatSum;
import fi.mstahv.sensorhub.protocol.SensorMeasurement;
import fi.mstahv.sensorhub.store.HistoryPoint;
import fi.mstahv.sensorhub.store.SensorSettingsStore;
import fi.mstahv.sensorhub.store.SensorThresholds;
import org.vaadin.firitin.components.details.VDetails;
import org.vaadin.firitin.components.popover.ContentProvider;
import org.vaadin.firitin.components.popover.PopoverButton;

import java.time.Instant;
import java.util.List;

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
    private final Reading humidity = new Reading();
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
        setMaxWidth("35rem");
        setWidthFull();
        applyName();

        settingsButton = new SettingsButton(this::createSettingsForm);
        setHeaderSuffix(settingsButton);

        /*
           The gauge goes in the Card's media slot, which is meant for exactly
           this: a visual that belongs to the card rather than being part of its
           text content.
        */
        gauge.setWidthFull();
        gauge.setThresholds(settings.thresholdsFor(deviceId, sensorId));
        setMedia(gauge);
        addThemeVariants(CardVariant.COVER_MEDIA);

        // Closing discards the grid, so the reference must go with it.
        measurements.addOpenedChangeListener(event -> {
            if (!event.isOpened()) {
                openMeasurements = null;
            }
        });

        add(humidity, sparkLine, heatSums, measurements);
    }

    /*
       The content is built only when opened, so the field always shows the
       stored name without any separate synchronisation.
    */
    /** How many motifs the stylesheet has. */
    private static final int MOTIFS = 4;

    /**
     * Gives this card its background motif, from its position among the device's
     * sensors.
     *
     * <p>The point is recognition without reading: somebody who checks the same
     * sensors every morning learns where each one is by its shape long before they
     * would have read "R0BF". Two things have to hold for that to work, and they
     * pull in opposite directions.
     *
     * <p><b>No two sensors on a device may share a motif</b>, or the shape stops
     * identifying anything. Hashing the sensor's own name was the first attempt and
     * it fails badly at this scale: four sensors drawing from four motifs collide
     * about ninety per cent of the time. Position in the sorted list cannot collide
     * at all, as long as there are no more sensors than motifs.
     *
     * <p><b>And it has to be the same motif tomorrow.</b> The list is sorted by
     * sensor id, so the position is stable across restarts and identical on every
     * machine. The device's own name shifts where the sequence starts, so two
     * devices with the same number of sensors do not look like each other.
     *
     * <p>What this gives up is that adding a sensor can renumber the ones sorting
     * after it. That is the honest cost of the guarantee, and it is the cheaper of
     * the two: a motif that moves on the rare day a sensor is added is better than
     * two sensors that are indistinguishable every day.
     *
     * @param position this card's index among the device's sensors, sorted by id
     */
    void setMotif(int position) {
        int offset = Math.floorMod(deviceId.hashCode(), MOTIFS);
        getElement().setAttribute("data-motif",
                String.valueOf(Math.floorMod(offset + position, MOTIFS)));
    }

    private Component createSettingsForm() {
        String clientId = context.clientId().get();
        SensorSettingsForm.AlertOptions alerts = alertOptions(clientId);
        return new SensorSettingsForm(
                sensorId,
                settings.nameFor(deviceId, sensorId),
                settings.thresholdsFor(deviceId, sensorId),
                alerts,
                createCounterForm(),
                values -> save(clientId, alerts, values));
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
                /*
                   The store's constraints have already been checked in the form, so
                   a violation here would be a programming error rather than
                   something to explain to the reader — it is left to surface.
                   Counting starts now: the counter begins when the meat goes up.
                */
                started -> {
                    context.heatSums().start(deviceId, sensorId, started.comment(),
                            started.target(), Instant.now());
                    afterCounterChange();
                },
                changed -> {
                    context.heatSums().update(changed.id(), changed.comment(), changed.target(),
                            changed.alertBeforeTarget(), changed.alertAtTarget());
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

    /*
       Nothing is caught here any more. The form will not offer to save a set of
       limits that does not hold together — the button is disabled and the reason is
       on screen — so reaching the store with one is not something a reader can do.
       The store still refuses it, which is what makes that true for every other
       caller as well.
    */
    private void save(String clientId, SensorSettingsForm.AlertOptions alerts,
                      SensorSettingsForm.Values values) {
        SensorThresholds thresholds = values.thresholds();
        settings.setThresholds(deviceId, sensorId, thresholds);
        settings.rename(deviceId, sensorId, values.name());
        /*
           Only when the section was shown: it being absent is not the same as
           "subscribed to nothing", and must not overwrite stored choices.
        */
        if (alerts.available() && clientId != null) {
            context.alerts().setPreferences(clientId, deviceId, sensorId, values.alerts());
        }

        applyName();
        gauge.setThresholds(thresholds);
        settingsButton.close();
    }

    /** The title is the name if one was given, otherwise the sensor id. */
    private void applyName() {
        String name = settings.nameFor(deviceId, sensorId);
        setTitle(name != null ? name : sensorId);
    }

    void update(SensorMeasurement sensor, List<HistoryPoint> history) {
        /*
           Null included: the gauge draws "no reading" itself — an empty dial with
           a dash — so the card no longer hides the dial and swaps a text dash in,
           and nothing jumps when the first reading arrives.
        */
        gauge.setTemperature(sensor.temperature());

        humidity.setText(Readings.format(sensor.humidity(), "%.1f %% RH"));
        sparkLine.setHistory(history);
        lastTemperature = sensor.temperature();
        showHeatSums(lastTemperature);

        /*
           A section left open has to keep up, otherwise it silently stays at the
           rows it had when it was opened. This runs only when a new packet has
           arrived — the view skips the update entirely when nothing changed — so
           it is one refresh per measurement, and the minute sweep costs nothing.
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

    /** The cog that opens the settings; the popover builds its content on open. */
    private static class SettingsButton extends PopoverButton {
        SettingsButton(ContentProvider content) {
            super(content);
            withIcon(VaadinIcon.COG.create());
            addThemeVariants(ButtonVariant.TERTIARY, ButtonVariant.SMALL);
            setAriaLabel("Sensor settings");
        }
    }

    /**
     * One line of a sensor's readings. Block display because these are spans in the
     * card's content slot, and each belongs on a line of its own.
     */
    private static class Reading extends SecondaryText {
        Reading() {
            getStyle().setDisplay(Style.Display.BLOCK);
        }
    }
}
