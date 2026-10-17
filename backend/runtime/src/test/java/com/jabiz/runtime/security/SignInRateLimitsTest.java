package com.jabiz.runtime.security;

import com.jabiz.runtime.RateLimitedException;
import com.jabiz.runtime.observability.PlatformObservations;
import com.jabiz.runtime.web.ClientAddresses;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * The per-address limit of the sign-in endpoints (decision D47 item 1): a burst, then a steady rate; each kind of
 * request apart; IPv6 by /64; off when disabled; bad settings are startup errors.
 */
class SignInRateLimitsTest {

    private final AtomicLong now = new AtomicLong();
    private final ClientAddresses clients = new ClientAddresses(new MockEnvironment());

    private SignInRateLimits limits(boolean enabled) {
        return new SignInRateLimits(enabled, 20, 10, Duration.ofMinutes(1), clients, PlatformObservations.NOOP,
            now::get);
    }

    @Test
    void aBurstOfTenThenTwentyAMinute() {
        SignInRateLimits limits = limits(true);
        for (int i = 0; i < 10; i++) {
            limits.count(SignInRateLimits.Kind.PASSWORD, "192.0.2.1");
        }
        RateLimitedException limited = catchThrowableOfType(RateLimitedException.class,
            () -> limits.count(SignInRateLimits.Kind.PASSWORD, "192.0.2.1"));
        assertThat(limited.retryAfterSeconds()).isEqualTo(3);
        assertThat(limited.violations()).singleElement().satisfies(v ->
            assertThat(v.ruleCode()).isEqualTo("RATE_LIMITED"));
        // One request every three seconds afterwards.
        now.addAndGet(TimeUnit.SECONDS.toNanos(3));
        limits.count(SignInRateLimits.Kind.PASSWORD, "192.0.2.1");
        assertThatThrownBy(() -> limits.count(SignInRateLimits.Kind.PASSWORD, "192.0.2.1"))
            .isInstanceOf(RateLimitedException.class);
        // A minute's rest refills the burst, never more.
        now.addAndGet(TimeUnit.MINUTES.toNanos(10));
        for (int i = 0; i < 10; i++) {
            limits.count(SignInRateLimits.Kind.PASSWORD, "192.0.2.1");
        }
        assertThatThrownBy(() -> limits.count(SignInRateLimits.Kind.PASSWORD, "192.0.2.1"))
            .isInstanceOf(RateLimitedException.class);
    }

    @Test
    void addressesAndKindsHaveBucketsOfTheirOwnAndIpv6CountsByNetwork() {
        SignInRateLimits limits = limits(true);
        for (int i = 0; i < 10; i++) {
            limits.count(SignInRateLimits.Kind.SECOND_FACTOR, "2001:db8:1:2::" + Integer.toHexString(i + 1));
        }
        assertThatThrownBy(() -> limits.count(SignInRateLimits.Kind.SECOND_FACTOR, "2001:db8:1:2:ffff::9"))
            .isInstanceOf(RateLimitedException.class);
        limits.count(SignInRateLimits.Kind.SECOND_FACTOR, "2001:db8:1:3::1");
        for (SignInRateLimits.Kind kind : List.of(SignInRateLimits.Kind.PASSWORD, SignInRateLimits.Kind.REFRESH,
            SignInRateLimits.Kind.OIDC_CALLBACK)) {
            limits.count(kind, "2001:db8:1:2::1");
        }
        // No address at all is one key, "unknown".
        for (int i = 0; i < 10; i++) {
            limits.count(SignInRateLimits.Kind.REFRESH, (String) null);
        }
        assertThatThrownBy(() -> limits.count(SignInRateLimits.Kind.REFRESH, "unknown"))
            .isInstanceOf(RateLimitedException.class);
    }

    @Test
    void disabledCountsNothing() {
        SignInRateLimits limits = limits(false);
        for (int i = 0; i < 100; i++) {
            limits.count(SignInRateLimits.Kind.PASSWORD, "192.0.2.1");
        }
        assertThat(limits.check()).isEmpty();
    }

    @Test
    void settingsOutOfRangeAreStartupErrors() {
        assertThat(limits(true).check()).isEmpty();
        SignInRateLimits wrong = new SignInRateLimits(true, 0, -1, Duration.ZERO, clients, null, now::get);
        assertThat(wrong.check()).extracting(problem -> problem.location()).containsExactly(
            "jabiz.security.sign-in.rate-limit.per-address", "jabiz.security.sign-in.rate-limit.burst",
            "jabiz.security.sign-in.rate-limit.period");
        // It still limits meanwhile, with what it can work with.
        wrong.count(SignInRateLimits.Kind.PASSWORD, "192.0.2.1");
        assertThatThrownBy(() -> wrong.count(SignInRateLimits.Kind.PASSWORD, "192.0.2.1"))
            .isInstanceOf(RateLimitedException.class);
    }
}
