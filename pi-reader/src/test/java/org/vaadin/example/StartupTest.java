package org.vaadin.example;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import org.vaadin.example.lora.LoraReceiver;
import org.vaadin.example.ruuvi.BleScanner;
import org.vaadin.example.thingy.ThingyReader;
import org.vaadin.example.upstream.ScrewCloudSender;

/**
 * That the application starts at all, with the real {@code application.properties}.
 *
 * <p>Every other test here builds its beans by hand, which is what makes them fast
 * and what makes them blind to this: a configuration value that cannot be
 * converted is not a compile error and not a unit test failure. It is a process
 * that exits 1 on the Pi, several minutes after a deployment, with one line in the
 * journal.
 *
 * <p>Written after exactly that. {@code screwcloud.thingy.address} is empty in the
 * properties file, an empty value is no value as far as the config layer is
 * concerned, and a plain {@code String} injection point therefore does not fall
 * back to its default — it fails to convert and takes the application down. Every
 * unit test passed while the service would not start.
 *
 * <p>So this asks for one of each bean that reads configuration. Reaching the
 * assertions at all is the test; the assertions themselves are almost incidental.
 */
@QuarkusTest
class StartupTest {

    @Inject
    BleScanner scanner;

    @Inject
    ThingyReader thingy;

    @Inject
    LoraReceiver lora;

    @Inject
    ScrewCloudSender sender;

    /**
     * Every bean that reads configuration can be built and answers for itself.
     *
     * <p>The statuses are asked for because they are what the page shows, so a bean
     * that started but cannot say anything about itself is also worth catching.
     */
    @Test
    void everyBeanThatReadsConfigurationCanBeBuilt() {
        assertNotNull(scanner.status());
        assertNotNull(thingy.status());
        assertNotNull(lora.status());
        assertNotNull(sender.status());
        assertNotNull(sender.deviceId(), "the identifier every packet is filed under");
    }
}
