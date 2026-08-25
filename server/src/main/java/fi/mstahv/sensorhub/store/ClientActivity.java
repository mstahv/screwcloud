package fi.mstahv.sensorhub.store;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;

/**
 * When a browser token was last seen on the pages.
 *
 * <p>There are no accounts, so this is the whole of what "user activity" means
 * here — and it exists for one reason: so that a browser that stops visiting
 * can eventually be forgotten, list and subscriptions and all, by
 * {@link RetentionSweep}. Push notifications alone do not count as activity;
 * a phone that only receives is exactly the case where nobody is looking.
 *
 * <p>The token itself is the key: one row per browser, updated in place.
 */
@Entity
@Table(name = "client_activity")
public class ClientActivity {

    @Id
    @Column(length = 64)
    private String clientId;

    @NotNull
    @Column(nullable = false)
    private Instant lastSeen;

    /** JPA requires a default constructor. */
    protected ClientActivity() {
    }

    public ClientActivity(String clientId, Instant lastSeen) {
        this.clientId = clientId;
        this.lastSeen = lastSeen;
    }

    public String getClientId() {
        return clientId;
    }

    public Instant getLastSeen() {
        return lastSeen;
    }

    public void setLastSeen(Instant lastSeen) {
        this.lastSeen = lastSeen;
    }
}
