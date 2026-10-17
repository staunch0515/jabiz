package com.jabiz.runtime.security;

import com.jabiz.runtime.RateLimitedException;
import com.jabiz.runtime.observability.PlatformObservations;
import com.jabiz.runtime.web.ClientAddresses;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * The limits of the sign-in endpoints (decision D47 item 1): per address and name (20 a minute, burst 10), per
 * address (300, burst 100), per IPv6 /48 (1000, burst 300) and per instance (200 a second); each kind apart; a refusal
 * takes no token; off when disabled; bad settings are startup errors.
 */
class SignInRateLimitsTest {

    private final AtomicLong now = new AtomicLong();
    private final ClientAddresses clients = new ClientAddresses(new MockEnvironment());

    private SignInRateLimits limits(boolean enabled, SignInRateLimits.Settings settings) {
        return new SignInRateLimits(enabled, settings, clients, PlatformObservations.NOOP, now::get);
    }

    private SignInRateLimits defaults() {
        return limits(true, SignInRateLimits.Settings.defaults());
    }

    /** The defaults without the instance's ceiling (tests that send hundreds at one instant). */
    private SignInRateLimits noCeiling() {
        SignInRateLimits.Settings d = SignInRateLimits.Settings.defaults();
        return limits(true, new SignInRateLimits.Settings(d.perUserAddress(), d.userBurst(), d.perAddress(), d.burst(),
            d.perNetwork(), d.networkBurst(), 1_000_000, d.period()));
    }

    @Test
    void oneNameFromOneAddressHasABurstOfTenThenTwentyAMinute() {
        SignInRateLimits limits = defaults();
        for (int i = 0; i < 10; i++) {
            limits.count(SignInRateLimits.Kind.PASSWORD, "192.0.2.1", "ann");
        }
        RateLimitedException limited = catchThrowableOfType(RateLimitedException.class,
            () -> limits.count(SignInRateLimits.Kind.PASSWORD, "192.0.2.1", "ann"));
        assertThat(limited.retryAfterSeconds()).isEqualTo(3);
        assertThat(limited.violations()).singleElement().satisfies(v ->
            assertThat(v.ruleCode()).isEqualTo("RATE_LIMITED"));
        // Case and spaces do not make another name.
        assertThatThrownBy(() -> limits.count(SignInRateLimits.Kind.PASSWORD, "192.0.2.1", "  ANN "))
            .isInstanceOf(RateLimitedException.class);
        // Other people behind the same address, and the same name from elsewhere, go on.
        limits.count(SignInRateLimits.Kind.PASSWORD, "192.0.2.1", "bob");
        limits.count(SignInRateLimits.Kind.PASSWORD, "192.0.2.2", "ann");
        // One request every three seconds afterwards.
        now.addAndGet(TimeUnit.SECONDS.toNanos(3));
        limits.count(SignInRateLimits.Kind.PASSWORD, "192.0.2.1", "ann");
        assertThatThrownBy(() -> limits.count(SignInRateLimits.Kind.PASSWORD, "192.0.2.1", "ann"))
            .isInstanceOf(RateLimitedException.class);
    }

    @Test
    void anAddressTryingManyNamesStopsAtItsOwnLimit() {
        SignInRateLimits limits = defaults();
        for (int i = 0; i < 100; i++) {
            limits.count(SignInRateLimits.Kind.PASSWORD, "192.0.2.3", "user" + i);
        }
        assertThatThrownBy(() -> limits.count(SignInRateLimits.Kind.PASSWORD, "192.0.2.3", "fresh"))
            .isInstanceOf(RateLimitedException.class);
        // A request naming nobody counts by the address only.
        assertThatThrownBy(() -> limits.count(SignInRateLimits.Kind.PASSWORD, "192.0.2.3", " "))
            .isInstanceOf(RateLimitedException.class);
        // The second factor has buckets of its own.
        limits.count(SignInRateLimits.Kind.SECOND_FACTOR, "192.0.2.3", "user0");
    }

    @Test
    void refreshAndTheProviderCallbackCountByAddress() {
        SignInRateLimits limits = noCeiling();
        for (int i = 0; i < 100; i++) {
            limits.count(SignInRateLimits.Kind.REFRESH, "192.0.2.4", "ignored-" + i);
        }
        assertThatThrownBy(() -> limits.count(SignInRateLimits.Kind.REFRESH, "192.0.2.4", null))
            .isInstanceOf(RateLimitedException.class);
        limits.count(SignInRateLimits.Kind.OIDC_CALLBACK, "192.0.2.4", null);
        limits.count(SignInRateLimits.Kind.PASSWORD, "192.0.2.4", "ann");
        // No address at all is one key, "unknown".
        for (int i = 0; i < 100; i++) {
            limits.count(SignInRateLimits.Kind.REFRESH, (String) null, null);
        }
        assertThatThrownBy(() -> limits.count(SignInRateLimits.Kind.REFRESH, "unknown", null))
            .isInstanceOf(RateLimitedException.class);
    }

    @Test
    void ipv6CountsByItsSlash64AndItsSiteBySlash48() {
        SignInRateLimits limits = noCeiling();
        for (int i = 0; i < 100; i++) {
            limits.count(SignInRateLimits.Kind.REFRESH, "2001:db8:1:2::" + Integer.toHexString(i + 1), null);
        }
        assertThatThrownBy(() -> limits.count(SignInRateLimits.Kind.REFRESH, "2001:db8:1:2:ffff::9", null))
            .isInstanceOf(RateLimitedException.class);
        // Other /64s of the site have their own until the site's 300.
        for (int net = 3; net < 5; net++) {
            for (int i = 0; i < 100; i++) {
                limits.count(SignInRateLimits.Kind.REFRESH, "2001:db8:1:" + net + "::" + Integer.toHexString(i + 1),
                    null);
            }
        }
        assertThatThrownBy(() -> limits.count(SignInRateLimits.Kind.REFRESH, "2001:db8:1:5::1", null))
            .isInstanceOf(RateLimitedException.class);
        limits.count(SignInRateLimits.Kind.REFRESH, "2001:db8:2:5::1", null);
    }

    @Test
    void aRefusalTakesNoTokenAndTheInstanceHasACeiling() {
        SignInRateLimits limits = limits(true, new SignInRateLimits.Settings(1, 1, 1000, 1000, 1000, 1000, 3,
            Duration.ofMinutes(1)));
        limits.count(SignInRateLimits.Kind.PASSWORD, "192.0.2.5", "ann");
        // Refused by the name's bucket: the instance's token is not taken.
        assertThatThrownBy(() -> limits.count(SignInRateLimits.Kind.PASSWORD, "192.0.2.5", "ann"))
            .isInstanceOf(RateLimitedException.class);
        limits.count(SignInRateLimits.Kind.PASSWORD, "192.0.2.6", "bob");
        limits.count(SignInRateLimits.Kind.PASSWORD, "192.0.2.7", "cy");
        // Three a second on the instance, whoever asks.
        assertThatThrownBy(() -> limits.count(SignInRateLimits.Kind.PASSWORD, "192.0.2.8", "dee"))
            .isInstanceOf(RateLimitedException.class);
        now.addAndGet(TimeUnit.SECONDS.toNanos(1));
        limits.count(SignInRateLimits.Kind.PASSWORD, "192.0.2.8", "dee");
    }

    @Test
    void disabledCountsNothing() {
        SignInRateLimits limits = limits(false, SignInRateLimits.Settings.defaults());
        for (int i = 0; i < 1000; i++) {
            limits.count(SignInRateLimits.Kind.PASSWORD, "192.0.2.1", "ann");
        }
        assertThat(limits.check()).isEmpty();
    }

    @Test
    void namesAreTrimmedLowerCaseAndCut() {
        assertThat(SignInRateLimits.normalizedName(" Ann@Example.COM ")).isEqualTo("ann@example.com");
        assertThat(SignInRateLimits.normalizedName("  ")).isNull();
        assertThat(SignInRateLimits.normalizedName(null)).isNull();
        assertThat(SignInRateLimits.normalizedName("x".repeat(1000))).hasSize(SignInRateLimits.MAX_NAME_LENGTH);
    }

    @Test
    void settingsOutOfRangeAreStartupErrors() {
        assertThat(defaults().check()).isEmpty();
        SignInRateLimits wrong = limits(true, new SignInRateLimits.Settings(0, 0, -1, 0, 0, 0, 0, Duration.ZERO));
        assertThat(wrong.check()).extracting(problem -> problem.location()).containsExactly(
            "jabiz.security.sign-in.rate-limit.per-user-address", "jabiz.security.sign-in.rate-limit.user-burst",
            "jabiz.security.sign-in.rate-limit.per-address", "jabiz.security.sign-in.rate-limit.burst",
            "jabiz.security.sign-in.rate-limit.per-network", "jabiz.security.sign-in.rate-limit.network-burst",
            "jabiz.security.sign-in.rate-limit.global-per-second", "jabiz.security.sign-in.rate-limit.period");
        // It still limits meanwhile, with what it can work with.
        wrong.count(SignInRateLimits.Kind.PASSWORD, "192.0.2.1", "ann");
        assertThatThrownBy(() -> wrong.count(SignInRateLimits.Kind.PASSWORD, "192.0.2.1", "ann"))
            .isInstanceOf(RateLimitedException.class);
    }
}
