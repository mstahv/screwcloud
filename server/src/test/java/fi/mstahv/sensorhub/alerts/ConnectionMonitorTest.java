package fi.mstahv.sensorhub.alerts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import fi.mstahv.sensorhub.store.AlertSubscriptionStore;
import fi.mstahv.sensorhub.store.ClientDeviceStore;
import fi.mstahv.sensorhub.store.MeasurementStore;
import fi.mstahv.sensorhub.store.PushSubscription;

/**
 * No Spring context and no database: the stores are mocks and the clock is a
 * variable, because what is being tested is when the sweep speaks and when it
 * stays quiet.
 */
class ConnectionMonitorTest {

    private static final Instant NOON = Instant.parse("2026-08-10T12:00:00Z");
    private static final Duration FIVE_MINUTES = Duration.ofMinutes(5);
    private static final String DEVICE = "LAHT";
    private static final String ALICE = "client-alice";

    private final MeasurementStore measurements = mock(MeasurementStore.class);
    private final ClientDeviceStore clientDevices = mock(ClientDeviceStore.class);
    private final AlertSubscriptionStore subscriptions = mock(AlertSubscriptionStore.class);
    private final WebPushService webPush = mock(WebPushService.class);

    private final MutableClock clock = new MutableClock(NOON);
    private final ConnectionMonitor monitor =
            new ConnectionMonitor(measurements, clientDevices, subscriptions, webPush, clock);

    @BeforeEach
    void setUp() {
        when(webPush.isEnabled()).thenReturn(true);
        when(clientDevices.clientsWatchingForSilence()).thenReturn(Map.of(DEVICE, List.of(ALICE)));
        when(subscriptions.pushSubscriptionsFor(ALICE)).thenReturn(List.of(
                new PushSubscription(ALICE, "https://push.example/alice", "key", "auth", NOON)));
    }

    @Test
    void aDeviceReportingOnTimeIsLeftAlone() {
        reportingUntil(clock.now);

        sweep();

        verify(webPush, never()).send(any(), anyString(), anyString());
    }

    @Test
    void silenceIsAnnouncedOnce() {
        reportingUntil(clock.now.minus(Duration.ofMinutes(30)));

        sweep();
        sweep();
        sweep();

        // Three sweeps, one notification: the outage has not changed.
        verify(webPush, times(1)).send(any(), anyString(), anyString());
    }

    @Test
    void theNotificationNamesTheDeviceAndTheRhythm() {
        reportingUntil(clock.now.minus(Duration.ofMinutes(32)));

        sweep();

        ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(webPush).send(any(), title.capture(), body.capture());
        assertEquals("LAHT is not reporting", title.getValue());
        assertTrue(body.getValue().contains("32 min"), body.getValue());
        assertTrue(body.getValue().contains("expected every 5 min"), body.getValue());
    }

    /*
       The point of tracking state: a device that comes back has to say so, or the
       reader is left waiting for news that never comes.
    */
    @Test
    void recoveryIsAnnouncedWhenReportsResume() {
        reportingUntil(clock.now.minus(Duration.ofMinutes(30)));
        sweep();

        reportingUntil(clock.now);
        sweep();

        verify(webPush, times(2)).send(any(), anyString(), anyString());
        ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
        verify(webPush, times(2)).send(any(), title.capture(), anyString());
        assertEquals("LAHT is reporting again", title.getAllValues().get(1));
    }

    /*
       The outage length is measured from the last packet that arrived, not from
       when the sweep noticed — otherwise every outage would be reported as
       three and a half intervals shorter than it was.
    */
    @Test
    void recoveryReportsTheWholeOutageNotJustTheDetectedPart() {
        reportingUntil(clock.now.minus(Duration.ofMinutes(40)));
        sweep();

        reportingUntil(clock.now);
        sweep();

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(webPush, times(2)).send(any(), anyString(), body.capture());
        assertTrue(body.getAllValues().get(1).contains("40 min"),
                "Expected the full 40 minutes, got: " + body.getAllValues().get(1));
    }

    @Test
    void aDeviceThatWasNeverReportedSilentGetsNoRecoveryNotice() {
        reportingUntil(clock.now);

        sweep();
        sweep();

        verify(webPush, never()).send(any(), anyString(), anyString());
    }

    @Test
    void nobodyIsNotifiedWithoutASubscription() {
        when(clientDevices.clientsWatchingForSilence()).thenReturn(Map.of());
        reportingUntil(clock.now.minus(Duration.ofHours(2)));

        sweep();

        verify(webPush, never()).send(any(), anyString(), anyString());
        // The store is not even asked about a device nobody watches.
        verify(measurements, never()).recentArrivals(eq(DEVICE), anyInt());
    }

    @Test
    void aWatcherWithNotificationsOffIsSkipped() {
        when(subscriptions.pushSubscriptionsFor(ALICE)).thenReturn(List.of());
        reportingUntil(clock.now.minus(Duration.ofHours(2)));

        sweep();

        verify(webPush, never()).send(any(), anyString(), anyString());
    }

    /*
       Without VAPID keys there is nothing to send with, and the sweep should not be
       querying the database every minute for notifications it cannot deliver.
    */
    @Test
    void theSweepDoesNothingWhenPushIsNotConfigured() {
        when(webPush.isEnabled()).thenReturn(false);
        reportingUntil(clock.now.minus(Duration.ofHours(2)));

        sweep();

        verify(clientDevices, never()).clientsWatchingForSilence();
    }

    /*
       A sweep that throws is never scheduled again, which would silently end all
       connection alerts for the lifetime of the server.
    */
    @Test
    void aFailingSweepDoesNotEscape() {
        when(clientDevices.clientsWatchingForSilence())
                .thenThrow(new IllegalStateException("the database is away"));

        sweep();  // must not throw
    }

    /** Makes the store answer as if the device reported every five minutes until then. */
    private void reportingUntil(Instant latest) {
        List<Instant> arrivals = java.util.stream.IntStream.range(0, 12)
                .mapToObj(i -> latest.minus(FIVE_MINUTES.multipliedBy(i)))
                .toList();
        when(measurements.recentArrivals(eq(DEVICE), anyInt())).thenReturn(arrivals);
    }

    private void sweep() {
        monitor.sweep();
    }

    /*
       The monitor remembers which devices it has already reported, so the test
       needs one instance and a clock it can move — not a fresh monitor per sweep.
    */
    private static class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
