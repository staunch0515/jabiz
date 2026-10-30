package com.jabiz.runtime.security;

import com.jabiz.context.EntryScope;
import com.jabiz.context.RequestContext;
import com.jabiz.runtime.process.sponsor.SignInSource;
import com.jabiz.security.EntryScopeResolver;
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
        return guards(java.util.List.of(), guards);
    }

    /** Guards and entry scope resolvers, with the entries {@code admin} and {@code portal}. */
    private static SignInGuards guards(java.util.List<EntryScopeResolver> resolvers, SignInGuard... guards) {
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        for (int i = 0; i < guards.length; i++) {
            factory.addBean("guard" + i, guards[i]);
        }
        for (int i = 0; i < resolvers.size(); i++) {
            factory.addBean("resolver" + i, resolvers.get(i));
        }
        SignInEntries entries = new SignInEntries(java.util.Map.of("portal", new SignInEntries.Config(
            java.util.List.of("CUSTOMER"), false, java.util.List.of(), null, "/portal/", null, null)), false, null);
        return new SignInGuards(factory.getBeanProvider(SignInGuard.class),
            factory.getBeanProvider(EntryScopeResolver.class), entries, null, null, null, null);
    }

    /** A resolver of the scope {@code store} for {@code entry}, answering {@code value}. */
    private static EntryScopeResolver resolver(String entry, java.util.function.Function<SignInAttempt, String> value) {
        return new EntryScopeResolver() {
            @Override
            public String entry() {
                return entry;
            }

            @Override
            public String scope() {
                return "store";
            }

            @Override
            public String resolve(SignInAttempt attempt) {
                return value.apply(attempt);
            }
        };
    }

    private static SignInDecision check(SignInGuards guards) {
        return guards.check("u1", "ann", "portal", Set.of("CUSTOMER"), true, SignInAttempt.Factor.PASSWORD,
            Instant.EPOCH).block();
    }

    @Test
    void anEntryScopeIsWorkedOutAfterTheGuardsAndNoneRefuses() {
        AtomicInteger asked = new AtomicInteger();
        SignInGuards scoped = guards(java.util.List.of(resolver("portal", attempt -> {
            asked.incrementAndGet();
            return "s-" + attempt.userId();
        })));
        assertThat(scoped.any()).isTrue();
        assertThat(scoped.scopeOf("portal")).contains("store");
        assertThat(scoped.scopeOf("admin")).isEmpty();
        assertThat(check(scoped)).satisfies(decision -> {
            assertThat(decision.allowed()).isTrue();
            assertThat(decision.scope()).isEqualTo(new EntryScope("store", "s-u1"));
        });
        // Another entry's sessions carry no scope, and its resolver is not asked.
        assertThat(scoped.check("u1", "ann", "admin", Set.of("ADMIN"), true, SignInAttempt.Factor.REFRESH,
            Instant.EPOCH).block()).isEqualTo(SignInDecision.ALLOW);
        assertThat(asked).hasValue(1);

        // No value, a blank one, one too long or a failure refuses (fail closed).
        assertThat(check(guards(java.util.List.of(resolver("portal", attempt -> null)))).allowed()).isFalse();
        assertThat(check(guards(java.util.List.of(resolver("portal", attempt -> " ")))).allowed()).isFalse();
        assertThat(check(guards(java.util.List.of(resolver("portal", attempt -> "x".repeat(129))))).allowed())
            .isFalse();
        assertThat(check(guards(java.util.List.of(resolver("portal", attempt -> {
            throw new IllegalStateException("bug");
        })))).allowed()).isFalse();
        // A guard's refusal comes first: the resolver is not asked.
        assertThat(check(guards(java.util.List.of(resolver("portal", attempt -> {
            asked.incrementAndGet();
            return "s";
        })), attempt -> SignInDecision.refuse("blocked")))).isEqualTo(SignInDecision.refuse("blocked"));
        assertThat(asked).hasValue(1);
    }

    @Test
    void theRolesOfAScopedEntryCountThereOnly() {
        SignInEntries entries = new SignInEntries(java.util.Map.of(
            "portal", new SignInEntries.Config(java.util.List.of("CUSTOMER"), false, java.util.List.of(), null,
                "/portal/", null, null),
            "shop", new SignInEntries.Config(java.util.List.of("CLERK"), false, java.util.List.of(), null,
                "/shop/", null, null)), false, null);
        assertThat(entries.reserveScoped(Set.of("portal"))).isEmpty();
        // The administration's "*" no longer includes the portal's role; other roles stay.
        assertThat(entries.resolve("admin").accepts("CUSTOMER")).isFalse();
        assertThat(entries.resolve("admin").accepts("ADMIN")).isTrue();
        assertThat(entries.resolve("portal").accepts("CUSTOMER")).isTrue();
        assertThat(entries.resolve("shop").accepts("CUSTOMER")).isFalse();

        // Listing the role in an entry without a scope, or a scoped entry taking every role, is reported.
        SignInEntries both = new SignInEntries(java.util.Map.of(
            "portal", new SignInEntries.Config(java.util.List.of("CUSTOMER"), false, java.util.List.of(), null,
                "/portal/", null, null),
            "shop", new SignInEntries.Config(java.util.List.of("CUSTOMER"), false, java.util.List.of(), null,
                "/shop/", null, null)), false, null);
        assertThat(both.reserveScoped(Set.of("portal"))).singleElement()
            .satisfies(problem -> assertThat(problem).startsWith("role CUSTOMER is accepted by entry shop"));
        assertThat(new SignInEntries(java.util.Map.of(), false, null).reserveScoped(Set.of("admin")))
            .singleElement().satisfies(problem -> assertThat(problem).contains("accepts every role"));
    }

    @Test
    void entryScopeResolversAreCheckedAtStartup() {
        assertThat(guards(java.util.List.of(resolver("portal", attempt -> "s"))).check()).isEmpty();
        assertThat(guards(java.util.List.of(resolver("nowhere", attempt -> "s"))).check())
            .extracting(problem -> problem.message()).containsExactly("entry nowhere does not exist");
        assertThat(guards(java.util.List.of(resolver("portal", attempt -> "s"), resolver("portal", attempt -> "t")))
            .check()).singleElement().satisfies(problem -> assertThat(problem.message())
                .startsWith("entry portal has another resolver already"));
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
