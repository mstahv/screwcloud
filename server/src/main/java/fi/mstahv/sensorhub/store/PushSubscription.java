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
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import fi.mstahv.sensorhub.validation.PushEndpoint;

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

    /*
       The keys are base64url as the Push API hands them over. Constraining the
       alphabet is not cosmetic: these strings are decoded and fed to the crypto
       layer, and the only thing standing behind them is that a browser said so.
    */
    private static final String BASE64URL = "[A-Za-z0-9_=-]+";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Size(max = 64)
    @Column(nullable = false, length = 64)
    private String clientId;

    @NotBlank
    @PushEndpoint
    @Size(max = MAX_ENDPOINT_LENGTH)
    @Column(nullable = false, length = MAX_ENDPOINT_LENGTH)
    private String endpoint;

    /** The browser's public key for encrypting the payload. */
    @NotBlank
    @Pattern(regexp = BASE64URL, message = "The push key is not base64url")
    @Size(max = 255)
    @Column(nullable = false, length = 255)
    private String p256dh;

    /** The browser's authentication secret. */
    @NotBlank
    @Pattern(regexp = BASE64URL, message = "The push secret is not base64url")
    @Size(max = 255)
    @Column(nullable = false, length = 255)
    private String auth;

    @NotNull
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
