package fi.mstahv.sensorhub;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.TestcontainersConfiguration;

/**
 * Runs the application locally with a PostgreSQL container from Docker.
 *
 * <p>Start this instead of {@link SensorHubApplication} in development: it is the
 * same application, with the database handed to it by Testcontainers instead of
 * expecting a PostgreSQL installed on the machine. Everything else — the UDP
 * listener, the UI on port 8080, Vaadin's hot reload — behaves exactly as in
 * production.
 *
 * <p>It lives in the test sources because that is where the Testcontainers
 * dependencies are. It is a {@code main} method rather than a test, so nothing
 * runs it during a build.
 *
 * <p>Notifications work here too: a development VAPID pair is generated on first
 * run and cached, so the switch on the front page is live rather than disabled.
 * Real keys in the environment take precedence. See {@link DevVapidKeys}.
 *
 * <p><b>The container is reusable, so measurements survive a restart</b> — but
 * only if reuse is enabled on the machine, which is a per-user setting
 * Testcontainers deliberately does not read from the project. Without it every
 * restart starts an empty database. See the warning this class logs, or the
 * README.
 */
public final class TestServer {

    private static final Logger log = LoggerFactory.getLogger(TestServer.class);

    public static void main(String[] args) {
        warnIfDataWillNotSurviveRestart();

        /*
           Before the context starts, because the keys have to be in the
           environment by the time WebPushService is constructed. Without them the
           notification UI is disabled, which is correct on a server with no keys
           but no use at all when the UI is what you are working on.
        */
        DevVapidKeys.applyUnlessConfigured();

        SpringApplication.from(SensorHubApplication::main)
                .with(DevelopmentDatabase.class)
                .run(args);
    }

    private static void warnIfDataWillNotSurviveRestart() {
        if (TestcontainersConfiguration.getInstance().environmentSupportsReuse()) {
            return;
        }
        log.warn("""
                Container reuse is not enabled, so this run gets an empty database \
                and everything in it is discarded on exit. To keep measurements \
                between restarts:

                    echo 'testcontainers.reuse.enable=true' >> ~/.testcontainers.properties
                """);
    }

    /**
     * The development database, kept separate from the one the tests use: its own
     * database name means its own container, so a test run cannot touch the
     * measurements collected here.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class DevelopmentDatabase {

        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            /*
               Reuse is what makes the data outlive the application: the
               container is left running on exit and the next start attaches to
               the same one. Its identity is a hash of this configuration, so
               changing the image or the database name here starts a new, empty
               container — as does docker rm.
            */
            return new PostgreSQLContainer(TestDatabase.image())
                    .withDatabaseName("screwcloud")
                    .withReuse(true);
        }
    }

    private TestServer() {
    }
}
