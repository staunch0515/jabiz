package com.jabiz.runtime.security.rotation;

import com.jabiz.query.BoundValue;
import com.jabiz.runtime.security.JwtService;
import com.jabiz.runtime.storage.Rows;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * How long a retired JWT secret is accepted (decision D78): {@code jabiz.security.jwt.previous-retention} (one year by
 * default, so that unsubscribe links of mail sent before the change - which do not expire - keep working that long)
 * from when the platform first saw it, recorded by its id in {@code sec_key_first_seen}. Past that, what it signed is
 * refused even while it is still configured, and the log says to remove it. Checked when the application is ready
 * (off the request path); until then a retired secret is accepted as configured.
 */
@Component
public class RetiredJwtSecrets {

    private static final Logger log = LoggerFactory.getLogger(RetiredJwtSecrets.class);
    static final String KIND = "jwt";

    private final JwtService jwt;
    private final StorageAdapterRegistry storages;
    private final String poolRef;
    private final Duration retention;
    private final Clock clock;

    public RetiredJwtSecrets(JwtService jwt, StorageAdapterRegistry storages,
        @Value("${jabiz.storage.default-pool-ref:default}") String poolRef,
        @Value("${jabiz.security.jwt.previous-retention:P365D}") Duration retention, Clock clock) {
        if (retention.isNegative() || retention.isZero()) {
            throw new IllegalStateException("jabiz.security.jwt.previous-retention must be positive");
        }
        this.jwt = jwt;
        this.storages = storages;
        this.poolRef = poolRef;
        this.retention = retention;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void apply() {
        Instant now = clock.instant();
        for (String id : jwt.previousIds()) {
            Instant firstSeen = storages.getEngine(poolRef).select("INSERT INTO sec_key_first_seen (key_kind, key_id,"
                    + " first_seen) VALUES (:kind, :id, :now) ON CONFLICT (key_kind, key_id) DO NOTHING RETURNING first_seen",
                    Map.of("kind", BoundValue.of(KIND), "id", BoundValue.of(id), "now", BoundValue.of(now)))
                .concatWith(storages.getEngine(poolRef).select("SELECT first_seen FROM sec_key_first_seen"
                    + " WHERE key_kind = :kind AND key_id = :id", Map.of("kind", BoundValue.of(KIND),
                    "id", BoundValue.of(id))))
                .next()
                .map(row -> Rows.instant(row.get("first_seen")))
                .block();
            Instant until = firstSeen.plus(retention);
            jwt.retireUntil(id, until);
            if (now.isAfter(until)) {
                log.warn("The retired JWT secret {} is past jabiz.security.jwt.previous-retention ({}): what it "
                    + "signed is refused; remove it from JABIZ_JWT_SECRET_PREVIOUS", id, retention);
            } else {
                log.info("The retired JWT secret {} is accepted until {}", id, until);
            }
        }
    }
}
