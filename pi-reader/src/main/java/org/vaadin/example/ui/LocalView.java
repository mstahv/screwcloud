package org.vaadin.example.ui;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
import org.vaadin.example.names.SensorNames;
import org.vaadin.example.ruuvi.BleScanner;
import org.vaadin.example.ruuvi.RuuviReading;
import org.vaadin.example.ruuvi.TagRegistry;
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
public class LocalView extends VerticalLayout {

    private static final int POLL_INTERVAL_MS = 5000;

    private final TagRegistry registry;
    private final ReadingHistory history;
    private final SensorNames names;
    private final BleScanner scanner;
    private final ScrewCloudSender sender;

    private final FlexLayout cards = new FlexLayout();
    private final Span emptyState = new Span();
    private final Span radioStatus = new Span();
    private final Span uploadStatus = new Span();

    /** One card per tag, kept in place and updated. Insertion order is display order. */
    private final Map<String, SensorCard> cardsByAddress = new LinkedHashMap<>();

    private Registration pollRegistration;

    @Inject
    public LocalView(TagRegistry registry, ReadingHistory history, SensorNames names,
                     BleScanner scanner, ScrewCloudSender sender) {
        this.registry = registry;
        this.history = history;
        this.names = names;
        this.scanner = scanner;
        this.sender = sender;

        setSizeFull();
        cards.setFlexWrap(FlexLayout.FlexWrap.WRAP);
        cards.setWidthFull();
        cards.getStyle().setGap(VaadinCssProps.GAP_M.var());

        secondary(emptyState);
        secondary(radioStatus);
        secondary(uploadStatus);

        add(new H2("Temperatures"), emptyState, cards, radioStatus, uploadStatus);
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
        List<RuuviReading> readings = registry.readings();

        for (RuuviReading reading : readings) {
            cardsByAddress
                    .computeIfAbsent(reading.macAddress(), address -> {
                        SensorCard card = new SensorCard(reading.sensorId(), names, this::refresh);
                        cards.add(card);
                        return card;
                    })
                    .update(reading, history.pointsFor(reading.macAddress()), now);
        }

        emptyState.setVisible(readings.isEmpty());
        emptyState.setText("Nothing heard yet. A RuuviTag in range shows up within a few seconds.");

        radioStatus.setText("Bluetooth: " + scanner.status());
        uploadStatus.setText("ScrewCloud: %s · sending as device %s"
                .formatted(sender.status(), sender.deviceId()));
    }

    private static void secondary(Span span) {
        span.getStyle()
                .setColor(VaadinCssProps.TEXT_COLOR_SECONDARY.var())
                .setFontSize("0.875rem");
    }
}
