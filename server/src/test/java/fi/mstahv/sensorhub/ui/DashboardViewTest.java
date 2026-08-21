package fi.mstahv.sensorhub.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.vaadin.browserless.BrowserlessUIContext;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.component.badge.Badge;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.vaadin.firitin.components.popover.PopoverButton;
import org.vaadin.firitin.layouts.SubViewHeader;

import fi.mstahv.sensorhub.protocol.DeviceMeasurement;
import fi.mstahv.sensorhub.protocol.SensorMeasurement;
import fi.mstahv.sensorhub.store.MeasurementStore;
import fi.mstahv.sensorhub.store.SensorSettingsStore;
import fi.mstahv.sensorhub.updates.DeviceUpdates;

/**
 * One device's measurements, driven through the view.
 *
 * <p>Each test uses a device identifier of its own. The window is fresh per test but
 * the database is not, and measurements are not per browser — a shared identifier
 * would mean tests reading each other's readings.
 */
@UiTest
class DashboardViewTest {

    @Autowired
    private MeasurementStore measurements;

    @Autowired
    private SensorSettingsStore settings;

    @Autowired
    private DeviceUpdates updates;

    @Test
    void everySensorInThePacketGetsACard(@Autowired BrowserlessUIContext ui) {
        store("AAAA", Instant.now(), 6.5, 21.0);

        ui.navigate(DashboardView.class, "AAAA");

        assertEquals(List.of("DHT", "RBF"), sensorCardTitles(ui));
    }

    /*
       Except the chip's own temperature, which is a diagnostic about the box
       rather than a measuring point. It used to get the same card as a sensor
       somebody placed on purpose — heading, sparkline, alert settings and all.
    */
    @Test
    void theChipTemperatureIsNotOneOfTheMeasuringPoints(@Autowired BrowserlessUIContext ui) {
        storeWithChip("EEEE", 6.5, 42.7);

        ui.navigate(DashboardView.class, "EEEE");

        assertEquals(List.of("DHT", "RBF"), sensorCardTitles(ui));
    }

    /* It is on the line that carries the rest of the device's own state. */
    @Test
    void theChipTemperatureIsInTheDeviceStatusLine(@Autowired BrowserlessUIContext ui) {
        storeWithChip("FFFF", 6.5, 42.7);

        ui.navigate(DashboardView.class, "FFFF");

        assertTrue(ui.findSpan().withTextContaining("· CPU 42.7 °C").exists(),
                "the chip temperature belongs with the sequence number, not on a card");
    }

    /**
     * The exception to all of the above: a board with nothing but its own chip is
     * a device whose one reading is what it is for. It gets an ordinary card, and
     * the status line does not repeat the number underneath it.
     */
    @Test
    void aDeviceThatOnlyMeasuresItselfGetsACardForIt(@Autowired BrowserlessUIContext ui) {
        measurements.store(new DeviceMeasurement("SLP1", 1, Instant.now(), List.of(
                new SensorMeasurement(SensorMeasurement.INTERNAL_SENSOR_ID, 18.6, null))));

        ui.navigate(DashboardView.class, "SLP1");

        assertEquals(List.of("CPU"), sensorCardTitles(ui),
                "hiding it would leave a status line and an empty page");
        assertFalse(ui.findSpan().withTextContaining("· CPU").exists(),
                "the same number twice on one screen reads as two readings that agree");
    }

    /* And a device that does not report one leaves the line as it was. */
    @Test
    void aDeviceWithoutAChipReadingSaysNothingAboutIt(@Autowired BrowserlessUIContext ui) {
        store("GGGG", Instant.now(), 6.5, 21.0);

        ui.navigate(DashboardView.class, "GGGG");

        assertTrue(ui.findSpan().withTextContaining("sequence 1").exists());
        assertFalse(ui.findSpan().withTextContaining("CPU").exists());
    }

    /*
       The top of the view is one floating row: the way back and the device's name.
       The back control is an arrow with no text, so the contract worth pinning is
       the invisible half — a link that shows nothing must still say where it goes.
    */
    @Test
    void theWayBackAndTheDeviceNameShareOneHeader(@Autowired BrowserlessUIContext ui) {
        store("NAVI", Instant.now(), 6.5, 21.0);

        ui.navigate(DashboardView.class, "NAVI");

        SubViewHeader header = ui.find(SubViewHeader.class).first();
        assertTrue(header.getChildren().anyMatch(child ->
                        child instanceof com.vaadin.flow.component.html.H1 h1
                                && h1.getText().equals("NAVI")),
                "The device's name is the view's main heading, inside the header");

        var back = header.getChildren()
                .filter(com.vaadin.flow.router.RouterLink.class::isInstance)
                .map(com.vaadin.flow.router.RouterLink.class::cast)
                .findFirst().orElseThrow(() -> new AssertionError("No way back"));
        assertEquals("", back.getHref(), "The arrow leads to the device list");
        assertEquals("Devices", back.getElement().getAttribute("aria-label"),
                "An arrow with no text still has to say where it goes");
    }

    @Test
    void aDeviceWithNoMeasurementsSaysSo(@Autowired BrowserlessUIContext ui) {
        ui.navigate(DashboardView.class, "BBBB");

        assertTrue(ui.findSpan().withTextContaining("No measurements from BBBB yet").exists());
        assertTrue(sensorCardTitles(ui).isEmpty());
    }

    /*
       Naming a sensor is the first thing anyone does with this screen, and the name
       has to reach the card title.
    */
    @Test
    void aSensorCanBeRenamedFromItsSettings(@Autowired BrowserlessUIContext ui) {
        store("CCCC", Instant.now(), 6.5, 21.0);
        ui.navigate(DashboardView.class, "CCCC");

        openSettings(ui, "DHT");
        ui.findTextField().withLabel("Name").setValue("Cold room");
        ui.findButton().withText("Save").click();

        assertTrue(sensorCardTitles(ui).contains("Cold room"));
        assertEquals("Cold room", settings.nameFor("CCCC", "DHT"));
    }

    /*
       A set of limits that does not hold together is refused in the form itself:
       the four values are one rule, and the reader is told which way it is broken
       while they are still looking at the fields. Nothing is saved, so the name
       they typed in the same visit is not saved either.
    */
    @Test
    void refusedLimitsAreExplainedAndNothingIsSaved(@Autowired BrowserlessUIContext ui) {
        store("DDDD", Instant.now(), 6.5, 21.0);
        ui.navigate(DashboardView.class, "DDDD");

        openSettings(ui, "DHT");
        ui.findTextField().withLabel("Name").setValue("Should not stick");
        // OK high below OK low: out of order, and no single field is wrong on its own.
        ui.findNumberField().withAriaLabel("OK low").setValue(8.0);
        ui.findNumberField().withAriaLabel("OK high").setValue(2.0);
        ui.findNumberField().withAriaLabel("Alert low").setValue(-5.0);
        ui.findNumberField().withAriaLabel("Alert high").setValue(15.0);

        assertTrue(bands(ui).isInvalid(), "The set of limits should be marked wrong");
        assertTrue(bands(ui).getErrorMessage().contains("The limits must increase"),
                "The reader should be told which way: " + bands(ui).getErrorMessage());
        assertFalse(ui.findButton().withText("Save").component().isEnabled(),
                "and Save should not offer to store it");
        assertEquals(null, settings.nameFor("DDDD", "DHT"),
                "A form that cannot be saved must not have renamed the sensor");
    }

    /*
       The constraint reaches the widget: nothing in this form sets a length limit,
       the @Size on the bound record does. Worth pinning down, because the field
       stopping the reader at 64 characters is the difference between a rule that is
       enforced and one that is merely checked afterwards.
    */
    @Test
    void theNameFieldCarriesTheLengthLimitFromItsConstraint(@Autowired BrowserlessUIContext ui) {
        store("MMMM", Instant.now(), 6.5, 21.0);
        ui.navigate(DashboardView.class, "MMMM");

        openSettings(ui, "DHT");

        assertEquals(64, ui.findTextField().withLabel("Name").component().getMaxLength());
    }

    /*
       Half a set is the other way to get it wrong, and it needs its own sentence:
       "not in order" is no help to someone who has filled in three fields of four.
    */
    @Test
    void halfFilledLimitsSayWhatIsMissing(@Autowired BrowserlessUIContext ui) {
        store("JJJJ", Instant.now(), 6.5, 21.0);
        ui.navigate(DashboardView.class, "JJJJ");

        openSettings(ui, "DHT");
        ui.findNumberField().withAriaLabel("OK low").setValue(2.0);

        assertTrue(bands(ui).getErrorMessage().contains("Give all four temperature limits"),
                "Half a set needs its own sentence: " + bands(ui).getErrorMessage());
        assertFalse(ui.findButton().withText("Save").component().isEnabled());
    }

    @Test
    void temperatureBandsAreStoredWhenTheyAreInOrder(@Autowired BrowserlessUIContext ui) {
        store("EEEE", Instant.now(), 6.5, 21.0);
        ui.navigate(DashboardView.class, "EEEE");

        openSettings(ui, "DHT");
        ui.findNumberField().withAriaLabel("Alert low").setValue(-5.0);
        ui.findNumberField().withAriaLabel("OK low").setValue(2.0);
        ui.findNumberField().withAriaLabel("OK high").setValue(8.0);
        ui.findNumberField().withAriaLabel("Alert high").setValue(15.0);
        ui.findButton().withText("Save").click();

        var stored = settings.thresholdsFor("EEEE", "DHT");
        assertTrue(stored.isConfigured());
        assertEquals(2.0, stored.okLow());
    }

    /*
       A counter started from the settings has to show up on the card, with a
       forecast — that is the whole point of the feature.
    */
    @Test
    void aDegreeDayCounterAppearsOnTheCard(@Autowired BrowserlessUIContext ui) {
        store("FFFF", Instant.now(), 6.5, 21.0);
        ui.navigate(DashboardView.class, "FFFF");

        openSettings(ui, "DHT");
        ui.findTextField().withPlaceholder("What is hanging").setValue("hirvi");
        ui.findButton().withText("Start").click();

        assertTrue(ui.findSpan().withTextContaining("hirvi").exists(),
                "The counter should be named on the card");
        assertTrue(ui.findSpan().withTextContaining("/ 40.0 °Cd").exists(),
                "with its progress towards the target");
    }

    /*
       A brand-new counter has no readings of its own yet, so its forecast comes from
       the current temperature and says so. This is the bug that shipped once: it read
       "below freezing" in a warm room.
    */
    @Test
    void aFreshCounterForecastsFromTheCurrentTemperature(@Autowired BrowserlessUIContext ui) {
        store("GGGG", Instant.now(), 23.0, 23.0);
        ui.navigate(DashboardView.class, "GGGG");

        openSettings(ui, "DHT");
        ui.findTextField().withPlaceholder("What is hanging").setValue("huoneenlampo");
        ui.findButton().withText("Start").click();

        assertTrue(ui.findSpan().withTextContaining("from the current temperature").exists(),
                "A provisional forecast should say where it came from");
        assertFalse(ui.findSpan().withTextContaining("below freezing").exists(),
                "23 degrees is not below freezing");
    }

    /*
       A running counter's row has no save button of its own — the popover already
       has one — so it saves as the values change. That it does, and that the card
       keeps up, is the whole contract of that row.
    */
    @Test
    void aRunningCounterIsRetargetedFromItsRow(@Autowired BrowserlessUIContext ui) {
        store("KKKK", Instant.now(), 6.5, 21.0);
        ui.navigate(DashboardView.class, "KKKK");

        openSettings(ui, "DHT");
        ui.findTextField().withPlaceholder("What is hanging").setValue("hirvi");
        ui.findButton().withText("Start").click();

        // The popover rebuilds its content on open, so the counter is now a row in it.
        openSettings(ui, "DHT");
        targetOfTheRunningCounter(ui).setValue(60.0);

        assertTrue(ui.findSpan().withTextContaining("/ 60.0 °Cd").exists(),
                "The card should show the new target");
    }

    /*
       The row saves on every change, which is one keystroke away from saving a
       half-typed value — emptying the field is what happens on the way to typing a
       new number. It cannot: the target is @NotNull, and the binder turns that into
       a required field, which then refuses to be emptied at all. The store's own
       constraint stays behind it for callers that are not a form.
    */
    @Test
    void aCounterCannotBeLeftWithoutATarget(@Autowired BrowserlessUIContext ui) {
        store("LLLL", Instant.now(), 6.5, 21.0);
        ui.navigate(DashboardView.class, "LLLL");

        openSettings(ui, "DHT");
        ui.findTextField().withPlaceholder("What is hanging").setValue("hirvi");
        ui.findButton().withText("Start").click();

        openSettings(ui, "DHT");

        assertTrue(targetOfTheRunningCounter(ui).component().isRequiredIndicatorVisible(),
                "The constraint should have reached the field");
        assertThrows(IllegalArgumentException.class,
                () -> targetOfTheRunningCounter(ui).setValue(null),
                "and a required field should refuse to be emptied");
        assertTrue(ui.findSpan().withTextContaining("/ 40.0 °Cd").exists());
    }

    /*
       The four limits are one field, so the violation about them as a set belongs to
       that field rather than to the form. Reaching for it by type is the point: it
       is a component of its own now, not four numbers in a layout.
    */
    private static TemperatureBandsField bands(BrowserlessUIContext ui) {
        return ui.find(TemperatureBandsField.class).first();
    }

    /*
       The running counter's row comes before the one that starts a new counter, and
       both have a target field. Index rather than a test id: the order is the
       reading order, and it is what a reader picks by too. The index is one based.
    */
    private static com.vaadin.flow.component.textfield.NumberFieldLocator
            targetOfTheRunningCounter(BrowserlessUIContext ui) {
        return ui.findNumberField().withAriaLabel("Target in degree-days").atIndex(1);
    }

    /*
       The failure nothing else can see: a device that has stopped sending. The badge
       and the notifications share one rule, and this is the readable half of it.
    */
    @Test
    void aSilentDeviceIsMarkedOffline(@Autowired BrowserlessUIContext ui) {
        Instant lastSeen = Instant.now().minus(Duration.ofMinutes(40));
        for (int i = 5; i >= 0; i--) {
            store("HHHH", lastSeen.minus(Duration.ofMinutes(5L * i)), 6.5, 21.0);
        }

        ui.navigate(DashboardView.class, "HHHH");

        assertTrue(ui.find(Badge.class).all().stream()
                        .anyMatch(badge -> badge.getText().contains("Offline")),
                "A device 40 minutes late on a five-minute rhythm is offline");
    }

    @Test
    void aReportingDeviceIsNotMarkedOffline(@Autowired BrowserlessUIContext ui) {
        for (int i = 5; i >= 0; i--) {
            store("IIII", Instant.now().minus(Duration.ofMinutes(5L * i)), 6.5, 21.0);
        }

        ui.navigate(DashboardView.class, "IIII");

        assertTrue(ui.find(Badge.class).all().stream()
                .noneMatch(badge -> badge.getText().contains("Offline")));
    }

    /** A packet as a device with ENABLE_INTERNAL_TEMPERATURE actually sends it. */
    private void storeWithChip(String deviceId, double dht, double chip) {
        measurements.store(new DeviceMeasurement(deviceId, 1, Instant.now(), List.of(
                new SensorMeasurement("DHT", dht, 45.0),
                new SensorMeasurement(SensorMeasurement.INTERNAL_SENSOR_ID, chip, null),
                new SensorMeasurement("RBF", 21.0, 40.0))));
    }

    /**
     * A reading that arrives while the page is open reaches the page.
     *
     * <p>This is what replaced the poll, and it is worth a test of its own because
     * the failure is silent: a page that is never told simply keeps showing the last
     * thing it was told, which is indistinguishable from a device that has not
     * reported. The packet is stored and announced the way the UDP thread does it —
     * from outside the session lock — and the assertion is that the new sensor has a
     * card without anybody navigating again.
     */
    @Test
    void aPacketArrivingWhileThePageIsOpenReachesIt(@Autowired BrowserlessUIContext ui) {
        store("PUSH", Instant.now(), 6.5, 21.0);
        ui.navigate(DashboardView.class, "PUSH");
        assertEquals(List.of("DHT", "RBF"), sensorCardTitles(ui));

        measurements.store(new DeviceMeasurement("PUSH", 2, Instant.now(), List.of(
                new SensorMeasurement("DHT", 6.5, 45.0),
                new SensorMeasurement("RBF", 21.0, 40.0),
                new SensorMeasurement("NEW", 12.0, 55.0))));
        deliver(ui, () -> updates.arrived("PUSH"));

        assertEquals(List.of("DHT", "NEW", "RBF"), sensorCardTitles(ui),
                "the new sensor should be on the page without navigating again");
    }

    /**
     * Publishes the way the receiving thread does, then runs what UI.access queued.
     *
     * <p>The queue is the part that needs saying. Work handed to a UI belongs to
     * whoever holds the session lock, and in a test that is the test itself, for its
     * whole length — so nothing runs until it is asked to. In production the push
     * connection's thread does this on unlocking.
     */
    private static void deliver(BrowserlessUIContext ui, Runnable event) {
        event.run();
        VaadinSession session = ui.getUI().getSession();
        session.getService().runPendingAccessTasks(session);
    }

    private void store(String deviceId, Instant at, double dht, double ruuvi) {
        measurements.store(new DeviceMeasurement(deviceId, 1, at, List.of(
                new SensorMeasurement("DHT", dht, 45.0),
                new SensorMeasurement("RBF", ruuvi, 40.0))));
    }

    /** The sensor names as the reader sees them, from the cards' title slots. */
    private static List<String> sensorCardTitles(BrowserlessUIContext ui) {
        return ui.find(SensorCard.class).all().stream().map(Slots::titleOf).toList();
    }

    /*
       The cog is in the card's header suffix slot, out of the locators' reach, and
       the popover builds its content when opened.
    */
    private static void openSettings(BrowserlessUIContext ui, String sensorId) {
        SensorCard card = ui.find(SensorCard.class).all().stream()
                .filter(candidate -> Slots.titleOf(candidate).equals(sensorId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No card for sensor " + sensorId));
        Slots.require(card, PopoverButton.class).click();
    }
}
