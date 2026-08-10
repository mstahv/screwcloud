package fi.mstahv.sensorhub;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A PostgreSQL container for the tests, shared by every test that needs a
 * database.
 *
 * <p>{@code @ServiceConnection} makes Spring Boot read the container's host,
 * port, database, user and password and feed them to the application as
 * connection details. They take precedence over the {@code spring.datasource.*}
 * settings in application.properties, so no test has to know or repeat a JDBC
 * URL.
 *
 * <p>The container lives in a static field on purpose. Each test class with a
 * different configuration gets its own Spring context, and a container created
 * inside the {@code @Bean} method would mean one PostgreSQL per context. One
 * static container is started once and shared.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestDatabase {

    /**
     * The image tag is not pinned to what the production server happens to run,
     * because nothing here depends on a version-specific feature. Override it
     * with {@code SENSORHUB_POSTGRES_IMAGE} to reproduce something version
     * specific — check the server's version with {@code psql -V}.
     */
    private static final String DEFAULT_IMAGE = "postgres:17-alpine";

    /*
       A separate database name from the development container in TestServer, so
       the two can never be the same container: the tests assert on exact row
       sets and would fail against a database holding real measurements.

       Reuse is requested so repeated runs get a warm container instead of a
       fresh one every time. It stays clean regardless, because @DataJpaTest
       rolls back each test.
    */
    private static final PostgreSQLContainer CONTAINER = new PostgreSQLContainer(image())
            .withDatabaseName("screwcloud_test")
            .withReuse(true);

    static String image() {
        String configured = System.getenv("SENSORHUB_POSTGRES_IMAGE");
        return configured == null || configured.isBlank() ? DEFAULT_IMAGE : configured;
    }

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return CONTAINER;
    }
}
