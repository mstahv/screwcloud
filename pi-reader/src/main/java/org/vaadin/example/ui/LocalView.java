package org.vaadin.example.ui;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.flowingcode.vaadin.addons.relativetime.RelativeTime;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.dependency.StyleSheet;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.shared.Registration;
import com.vaadin.flow.theme.aura.Aura;

import org.vaadin.example.history.ReadingHistory;
import org.vaadin.example.lora.LoraReceiver;
import org.vaadin.example.names.SensorNames;
import org.vaadin.example.ruuvi.BleScanner;
import org.vaadin.example.ruuvi.TagRegistry;
import org.vaadin.example.sensor.Reading;
import org.vaadin.example.thingy.ThingyReader;
import org.vaadin.example.upstream.ScrewCloudSender;
import org.vaadin.firitin.util.style.VaadinCssProps;

import jakarta.inject.Inject;

/**
 * Everything this application shows: what the tags in this house are reading, on
 * the local network, whether or not anything else is reachable.
 *
 * <p>Deliberately one page with no navigation. The server has the history, the
 * thresholds, the counters and the notifications; this is the page you look at
 * from the doorway, and the case it is built for is the one where the server
 * cannot be reached at all.
 *
 * <p>Aura, and the same cards the server draws, because the two are looked at by
 * the same person about the same sensors — a different look would suggest a
 * different system.
 *
 * <p>It polls rather than pushes. A reading changes every few seconds at most, a
 * poll costs one request against a server with a handful of clients on a home
 * network, and it is one moving part fewer than a websocket that has to survive a
 * sleeping phone.
 */
@Route("")
@PageTitle("ScrewCloud local")
@StyleSheet(Aura.STYLESHEET)
// After Aura, because it sets the tokens Aura reads. The same file the
// server uses: the two are looked at by the same person about the same
// sensors, and a different look would suggest a different system.
@StyleSheet("/styles/sunset-glass.css")
public class LocalView extends VerticalLayout {

    private static final int POLL_INTERVAL_MS = 5000;

    private final TagRegistry registry;
    private final ReadingHistory history;
    private final SensorNames names;
    private final BleScanner scanner;
    private final ScrewCloudSender sender;
    private final LoraReceiver lora;
    private final ThingyReader thingy;

    private final FlexLayout cards = new FlexLayout();
    private final Span emptyState = new Span();
    private final Span radioStatus = new Span();
    private final Span uploadStatus = new Span();

    /**
     * The Thingy:52, which reports separately from the Bluetooth line above it.
     *
     * <p>Its own line because its failures are its own. The scanner can be
     * listening perfectly while the Thingy is asleep, out of range, or held by the
     * phone app — and "Bluetooth: listening" would say nothing about any of that.
     */
    private final Span thingyStatus = new Span();

    /**
     * What the LoRa radio has heard, most recent first.
     *
     * <p>Signal strength rather than temperatures, because this machine relays
     * those packets without opening them. What a reader standing in a field with
     * a node in one hand wants from this page is whether it is being heard, and
     * how well — which is a number that changes as they walk.
     */
    private final Span loraStatus = new Span();
    private final VerticalLayout loraArrivals = new VerticalLayout();

    /** One card per tag, kept in place and updated. Insertion order is display order. */
    private final Map<String, SensorCard> cardsByAddress = new LinkedHashMap<>();

    private Registration pollRegistration;

    @Inject
    public LocalView(TagRegistry registry, ReadingHistory history, SensorNames names,
                     BleScanner scanner, ScrewCloudSender sender, LoraReceiver lora,
                     ThingyReader thingy) {
        this.registry = registry;
        this.history = history;
        this.names = names;
        this.scanner = scanner;
        this.sender = sender;
        this.lora = lora;
        this.thingy = thingy;

        /*
           A floor rather than a fixed height: a view the exact height of the viewport
           ends there, and its bottom padding with it, so anything past one screenful
           finishes flush against the end of the document — which on a phone reads as
           a page cut off rather than one that has ended. The server's views are set
           the same way and for the same reason.
        */
        setWidthFull();
        setMinHeight("100%");
        cards.setFlexWrap(FlexLayout.FlexWrap.WRAP);
        cards.setWidthFull();
        cards.getStyle().setGap(VaadinCssProps.GAP_M.var());

        secondary(emptyState);
        secondary(radioStatus);
        secondary(uploadStatus);
        secondary(thingyStatus);
        secondary(loraStatus);

        loraArrivals.setPadding(false);
        loraArrivals.setSpacing(false);

        add(new H2("Temperatures"), emptyState, cards,
                radioStatus, thingyStatus, uploadStatus, loraStatus, loraArrivals);
    }

    @Override
    protected void onAttach(AttachEvent event) {
        super.onAttach(event);
        event.getUI().setPollInterval(POLL_INTERVAL_MS);
        pollRegistration = event.getUI().addPollListener(poll -> refresh());
        refresh();
    }

    @Override
    protected void onDetach(DetachEvent event) {
        if (pollRegistration != null) {
            pollRegistration.remove();
            pollRegistration = null;
        }
        event.getUI().setPollInterval(-1);
        super.onDetach(event);
    }

    private void refresh() {
        Instant now = Instant.now();
        List<Reading> readings = registry.readings();

        int position = 0;
        for (Reading reading : readings) {
            SensorCard card = cardsByAddress
                    .computeIfAbsent(reading.macAddress(), address -> {
                        SensorCard fresh = new SensorCard(reading.sensorId(), names, this::refresh);
                        cards.add(fresh);
                        return fresh;
                    });
            /*
               Set on every refresh rather than at creation: the motif depends on the
               card's position among the sensors, so a sensor appearing has to be
               able to move the others.
            */
            card.setMotif(position++);
            card.update(reading, history.pointsFor(reading.macAddress()), now);
        }

        emptyState.setVisible(readings.isEmpty());
        emptyState.setText("Nothing heard yet. A RuuviTag in range shows up within a few seconds.");

        radioStatus.setText("Bluetooth: " + scanner.status());
        thingyStatus.setText("Thingy: " + thingy.status());
        showLoraArrivals();
        uploadStatus.setText("ScrewCloud: %s · sending as device %s"
                .formatted(sender.status(), sender.deviceId()));
    }

    /**
     * The last few packets heard over the air, with their signal strength.
     *
     * <p>Rebuilt each poll rather than appended to. There are ten of them and
     * their ages change every second, so keeping components to update would buy
     * nothing but somewhere for a stale line to hide.
     */
    private void showLoraArrivals() {
        loraStatus.setText("LoRa: " + lora.status());

        loraArrivals.removeAll();
        List<LoraReceiver.Arrival> recent = lora.recent();

        /*
           Said explicitly rather than left as a blank space. Standing in a field
           with a node in one hand, "listening and has heard nothing" and "not
           listening" are entirely different situations, and an empty area under a
           status line does not distinguish them.
        */
        if (recent.isEmpty()) {
            if (lora.listening()) {
                Span line = new Span("Nothing heard over the air yet.");
                secondary(line);
                loraArrivals.add(line);
            }
            return;
        }

        for (LoraReceiver.Arrival arrival : recent) {
            /*
               The time is a component rather than part of the string. These lines
               are rebuilt on every poll anyway, but a packet that arrived seconds
               ago is exactly the case where a five second old "0 s ago" reads as
               wrong — and it is the browser that knows what second it is.
            */
            Span line = new Span();
            line.add(new RelativeTime(arrival.at()),
                    new Span(" — " + arrival.packet().describe()));
            secondary(line);
            loraArrivals.add(line);
        }
    }

    private static void secondary(Span span) {
        span.getStyle()
                .setColor(VaadinCssProps.TEXT_COLOR_SECONDARY.var())
                .setFontSize("0.875rem");
    }
}
