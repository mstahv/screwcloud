package fi.mstahv.sensorhub.ui;

import com.vaadin.browserless.BrowserlessApplicationContext;
import com.vaadin.browserless.BrowserlessUIContext;
import com.vaadin.browserless.SpringBrowserlessApplicationContext;

import fi.mstahv.sensorhub.SensorHubApplication;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

/**
 * The browserless UI harness, wired the way the Vaadin archetype wires it.
 *
 * <p>The views, the components and the routing all run in the JVM: no browser, no
 * frontend build, and every Spring bean is the real one. What is <em>not</em> real
 * is the browser, which matters in one place — see {@link Browser}.
 */
@Configuration
public class BrowserlessSetup {

    @Bean
    BrowserlessApplicationContext browserlessApplicationContext(ApplicationContext springContext) {
        return SpringBrowserlessApplicationContext.create(springContext, SensorHubApplication.class);
    }

    /**
     * A window of its own for every test method.
     *
     * <p>Prototype scope, not the singleton the archetype's one-test example uses.
     * Sharing a window means sharing a session and one growing component tree: views
     * from earlier tests linger, an earlier test's hidden empty-state is found
     * instead of this test's visible one, and cards from three tests ago answer a
     * query about this one. Every symptom looks like a bug in the view.
     *
     * <p>The database is shared, since these tests are not transactional. That is
     * harmless here because each fresh window is a fresh browser with a fresh
     * localStorage token, and every list in this application is keyed by that token.
     */
    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    BrowserlessUIContext browserlessUIContext(BrowserlessApplicationContext applicationContext) {
        return applicationContext.newUser().newWindow();
    }
}
