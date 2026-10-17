package com.jabiz.runtime.security;

import com.jabiz.runtime.RateLimitedException;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import com.jabiz.runtime.observability.PlatformObservations;
import com.jabiz.runtime.web.ClientAddresses;
import com.jabiz.runtime.web.TokenBucketLimiter;
import io.micrometer.common.KeyValues;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * The per-address limit of the sign-in endpoints (docs/design/10-security.md section 4.1; decision D47 item 1): signing
 * in with a password, the identity provider's callback, the second factor (at sign-in and step-up) and refreshing,
 * each counted per client address ({@link ClientAddresses}: forwarding headers count only through trusted proxies;
 * an IPv6 address counts as its /64). A token bucket per address and kind of request holds
 * {@code jabiz.security.sign-in.rate-limit.burst} (10) requests and refills at {@code .per-address} (20) per
 * {@code .period} (one minute); beyond it, 429 {@code RATE_LIMITED} with {@code Retry-After}, before anything is read.
 *
 * <p>Next to the lock of an account after failed passwords (section 4), not instead of it: the lock stops guessing one
 * account's password from many addresses, this stops one address from trying many accounts (or flooding the hashing).
 * Every request counts, right or wrong, so a refusal tells nothing about the account. Kept in memory, per instance,
 * as {@link SignUpRateLimits}; {@code .enabled=false} turns it off (an instance behind a gateway that limits already).
 */
@Component
public class SignInRateLimits implements PlatformCheck {

    /** Keys kept per limiter; the least recently used are forgotten first. */
    static final int MAX_KEYS = 100_000;
    static final int DEFAULT_PER_ADDRESS = 20;
    static final int DEFAULT_BURST = 10;
    static final String PREFIX = "jabiz.security.sign-in.rate-limit";

    /** What a request does; each has buckets of its own, so a busy refresh does not lock signing in out. */
    public enum Kind {
        /** {@code POST /api/auth/login}. */
        PASSWORD,
        /** {@code POST /api/auth/oidc/callback}. */
        OIDC_CALLBACK,
        /** {@code POST /api/auth/challenge/verify} and {@code POST /api/auth/step-up}. */
        SECOND_FACTOR,
        /** {@code POST /api/auth/refresh}. */
        REFRESH
    }

    private final boolean enabled;
    private final int perAddress;
    private final int burst;
    private final Duration period;
    private final ClientAddresses clients;
    private final PlatformObservations observations;
    private final Map<Kind, TokenBucketLimiter> limiters = new EnumMap<>(Kind.class);

    @Autowired
    public SignInRateLimits(@Value("${" + PREFIX + ".enabled:true}") boolean enabled,
        @Value("${" + PREFIX + ".per-address:" + DEFAULT_PER_ADDRESS + "}") int perAddress,
        @Value("${" + PREFIX + ".burst:" + DEFAULT_BURST + "}") int burst,
        @Value("${" + PREFIX + ".period:PT1M}") Duration period, ClientAddresses clients,
        PlatformObservations observations) {
        this(enabled, perAddress, burst, period, clients, observations, System::nanoTime);
    }

    /** As above, timed by {@code nanoTime} (tests). */
    public SignInRateLimits(boolean enabled, int perAddress, int burst, Duration period, ClientAddresses clients,
        PlatformObservations observations, LongSupplier nanoTime) {
        this.enabled = enabled;
        this.perAddress = perAddress;
        this.burst = burst;
        this.period = period;
        this.clients = Objects.requireNonNull(clients, "clients");
        this.observations = observations;
        // Out-of-range settings are the startup check's to report; the buckets keep to what they can work with.
        Duration usable = period == null || period.isNegative() || period.isZero() ? Duration.ofMinutes(1) : period;
        for (Kind kind : Kind.values()) {
            limiters.put(kind, new TokenBucketLimiter(Math.max(1, burst), Math.max(1, perAddress), usable, MAX_KEYS,
                nanoTime));
        }
    }

    /** Counts a request of {@code kind} from {@code request}'s client; 429 {@code RATE_LIMITED} beyond the limit. */
    public void count(Kind kind, ServerHttpRequest request) {
        if (!enabled) {
            return;
        }
        count(kind, clients.clientOf(request));
    }

    /** Counts a request of {@code kind} from the client address {@code client} (as {@link ClientAddresses} gives it). */
    void count(Kind kind, String client) {
        if (!enabled) {
            return;
        }
        String key = ClientAddresses.rateLimitKey(Objects.requireNonNullElse(client, "unknown"));
        TokenBucketLimiter.Decision decision = limiters.get(kind).tryAcquire(key);
        if (!decision.allowed()) {
            if (observations != null) {
                observations.event(PlatformObservations.AUTH_RATE_LIMITED,
                    KeyValues.of("kind", kind.name().toLowerCase(Locale.ROOT)));
            }
            throw new RateLimitedException("Too many sign-in requests from this address; try again in "
                + decision.retryAfterSeconds() + " seconds", decision.retryAfterSeconds());
        }
    }

    @Override
    public List<CheckProblem> check() {
        List<CheckProblem> problems = new ArrayList<>();
        if (perAddress <= 0) {
            problems.add(CheckProblem.error("SECURITY", PREFIX + ".per-address", "must be positive"));
        }
        if (burst <= 0) {
            problems.add(CheckProblem.error("SECURITY", PREFIX + ".burst", "must be positive"));
        }
        if (period == null || period.isNegative() || period.isZero()) {
            problems.add(CheckProblem.error("SECURITY", PREFIX + ".period", "must be positive"));
        }
        return problems;
    }
}
