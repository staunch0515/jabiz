package com.jabiz.runtime.security;

import com.jabiz.runtime.RateLimitedException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class SignUpRateLimitsTest {

    private final AtomicLong now = new AtomicLong();
    private final SignUpRateLimits limits = new SignUpRateLimits(3, 2, Duration.ofHours(1), now::get);

    @Test
    void anAddressIsCountedRegardlessOfCaseUntilItsLimit() {
        limits.email(SignUpRateLimits.Kind.REGISTER, "Ann@Example.com");
        limits.email(SignUpRateLimits.Kind.REGISTER, "ann@example.com");

        RateLimitedException limited = catchThrowableOfType(RateLimitedException.class,
            () -> limits.email(SignUpRateLimits.Kind.REGISTER, "ANN@EXAMPLE.COM"));
        assertThat(limited.retryAfterSeconds()).isBetween(1L, 1800L);
        assertThat(limited.violations()).singleElement().satisfies(v ->
            assertThat(v.ruleCode()).isEqualTo("RATE_LIMITED"));
        // Other addresses and other kinds of request have buckets of their own.
        limits.email(SignUpRateLimits.Kind.REGISTER, "bob@example.com");
        limits.email(SignUpRateLimits.Kind.PASSWORD_RESET, "ann@example.com");
    }

    @Test
    void aClientIsCountedUntilItsLimitAndRefilledOverThePeriod() {
        for (int i = 0; i < 3; i++) {
            limits.client(SignUpRateLimits.Kind.LINK, "192.0.2.1");
        }
        assertThatThrownBy(() -> limits.client(SignUpRateLimits.Kind.LINK, "192.0.2.1"))
            .isInstanceOf(RateLimitedException.class);
        limits.client(SignUpRateLimits.Kind.LINK, "192.0.2.2");
        // A client without an address shares one bucket.
        for (int i = 0; i < 3; i++) {
            limits.client(SignUpRateLimits.Kind.LINK, null);
        }
        assertThatThrownBy(() -> limits.client(SignUpRateLimits.Kind.LINK, null))
            .isInstanceOf(RateLimitedException.class);

        now.addAndGet(Duration.ofMinutes(21).toNanos());
        limits.client(SignUpRateLimits.Kind.LINK, "192.0.2.1");
    }

    @Test
    void aSignedInUserIsCountedAsAnAddress() {
        limits.user(SignUpRateLimits.Kind.VERIFY_RESEND, "u1");
        limits.user(SignUpRateLimits.Kind.VERIFY_RESEND, "u1");
        assertThatThrownBy(() -> limits.user(SignUpRateLimits.Kind.VERIFY_RESEND, "u1"))
            .isInstanceOf(RateLimitedException.class);
    }

    @Test
    void passwordChangesAreCountedPerUser() {
        SignUpRateLimits limits = new SignUpRateLimits(20, 3, 2, Duration.ofHours(1), () -> 0L);
        limits.passwordChange("u1");
        limits.passwordChange("u1");
        assertThatThrownBy(() -> limits.passwordChange("u1")).isInstanceOf(RateLimitedException.class);
        // Another user has a bucket of their own; the address limit of a request about an address is apart.
        limits.passwordChange("u2");
        limits.user(SignUpRateLimits.Kind.VERIFY_RESEND, "u1");
        assertThatThrownBy(() -> new SignUpRateLimits(1, 1, 0, Duration.ofHours(1), () -> 0L))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void startsThroughProvidersAndLinksHaveLimitsOfTheirOwn() {
        SignUpRateLimits limits = new SignUpRateLimits(20, 3, 2, 2, Duration.ofHours(1), () -> 0L);
        limits.oidcStart("192.0.2.1");
        limits.oidcStart("192.0.2.1");
        assertThatThrownBy(() -> limits.oidcStart("192.0.2.1")).isInstanceOf(RateLimitedException.class);
        // The address's other requests count apart, and so do accounts opened by providers.
        limits.client(SignUpRateLimits.Kind.REGISTER, "192.0.2.1");
        limits.client(SignUpRateLimits.Kind.OIDC_SIGN_UP, "192.0.2.1");
        limits.oidcStart("192.0.2.2");

        limits.identityLink("u1");
        limits.identityLink("u1");
        assertThatThrownBy(() -> limits.identityLink("u1")).isInstanceOf(RateLimitedException.class);
        limits.passwordChange("u1");
        assertThatThrownBy(() -> new SignUpRateLimits(1, 1, 1, 0, Duration.ofHours(1), () -> 0L))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void limitsArePositive() {
        assertThatThrownBy(() -> new SignUpRateLimits(0, 1, Duration.ofHours(1)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SignUpRateLimits(1, 0, Duration.ofHours(1)))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
