package fi.mstahv.sensorhub.ui;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import fi.mstahv.sensorhub.TestDatabase;

/**
 * A test that drives the real views against the real application.
 *
 * <p>The whole context: PostgreSQL in a container, Flyway's schema, the stores, the
 * routes and the components. Only the browser is absent.
 *
 * <p>These are the tests that would have caught the two UI faults this project
 * found by hand — a notification switch that was invisible without VAPID keys, and
 * a fresh degree-day counter reporting itself frozen. Neither was visible to a store
 * test, and both were one assertion away from being visible here.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@Import({TestDatabase.class, BrowserlessSetup.class})
/*
   Transactional, and therefore rolled back. These tests drive the real stores, so
   without it they commit rows into the container the slice tests share — and those
   assert on exact row sets. The first run of this class broke six store tests that
   had nothing to do with the UI, which is a good demonstration of why.

   The view runs on the test's own thread, so it takes part in the test's
   transaction and sees its own writes.
*/
@Transactional
@TestPropertySource(properties = {
        // 0 = let the OS pick a port, so a test run does not claim 5555
        "sensorhub.udp.port=0"
})
public @interface UiTest {
}
