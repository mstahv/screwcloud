package fi.mstahv.sensorhub.ui;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.vaadin.flow.component.orderedlayout.FlexLayout;

import fi.mstahv.sensorhub.protocol.DeviceMeasurement;
import fi.mstahv.sensorhub.protocol.SensorMeasurement;
import fi.mstahv.sensorhub.alerts.WebPushService;
import fi.mstahv.sensorhub.store.AlertSubscriptionStore;
import fi.mstahv.sensorhub.store.MeasurementStore;
import fi.mstahv.sensorhub.store.SensorSettingsStore;

/**
 * The selected device's sensor cards, wrapping to the available width.
 *
 * <p>Cards are kept in a map keyed by sensor id and updated in place. Components
 * are only added or removed when the set of sensors actually changes — in
 * practice when switching devices or when a new sensor appears, not on every
 * poll.
 */
class SensorCardLayout extends FlexLayout {

    static final Duration HISTORY_WINDOW = Duration.ofHours(24);

    private final MeasurementStore store;
    private final SensorCardContext context;

    /**
     * The browser token, or null until the browser has answered. Only the
     * per-browser alert settings need it, and those are reached by opening a
     * popover — long after attach.
     */
    private String clientId;

    /** Insertion order is kept so cards stay in the same place. */
    private final Map<String, SensorCard> cardsBySensorId = new LinkedHashMap<>();

    private String shownDeviceId;

    SensorCardLayout(MeasurementStore store, SensorSettingsStore settings,
                     AlertSubscriptionStore alerts, WebPushService webPush) {
        this.store = store;
        this.context = new SensorCardContext(settings, store, alerts, webPush, () -> clientId);
        setFlexWrap(FlexWrap.WRAP);
        setWidthFull();
        getStyle().setGap("var(--vaadin-gap-m)");
    }

    void show(DeviceMeasurement device) {
        /*
           A card holds the device id for its settings, and sensor ids are only
           unique within a device. So when the device changes the cards must be
           recreated rather than reused.
        */
        if (!device.deviceId().equals(shownDeviceId)) {
            clear();
            shownDeviceId = device.deviceId();
        }

        Instant from = Instant.now().minus(HISTORY_WINDOW);

        List<SensorMeasurement> sensors = device.sensors().stream()
                .sorted(Comparator.comparing(SensorMeasurement::sensorId))
                .toList();

        removeCardsOtherThan(sensors.stream()
                .map(SensorMeasurement::sensorId)
                .collect(Collectors.toSet()));

        int index = 0;
        for (SensorMeasurement sensor : sensors) {
            SensorCard card = cardsBySensorId.get(sensor.sensorId());
            if (card == null) {
                card = new SensorCard(context, device.deviceId(), sensor.sensorId());
                cardsBySensorId.put(sensor.sensorId(), card);
                /*
                   Inserted at its position in the sorted list. The existing
                   cards are already in order among themselves, so the ordering
                   holds even when a new sensor appears later.
                */
                addComponentAtIndex(index, card);
            }
            card.update(sensor, store.history(device.deviceId(), sensor.sensorId(), from));
            index++;
        }
    }

    /** Told once the browser has reported its token. */
    void setClientId(String clientId) {
        this.clientId = clientId;
    }

    void clear() {
        removeCardsOtherThan(Set.of());
        shownDeviceId = null;
    }

    int cardCount() {
        return cardsBySensorId.size();
    }

    /** Visible for tests, which assert that the instance is the same one. */
    SensorCard cardFor(String sensorId) {
        return cardsBySensorId.get(sensorId);
    }

    private void removeCardsOtherThan(Set<String> sensorIds) {
        Iterator<Map.Entry<String, SensorCard>> cards = cardsBySensorId.entrySet().iterator();
        while (cards.hasNext()) {
            Map.Entry<String, SensorCard> entry = cards.next();
            if (!sensorIds.contains(entry.getKey())) {
                remove(entry.getValue());
                cards.remove();
            }
        }
    }
}
