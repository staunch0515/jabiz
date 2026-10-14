package com.jabiz.runtime.security;

import com.jabiz.runtime.RateLimitedException;
import com.jabiz.runtime.web.TokenBucketLimiter;
import com.jabiz.security.EmailVerification;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Rate limits of the anonymous account endpoints (docs/design/10-security.md section 15; decision D36 items 2, 3 and
 * 5): per client address ({@code ClientAddresses}, so forged forwarding headers count only through trusted proxies)
 * and per e-mail address, lower case. Defaults: {@code jabiz.security.sign-up.rate-limit.per-address} (20) and
 * {@code .per-email} (3) requests per {@code .period} (one hour), counted for each kind of request apart
 * (registering, resending the verification, asking for a password reset). A signed-in user's own password change
 * counts per client address and per user ({@code jabiz.security.account.rate-limit.per-user}, 10 per period); ending
 * sessions of one's own and linking provider accounts count per user with the same limit, apart. Accounts opened by
 * identity providers count per client address as registrations do ({@link Kind#OIDC_SIGN_UP}); anonymous starts of a
 * sign-in through a provider have a limit of their own ({@code jabiz.security.oidc.rate-limit.per-address}, 60).
 *
 * <p>An address is counted whether or not it belongs to an account, so a refusal tells nothing about it. Kept in
 * memory, per instance (as the public API's limit, docs/design/15-public-access.md section 6).
 */
@Component
public class SignUpRateLimits {

    /** Keys kept per limiter; the least recently used are forgotten first. */
    static final int MAX_KEYS = 100_000;

    /** What a request does; each has buckets of its own. */
    public enum Kind {
        REGISTER, VERIFY_RESEND, PASSWORD_RESET, LINK,
        /** A signed-in user changing their own password (section 15.8): counted per user and per address. */
        PASSWORD_CHANGE,
        /** A signed-in user ending sessions of their own (section 15.9): counted per user. */
        SESSION_REVOKE,
        /** An account opened by an identity provider's sign-in (section 12; decision D39): counted per address. */
        OIDC_SIGN_UP,
        /** Starting a sign-in through an identity provider (section 12), anonymous: counted per address. */
        OIDC_START,
        /** A signed-in user starting to link a provider account (section 12): counted per user. */
        IDENTITY_LINK
    }

    /** Default of {@code jabiz.security.account.rate-limit.per-user}. */
    static final int DEFAULT_PER_USER = 10;
    /** Default of {@code jabiz.security.oidc.rate-limit.per-address}. */
    static final int DEFAULT_OIDC_START_PER_ADDRESS = 60;

    private final TokenBucketLimiter perAddress;
    private final TokenBucketLimiter perEmail;
    private final TokenBucketLimiter perUser;
    private final TokenBucketLimiter oidcStart;

    @Autowired
    public SignUpRateLimits(@Value("${jabiz.security.sign-up.rate-limit.per-address:20}") int perAddress,
        @Value("${jabiz.security.sign-up.rate-limit.per-email:3}") int perEmail,
        @Value("${jabiz.security.sign-up.rate-limit.period:PT1H}") Duration period,
        @Value("${jabiz.security.account.rate-limit.per-user:" + DEFAULT_PER_USER + "}") int perUser,
        @Value("${jabiz.security.oidc.rate-limit.per-address:" + DEFAULT_OIDC_START_PER_ADDRESS + "}")
        int oidcStartPerAddress) {
        this(perAddress, perEmail, perUser, oidcStartPerAddress, period, System::nanoTime);
    }

    /** As above, with the default limit of starts through identity providers. */
    public SignUpRateLimits(int perAddress, int perEmail, Duration period, int perUser) {
        this(perAddress, perEmail, perUser, DEFAULT_OIDC_START_PER_ADDRESS, period, System::nanoTime);
    }

    /** As above, with the default limit per user. */
    public SignUpRateLimits(int perAddress, int perEmail, Duration period) {
        this(perAddress, perEmail, period, DEFAULT_PER_USER);
    }

    /** As above, timed by {@code nanoTime} (tests), with the default limit per user. */
    public SignUpRateLimits(int perAddress, int perEmail, Duration period, LongSupplier nanoTime) {
        this(perAddress, perEmail, DEFAULT_PER_USER, period, nanoTime);
    }

    /**
     * @param perUser a signed-in user's attempts to change their own password per period (wrong current passwords
     *                count towards the lock of the account as well)
     */
    public SignUpRateLimits(int perAddress, int perEmail, int perUser, Duration period, LongSupplier nanoTime) {
        this(perAddress, perEmail, perUser, DEFAULT_OIDC_START_PER_ADDRESS, period, nanoTime);
    }

    /**
     * @param oidcStartPerAddress anonymous starts of a sign-in through an identity provider per client address and
     *                            period ({@code jabiz.security.oidc.rate-limit.per-address}): each one writes a row
     */
    public SignUpRateLimits(int perAddress, int perEmail, int perUser, int oidcStartPerAddress, Duration period,
        LongSupplier nanoTime) {
        if (perAddress <= 0 || perEmail <= 0 || perUser <= 0 || oidcStartPerAddress <= 0) {
            throw new IllegalArgumentException("Sign-up rate limits must be positive");
        }
        this.perAddress = new TokenBucketLimiter(perAddress, period, MAX_KEYS, nanoTime);
        this.perEmail = new TokenBucketLimiter(perEmail, period, MAX_KEYS, nanoTime);
        this.perUser = new TokenBucketLimiter(perUser, period, MAX_KEYS, nanoTime);
        this.oidcStart = new TokenBucketLimiter(oidcStartPerAddress, period, MAX_KEYS, nanoTime);
    }

    /**
     * Counts an anonymous start of a sign-in through an identity provider from {@code client}, with a limit of its own
     * ({@code jabiz.security.oidc.rate-limit.per-address}); 429 {@code RATE_LIMITED} beyond it.
     */
    public void oidcStart(String client) {
        acquire(oidcStart, Kind.OIDC_START + " " + Objects.requireNonNullElse(client, "unknown"));
    }

    /**
     * Counts a signed-in user's start of linking a provider account, with the limit of password changes (in buckets
     * of its own): a start may check the current password.
     */
    public void identityLink(String userId) {
        acquire(perUser, Kind.IDENTITY_LINK + " user:" + Objects.requireNonNull(userId, "userId"));
    }

    /** Counts a request of {@code client} (its address as text); 429 {@code RATE_LIMITED} beyond the limit. */
    public void client(Kind kind, String client) {
        acquire(perAddress, kind + " " + Objects.requireNonNullElse(client, "unknown"));
    }

    /** Counts a request about {@code email}, regardless of case; 429 {@code RATE_LIMITED} beyond the limit. */
    public void email(Kind kind, String email) {
        acquire(perEmail, kind + " " + EmailVerification.normalize(Objects.requireNonNull(email, "email")));
    }

    /** Counts a signed-in user's request about their own address, with the limit of an address. */
    public void user(Kind kind, String userId) {
        acquire(perEmail, kind + " user:" + Objects.requireNonNull(userId, "userId"));
    }

    /** Counts a signed-in user's attempt to change their own password; 429 {@code RATE_LIMITED} beyond the limit. */
    public void passwordChange(String userId) {
        acquire(perUser, Kind.PASSWORD_CHANGE + " user:" + Objects.requireNonNull(userId, "userId"));
    }

    /**
     * Counts a signed-in user's request to end sessions of their own, with the limit of password changes (in buckets of
     * its own); 429 {@code RATE_LIMITED} beyond the limit.
     */
    public void sessionRevoke(String userId) {
        acquire(perUser, Kind.SESSION_REVOKE + " user:" + Objects.requireNonNull(userId, "userId"));
    }

    private static void acquire(TokenBucketLimiter limiter, String key) {
        TokenBucketLimiter.Decision decision = limiter.tryAcquire(key);
        if (!decision.allowed()) {
            throw new RateLimitedException("Too many requests; try again in " + decision.retryAfterSeconds()
                + " seconds", decision.retryAfterSeconds());
        }
    }
}
