package fi.mstahv.sensorhub.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.vaadin.browserless.BrowserlessUIContext;
import com.vaadin.flow.component.badge.Badge;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.vaadin.firitin.components.popover.PopoverButton;

import fi.mstahv.sensorhub.protocol.DeviceMeasurement;
import fi.mstahv.sensorhub.protocol.SensorMeasurement;
import fi.mstahv.sensorhub.store.MeasurementStore;
import fi.mstahv.sensorhub.store.SensorSettingsStore;

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

    @Test
    void everySensorInThePacketGetsACard(@Autowired BrowserlessUIContext ui) {
        store("AAAA", Instant.now(), 6.5, 21.0);

        ui.navigate(DashboardView.class, "AAAA");

        assertEquals(List.of("DHT", "RBF"), sensorCardTitles(ui));
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

        assertTrue(ui.findParagraph().withTextContaining("The limits must increase").exists(),
                "The reader should be told which way the set is wrong");
        assertFalse(ui.findButton().withText("Save").component().isEnabled(),
                "and Save should not offer to store it");
        assertEquals(null, settings.nameFor("DDDD", "DHT"),
                "A form that cannot be saved must not have renamed the sensor");
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

        assertTrue(ui.findParagraph().withTextContaining("Give all four temperature limits").exists());
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
