package fi.mstahv.sensorhub;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.test.context.TestPropertySource;

/**
 * A JPA slice test against the real schema in a real PostgreSQL.
 *
 * <p>Combines three things that all have to be right, and each of which fails in
 * its own quiet way if it is not:
 *
 * <ul>
 * <li>{@code replace = NONE} keeps the container's datasource. By default the
 * slice replaces the datasource with an embedded database — which, now that
 * there is no embedded database on the classpath, is a failure rather than a
 * silent downgrade, but the intent is worth stating anyway.
 * <li>Flyway is imported explicitly. Spring Boot 4's {@code @DataJpaTest} slice
 * auto-configures Hibernate and the datasource but not Flyway, so without this
 * there would be no schema at all.
 * <li>{@code ddl-auto=validate} means every test class also checks that the
 * entities still match what the migrations produce. Hibernate creating the
 * schema instead would defeat the point of testing against PostgreSQL.
 * <li>Validation is imported for the same reason as Flyway: the slice does not
 * bring it. Without it the stores' {@code @Validated} would be an annotation with
 * nothing behind it, and every test asserting that a bad value is refused would
 * fail — while the application, which does auto-configure it, refused them.
 * </ul>
 *
 * <p>Each test runs in a transaction that is rolled back, so tests do not see
 * each other's rows even though they share one container.
 *
 * <p>Add {@code @Import({TestDatabase.class, TheStoreUnderTest.class})} to the
 * test class: the container itself is not imported here, so that each test class
 * has a single, complete list of what it wires in.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration({FlywayAutoConfiguration.class, ValidationAutoConfiguration.class})
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=validate")
public @interface DatabaseTest {
}
