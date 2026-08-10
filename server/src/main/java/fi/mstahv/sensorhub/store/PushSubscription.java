package fi.mstahv.sensorhub.store;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * One browser's web push subscription: where the push service will deliver a
 * notification, and the keys that encrypt it.
 *
 * <p>Hangs off the same browser token as the device list. A browser has one
 * subscription at a time; the endpoint is unique because the push service can
 * hand out a new one for the same browser, and the old one then has to be
 * replaced rather than duplicated.
 *
 * <p>The keys are a shared secret between the browser and this server, and they
 * are all that stands between a stranger and the ability to push notifications
 * to someone's phone. They are stored as the browser gave them, which is what
 * the protocol requires — but it does mean this table is worth as much care as a
 * password table.
 */
@Entity
@Table(name = "push_subscription",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_push_subscription_endpoint",
                columnNames = {"endpoint"}),
        indexes = @Index(name = "idx_push_subscription_client", columnList = "clientId"))
public class PushSubscription {

    /*
       Push endpoints are URLs from the browser vendor's service and can be long.
       512 is comfortably above what Google, Mozilla and Apple issue in practice.
    */
    static final int MAX_ENDPOINT_LENGTH = 512;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String clientId;

    @Column(nullable = false, length = MAX_ENDPOINT_LENGTH)
    private String endpoint;

    /** The browser's public key for encrypting the payload. */
    @Column(nullable = false, length = 255)
    private String p256dh;

    /** The browser's authentication secret. */
    @Column(nullable = false, length = 255)
    private String auth;

    @Column(nullable = false)
    private Instant createdAt;

    /** JPA requires a default constructor. */
    protected PushSubscription() {
    }

    public PushSubscription(String clientId, String endpoint, String p256dh, String auth,
                            Instant createdAt) {
        this.clientId = clientId;
        this.endpoint = endpoint;
        this.p256dh = p256dh;
        this.auth = auth;
        this.createdAt = createdAt;
    }

    public String getClientId() {
        return clientId;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public String getP256dh() {
        return p256dh;
    }

    public String getAuth() {
        return auth;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
