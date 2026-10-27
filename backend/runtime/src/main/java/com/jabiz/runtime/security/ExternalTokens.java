package com.jabiz.runtime.security;

import com.jabiz.query.BoundValue;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import com.jabiz.runtime.storage.StorageEngine;
import com.jabiz.runtime.storage.UniqueKeyViolationException;
import com.jabiz.security.ExternalTokenRules;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Tokens of external token providers (decision D59): {@link #verify} parses a compact JWT and applies
 * {@link ExternalTokenRules}; {@link #useOnce} records its token id so that a sign-in uses each token once. The uses
 * are an ordinary table the platform writes itself, like the OpenID Connect states: anyone may present a token, so
 * the rows are not kept beyond the token's expiry (each use removes the expired ones).
 */
@Component
public class ExternalTokens {

    /** The longest token read: a session token is a few hundred bytes. */
    static final int MAX_TOKEN_LENGTH = 4096;
    static final int MAX_IDENTITY_SUBJECT = 255;
    /** Expired uses removed by one sign-in at most: more than come in between two sign-ins. */
    static final int CLEANUP_BATCH = 100;
    private static final Pattern PART = Pattern.compile("[A-Za-z0-9_-]+");
    private static final JsonMapper STRICT = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .build();

    private final StorageAdapterRegistry storages;
    private final String poolRef;
    private final Clock clock;

    public ExternalTokens(StorageAdapterRegistry storages,
        @Value("${jabiz.storage.default-pool-ref:default}") String poolRef, Clock clock) {
        this.storages = storages;
        this.poolRef = poolRef;
        this.clock = clock;
    }

    /**
     * Verifies a token of the provider now.
     *
     * @throws ExternalTokenRules.RefusedException naming why it is not good
     */
    public ExternalTokenRules.Verified verify(ExternalTokenProvider provider, String token) {
        if (token == null || token.length() > MAX_TOKEN_LENGTH) {
            throw new ExternalTokenRules.RefusedException(ExternalTokenRules.Failure.MALFORMED);
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3 || !PART.matcher(parts[0]).matches() || !PART.matcher(parts[1]).matches()
            || !PART.matcher(parts[2]).matches()) {
            throw new ExternalTokenRules.RefusedException(ExternalTokenRules.Failure.MALFORMED);
        }
        Map<String, Object> header = object(parts[0]);
        Map<String, Object> claims = object(parts[1]);
        byte[] signature = decode(parts[2]);
        ExternalTokenRules.Verified verified = ExternalTokenRules.verify(header, claims, parts[0] + "." + parts[1],
            signature, provider.secretBytes(), provider.settings(), clock.instant());
        if (ExternalTokenProvider.identitySubject(verified).length() > MAX_IDENTITY_SUBJECT) {
            throw new ExternalTokenRules.RefusedException(ExternalTokenRules.Failure.SUBJECT);
        }
        return verified;
    }

    /**
     * Uses a verified token once, in its own transaction; a second use fails with
     * {@link ExternalTokenRules.Failure#REPLAYED}. The row is kept until the token could no longer be used anyway.
     */
    public Mono<Void> useOnce(ExternalTokenProvider provider, ExternalTokenRules.Verified token) {
        return Mono.defer(() -> {
            StorageEngine engine = storages.getEngine(poolRef);
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            Map<String, Object> use = new LinkedHashMap<>();
            use.put("provider", provider.name());
            use.put("token_id", token.tokenId());
            use.put("used_at", now);
            use.put("expires_at", token.expiresAt().plus(provider.skew()));
            // A few expired rows at a time, skipping those another sign-in is removing: sign-ins never wait on
            // each other here.
            return engine.inTransaction(engine.select(
                    "DELETE FROM sec_external_token_use WHERE (provider, token_id) IN (SELECT provider, token_id"
                        + " FROM sec_external_token_use WHERE expires_at < :now LIMIT " + CLEANUP_BATCH
                        + " FOR UPDATE SKIP LOCKED) RETURNING token_id",
                    Map.of("now", BoundValue.of(now)))
                .then(engine.insert("sec_external_token_use", use)))
                .onErrorMap(UniqueKeyViolationException.class,
                    e -> new ExternalTokenRules.RefusedException(ExternalTokenRules.Failure.REPLAYED));
        });
    }

    private static Map<String, Object> object(String part) {
        try {
            Object value = STRICT.readValue(decode(part), Object.class);
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> result = new LinkedHashMap<>();
                map.forEach((key, entry) -> result.put(String.valueOf(key), entry));
                return result;
            }
        } catch (JacksonException e) {
            // answered below
        }
        throw new ExternalTokenRules.RefusedException(ExternalTokenRules.Failure.MALFORMED);
    }

    private static byte[] decode(String part) {
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(part);
            // One spelling per value, as for webhook signatures: no stray bits after the last byte.
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(part)) {
                throw new ExternalTokenRules.RefusedException(ExternalTokenRules.Failure.MALFORMED);
            }
            return bytes;
        } catch (IllegalArgumentException e) {
            throw new ExternalTokenRules.RefusedException(ExternalTokenRules.Failure.MALFORMED);
        }
    }
}
