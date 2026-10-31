package com.jabiz.runtime.security;

import com.jabiz.i18n.MessageCatalog;
import com.jabiz.security.EntryScopeResolver;
import com.jabiz.security.SignInGuard;
import com.jabiz.security.SignInLoad;
import com.jabiz.security.SignUpAttempt;
import com.jabiz.security.SignUpDecision;
import com.jabiz.security.SignUpGuard;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Running sign-up guards fails closed and always answers with a reason the caller can read (decision D66). */
class SignUpGuardsTest {

    private static final MessageCatalog MESSAGES = new MessageCatalog(List.of(MessageCatalog.PLATFORM_BUNDLE),
        List.of(Locale.ENGLISH, Locale.CHINESE, Locale.JAPANESE), Locale.ENGLISH, SignUpGuardsTest.class.getClassLoader());

    private static SignUpGuards guards(SignUpGuard... guards) {
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        for (int i = 0; i < guards.length; i++) {
            factory.addBean("guard" + i, guards[i]);
        }
        SignInGuards reader = new SignInGuards(new StaticListableBeanFactory().getBeanProvider(SignInGuard.class),
            new StaticListableBeanFactory().getBeanProvider(EntryScopeResolver.class), null, null, null, null, null);
        return new SignUpGuards(factory.getBeanProvider(SignUpGuard.class), reader, MESSAGES);
    }

    private static SignUpDecision check(SignUpGuards guards, String userId) {
        return guards.check(SignUpAttempt.Path.SIGNED_IN_JOIN, "portal", userId, "a@example.com", true,
            Instant.EPOCH).block();
    }

    @Test
    void withoutGuardsEverythingIsAllowed() {
        SignUpGuards none = guards();
        assertThat(none.any()).isFalse();
        assertThat(check(none, "u1")).isEqualTo(SignUpDecision.ALLOW);
        assertThat(none.check()).isEmpty();
    }

    @Test
    void allMustAllowAndTheFirstRefusalDecides() {
        AtomicReference<SignUpAttempt> seen = new AtomicReference<>();
        SignUpGuards both = guards(attempt -> {
            seen.set(attempt);
            return SignUpDecision.ALLOW;
        }, attempt -> SignUpDecision.refuse(null));
        assertThat(both.any()).isTrue();
        assertThat(check(both, "u1")).isEqualTo(SignUpDecision.refuse(SignUpDecision.REFUSED));
        assertThat(seen.get().path()).isEqualTo(SignUpAttempt.Path.SIGNED_IN_JOIN);
        assertThat(seen.get().userId()).isEqualTo("u1");
        assertThat(check(guards(attempt -> SignUpDecision.ALLOW), "u1").allowed()).isTrue();
    }

    @Test
    void aGuardThatThrowsOrAnswersNothingRefuses() {
        assertThat(check(guards(attempt -> {
            throw new IllegalStateException("broken");
        }), "u1")).isEqualTo(SignUpDecision.refuse(SignUpDecision.REFUSED));
        assertThat(check(guards(attempt -> null), "u1").allowed()).isFalse();
    }

    @Test
    void aReasonWithoutATextIsAnsweredWithTheGeneralOne() {
        // The platform's bundle has no text for this reason: the caller reads the general refusal instead.
        assertThat(check(guards(attempt -> SignUpDecision.refuse("app.unknown-reason")), "u1").reason())
            .isEqualTo(SignUpDecision.REFUSED);
    }

    @Test
    void aNewAccountsLoadsFindNoRowsAndNothingIsRead() {
        AtomicReference<SignUpAttempt> seen = new AtomicReference<>();
        SignUpGuards loading = guards(new SignUpGuard() {
            @Override
            public List<SignInLoad> loads() {
                return List.of(SignInLoad.of("customers", "urn:jabiz:dataset:x:Customer", "userId"));
            }

            @Override
            public SignUpDecision check(SignUpAttempt attempt) {
                seen.set(attempt);
                return SignUpDecision.ALLOW;
            }
        });
        // No user: no read (the reader here could not read anything), the load is there and empty.
        assertThat(check(loading, null).allowed()).isTrue();
        assertThat(seen.get().data()).isEqualTo(Map.of("customers", List.of()));
        assertThat(seen.get().existingUser()).isFalse();
        // A user whose data cannot be read: refused (fail closed).
        assertThat(check(loading, "u1")).isEqualTo(SignUpDecision.refuse(SignUpDecision.REFUSED));
    }
}
