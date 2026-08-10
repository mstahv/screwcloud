package fi.mstahv.sensorhub.store;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Logs which database the application is actually connected to.
 *
 * <p>This exists because "the measurements are gone" is almost always "the
 * application is talking to a different database than you are". One line naming
 * the server, the database and the user removes the guesswork.
 *
 * <p>The details are read from the live connection rather than from
 * {@code spring.datasource.url}, because that property is not always the answer:
 * when Testcontainers supplies the database the URL is decided at runtime and
 * never appears in any properties file at all.
 */
@Component
class DatabaseLocation {

    private static final Logger log = LoggerFactory.getLogger(DatabaseLocation.class);

    private final DataSource dataSource;

    DatabaseLocation(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /*
       On ApplicationReadyEvent rather than in the constructor: opening a
       connection while the context is still being built would tangle this bean's
       creation order with Flyway's and Hibernate's, for a line of logging.
    */
    @EventListener(ApplicationReadyEvent.class)
    void logConnection() {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            log.info("Measurement database: {} as {} ({} {})",
                    metaData.getURL(),
                    metaData.getUserName(),
                    metaData.getDatabaseProductName(),
                    metaData.getDatabaseProductVersion());
        } catch (SQLException e) {
            // Never fatal: the application has already started, and whatever is
            // wrong with the connection will surface on the first real query.
            log.warn("Could not read the database connection details: {}", e.getMessage());
        }
    }
}
