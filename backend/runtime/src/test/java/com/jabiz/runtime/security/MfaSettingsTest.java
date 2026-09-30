package com.jabiz.runtime.security;

import com.jabiz.context.RequestContext;
import com.jabiz.runtime.test.MutableClock;
import com.jabiz.security.MfaRequirement;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MfaSettingsTest {

    private static final Instant T0 = Instant.parse("2026-01-31T09:00:00Z");

    private static MfaSettings settings(boolean administration) {
        return new MfaSettings("jabiz", Duration.ofMinutes(5), Duration.ofMinutes(10), administration,
            Duration.ofMinutes(15));
    }

    private static RequestContext context(String actor, Instant mfaAt) {
        return new RequestContext(actor, null, Locale.ENGLISH, "r", Set.of(), Set.of("*"), mfaAt);
    }

    @Test
    void aSecondFactorIsRecentForTheStepUpAge() {
        MfaSettings settings = settings(true);
        assertThat(settings.recent(T0.minus(Duration.ofMinutes(10)), T0)).isTrue();
        assertThat(settings.recent(T0.minus(Duration.ofMinutes(10)).minusSeconds(1), T0)).isFalse();
        assertThat(settings.recent(null, T0)).isFalse();
        // Nothing from the future beyond a little clock skew.
        assertThat(settings.recent(T0.plusSeconds(3600), T0)).isFalse();
    }

    @Test
    void administrationAppliesOnlyWhenSwitchedOn() {
        assertThat(settings(true).applies(MfaRequirement.ADMINISTRATION)).isTrue();
        assertThat(settings(false).applies(MfaRequirement.ADMINISTRATION)).isFalse();
        assertThat(settings(false).applies(MfaRequirement.ALWAYS)).isTrue();
        assertThat(settings(true).applies(MfaRequirement.NONE)).isFalse();
    }

    @Test
    void thePolicyRefusesMissingOrOldSecondFactorsButNotTheSystem() {
        MutableClock clock = new MutableClock(T0);
        MfaPolicy policy = new MfaPolicy(settings(true), clock);
        policy.require(context("u", T0), MfaRequirement.ALWAYS, "x");
        policy.require(context("u", null), MfaRequirement.NONE, "x");
        policy.require(context(RequestContext.SYSTEM_ACTOR, null), MfaRequirement.ALWAYS, "x");
        assertThatThrownBy(() -> policy.require(context("u", null), MfaRequirement.ADMINISTRATION, "Doing x"))
            .isInstanceOf(MfaRequiredException.class).hasMessageContaining("Doing x");
        clock.advance(Duration.ofMinutes(11));
        assertThatThrownBy(() -> policy.require(context("u", T0), MfaRequirement.ALWAYS, "x"))
            .isInstanceOf(MfaRequiredException.class);
    }

    @Test
    void settingsAndSessionLengthsAreChecked() {
        assertThatThrownBy(() -> new MfaSettings(" ", Duration.ofMinutes(5), Duration.ofMinutes(10), true,
            Duration.ofMinutes(15))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MfaSettings("j", Duration.ZERO, Duration.ofMinutes(10), true,
            Duration.ofMinutes(15))).isInstanceOf(IllegalArgumentException.class);
        MutableClock clock = new MutableClock(T0);
        byte[] key = new byte[32];
        assertThat(new SessionChecks(new JwtService(key, Duration.ofMinutes(15), clock), settings(true)).check())
            .isEmpty();
        assertThat(new SessionChecks(new JwtService(key, Duration.ofMinutes(20), clock), settings(true)).check())
            .singleElement().satisfies(problem -> assertThat(problem.category()).isEqualTo("SECURITY"));
    }

    @Test
    void keysAreRequiredOutsideDevelopment() {
        assertThatThrownBy(() -> SecurityConfig.key(null, "jabiz.security.mfa.key", "JABIZ_MFA_KEY", 32, false))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("JABIZ_MFA_KEY");
        assertThat(SecurityConfig.key(null, "jabiz.security.mfa.key", "JABIZ_MFA_KEY", 32, true)).hasSize(32);
    }
}
