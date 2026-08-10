package fi.mstahv.sensorhub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.test.context.TestPropertySource;

/**
 * Boots the whole application against the container database.
 *
 * <p>This is the production chain in miniature: the application starts with the
 * settings it really runs with, Flyway creates the schema, and
 * {@code ddl-auto=validate} then checks it against the entities. If the
 * migrations and the entities diverge, the context does not start.
 *
 * <p>The slice tests cover the same validate step, but only for the parts of the
 * context they wire in. This one also proves that the UI, the UDP listener and
 * the database come up together.
 */
/*
   The web environment is MOCK (the default) rather than NONE: Vaadin's
   SpringBootAutoConfiguration requires a WebApplicationContext, so NONE would
   fail the context before the schema is ever reached.
*/
@SpringBootTest
@Import(TestDatabase.class)
@TestPropertySource(properties = {
        // 0 = let the OS pick a free port so the test does not claim 5555
        "sensorhub.udp.port=0"
})
class SchemaMigrationTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void migrationProducesSchemaThatMatchesEntities() throws SQLException {
        // The context starting at all is already a result: validate passed.
        Set<String> tables = readTableNames();

        assertTrue(tables.contains("MEASUREMENT_SAMPLE"), tables.toString());
        assertTrue(tables.contains("SENSOR_SETTINGS"), tables.toString());
        assertTrue(tables.contains("CLIENT_DEVICE"), tables.toString());
        assertTrue(tables.contains("FLYWAY_SCHEMA_HISTORY"), tables.toString());
    }

    @Test
    void everyMigrationIsRecordedAsApplied() throws Exception {
        /*
           Counted against the scripts on the classpath rather than against a
           number written here, so adding a migration does not mean editing this
           test — and a migration that is present but never ran still fails it.

           Successes are counted rather than rows: a version that fails to apply
           leaves a row with success = false rather than no row at all.
        */
        int scripts = new PathMatchingResourcePatternResolver()
                .getResources("classpath:db/migration/V*.sql").length;
        assertTrue(scripts > 0, "No migration scripts found");

        try (Connection connection = dataSource.getConnection();
                ResultSet applied = connection.createStatement().executeQuery(
                        "select count(*) from flyway_schema_history"
                                + " where success = true and version is not null")) {
            assertTrue(applied.next());
            assertEquals(scripts, applied.getInt(1),
                    "Every migration script should be applied and successful");
        }
    }

    /*
       PostgreSQL folds unquoted identifiers to lower case, so everything the
       migrations create ends up lower case. The names are upper-cased here to
       keep the assertions readable.
    */
    private Set<String> readTableNames() throws SQLException {
        Set<String> names = new HashSet<>();
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            try (ResultSet tables = metaData.getTables(null, "public", "%", new String[] {"TABLE"})) {
                while (tables.next()) {
                    names.add(tables.getString("TABLE_NAME").toUpperCase(Locale.ROOT));
                }
            }
        }
        return names;
    }
}
