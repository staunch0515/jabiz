package com.jabiz.runtime.security;

import com.jabiz.context.RequestContext;
import com.jabiz.runtime.process.sponsor.SignInSource;
import com.jabiz.security.SignInAttempt;
import com.jabiz.security.SignInDecision;
import com.jabiz.security.SignInGuard;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Running sign-in guards fails closed (decision D36 item 6); verified addresses at the entry points; the source of a
 * login record.
 */
class SignInGuardsTest {

    private static SignInGuards guards(SignInGuard... guards) {
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        for (int i = 0; i < guards.length; i++) {
            factory.addBean("guard" + i, guards[i]);
        }
        return new SignInGuards(factory.getBeanProvider(SignInGuard.class), null, null, null);
    }

    private static SignInDecision check(SignInGuards guards) {
        return guards.check("u1", "ann", "portal", Set.of("CUSTOMER"), true, SignInAttempt.Factor.PASSWORD,
            Instant.EPOCH).block();
    }

    @Test
    void withoutGuardsEverySignInMayGoOn() {
        SignInGuards none = guards();
        assertThat(none.any()).isFalse();
        assertThat(check(none)).isEqualTo(SignInDecision.ALLOW);
        assertThat(none.check()).isEmpty();
    }

    @Test
    void allGuardsMustAllowAndTheyAreAskedInOrder() {
        AtomicInteger asked = new AtomicInteger();
        SignInGuard counting = attempt -> {
            asked.incrementAndGet();
            assertThat(attempt.entry()).isEqualTo("portal");
            assertThat(attempt.roles()).containsExactly("CUSTOMER");
            return SignInDecision.ALLOW;
        };
        assertThat(check(guards(counting, counting))).isEqualTo(SignInDecision.ALLOW);
        assertThat(asked).hasValue(2);

        assertThat(check(guards(counting, attempt -> SignInDecision.refuse("blocked"), counting)))
            .isEqualTo(SignInDecision.refuse("blocked"));
        assertThat(asked).hasValue(3);
    }

    @Test
    void aGuardThatFailsOrAnswersNothingRefuses() {
        SignInGuard failing = attempt -> {
            throw new IllegalStateException("bug");
        };
        assertThat(check(guards(failing)).allowed()).isFalse();
        assertThat(check(guards(attempt -> null)).allowed()).isFalse();
        // Loads that cannot even be listed are reported at startup.
        SignInGuard broken = new SignInGuard() {
            @Override
            public java.util.List<com.jabiz.security.SignInLoad> loads() {
                throw new IllegalStateException("no loads");
            }

            @Override
            public SignInDecision check(SignInAttempt attempt) {
                return SignInDecision.ALLOW;
            }
        };
        assertThat(guards(broken).check()).singleElement().satisfies(p -> assertThat(p.isError()).isTrue());
        // And refuse at sign-in.
        assertThat(check(guards(broken)).allowed()).isFalse();
    }

    @Test
    void verifiedAddressesAreRequiredWhereDeclared() {
        RequestContext unverified = new RequestContext("u1", null, Locale.ENGLISH, "r", Set.of(), Set.of("p"), null,
            null, "portal", false);
        RequestContext verified = new RequestContext("u1", null, Locale.ENGLISH, "r", Set.of(), Set.of("p"), null,
            null, "portal", true);

        VerifiedEmailPolicy.require(unverified, false, "Reading");
        VerifiedEmailPolicy.require(verified, true, "Ordering");
        VerifiedEmailPolicy.require(RequestContext.system(Locale.ENGLISH, "job"), true, "A job");
        assertThatThrownBy(() -> VerifiedEmailPolicy.require(unverified, true, "Ordering"))
            .isInstanceOf(EmailNotVerifiedException.class).hasMessageContaining("Ordering");
        // Contexts built before entries existed know of no verified address.
        assertThat(new RequestContext("u1", null, Locale.ENGLISH, "r", Set.of(), Set.of()).emailVerified()).isFalse();
    }

    @Test
    void userAgentsAreCleanedAndCut() {
        assertThat(new SignInSource(null, null, "Mozilla/5.0\r\nX-Injected: 1\u0000").userAgent())
            .isEqualTo("Mozilla/5.0X-Injected: 1");
        assertThat(new SignInSource(null, null, "a".repeat(300)).userAgent()).hasSize(256);
        // A surrogate pair is never split at the cut.
        String agent = "a".repeat(255) + "😀";
        assertThat(new SignInSource(null, null, agent).userAgent()).isEqualTo("a".repeat(255));
        assertThat(new SignInSource(null, null, " \t ").userAgent()).isNull();
        assertThat(new SignInSource(null, "x".repeat(60), null).clientIp()).hasSize(45);
        assertThat(SignInSource.NONE.entryOrDefault()).isEqualTo("admin");
        assertThat(SignInSource.NONE.withEntry("portal").entryOrDefault()).isEqualTo("portal");
    }
}
