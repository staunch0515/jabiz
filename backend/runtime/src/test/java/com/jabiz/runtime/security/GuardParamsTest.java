package com.jabiz.runtime.security;

import com.jabiz.i18n.MessageCatalog;
import com.jabiz.param.ParamValues;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.param.ParamService;
import com.jabiz.security.EntryScopeResolver;
import com.jabiz.security.GuardParam;
import com.jabiz.security.SignInAttempt;
import com.jabiz.security.SignInDecision;
import com.jabiz.security.SignInGuard;
import com.jabiz.security.SignUpAttempt;
import com.jabiz.security.SignUpDecision;
import com.jabiz.security.SignUpGuard;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The business parameters of sign-in and sign-up guards (decision D79): read once for all guards at the attempt's
 * time, required keys without a value refuse before any guard is asked, optional ones reach the guard absent, and the
 * startup check reports malformed keys.
 */
class GuardParamsTest {

    private static final Instant TIME = Instant.parse("2026-11-08T09:00:00Z");
    private static final MessageCatalog MESSAGES = new MessageCatalog(List.of(MessageCatalog.PLATFORM_BUNDLE),
        List.of(Locale.ENGLISH, Locale.CHINESE, Locale.JAPANESE), Locale.ENGLISH,
        GuardParamsTest.class.getClassLoader());

    /** Parameters in effect: {@code values}; records what was asked for. */
    private static final class FakeParams extends ParamService {
        final Map<String, Object> values;
        final List<Collection<String>> asked = new ArrayList<>();
        final List<Instant> times = new ArrayList<>();
        RuntimeException failure;

        FakeParams(Map<String, Object> values) {
            super(null, null);
            this.values = values;
        }

        @Override
        public Mono<ParamValues> loadAvailable(Collection<String> keys, Instant asOf) {
            asked.add(List.copyOf(keys));
            times.add(asOf);
            if (failure != null) {
                return Mono.error(failure);
            }
            Map<String, Object> found = new LinkedHashMap<>();
            keys.stream().filter(values::containsKey).forEach(key -> found.put(key, values.get(key)));
            return Mono.just(new ParamValues(asOf, found));
        }
    }

    private static SignInGuards signIn(FakeParams params, SignInGuard... guards) {
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        for (int i = 0; i < guards.length; i++) {
            factory.addBean("guard" + i, guards[i]);
        }
        return new SignInGuards(factory.getBeanProvider(SignInGuard.class),
            factory.getBeanProvider(EntryScopeResolver.class), null, null, null, null, null, new GuardParams(params));
    }

    private static SignUpGuards signUp(FakeParams params, SignUpGuard... guards) {
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        for (int i = 0; i < guards.length; i++) {
            factory.addBean("guard" + i, guards[i]);
        }
        return new SignUpGuards(factory.getBeanProvider(SignUpGuard.class), signIn(params), MESSAGES,
            new GuardParams(params));
    }

    private static SignInGuard signInGuard(List<GuardParam> params, AtomicReference<SignInAttempt> seen,
        java.util.function.Function<SignInAttempt, SignInDecision> decide) {
        return new SignInGuard() {
            @Override
            public List<GuardParam> params() {
                return params;
            }

            @Override
            public SignInDecision check(SignInAttempt attempt) {
                seen.set(attempt);
                return decide.apply(attempt);
            }
        };
    }

    private static SignUpGuard signUpGuard(List<GuardParam> params, AtomicReference<SignUpAttempt> seen,
        java.util.function.Function<SignUpAttempt, SignUpDecision> decide) {
        return new SignUpGuard() {
            @Override
            public List<GuardParam> params() {
                return params;
            }

            @Override
            public SignUpDecision check(SignUpAttempt attempt) {
                seen.set(attempt);
                return decide.apply(attempt);
            }
        };
    }

    private static SignInDecision signInCheck(SignInGuards guards) {
        return guards.check("u1", "ann", "portal", Set.of("CUSTOMER"), true, SignInAttempt.Factor.REFRESH, TIME)
            .block();
    }

    private static SignUpDecision registration(SignUpGuards guards) {
        return guards.check(SignUpAttempt.Path.REGISTER, "portal", null, "a@example.com", false, TIME).block();
    }

    @Test
    void aKeyAnyGuardRequiresIsRequired() {
        assertThat(GuardParams.merge(List.of(
            List.of(GuardParam.optional("shop.closed"), GuardParam.optional("shop.note")),
            List.of(GuardParam.required("shop.closed"), GuardParam.required("shop.limit")))))
            .containsExactly(Map.entry("shop.closed", true), Map.entry("shop.note", false),
                Map.entry("shop.limit", true));
        assertThat(GuardParams.merge(List.of(List.of(), List.of()))).isEmpty();
    }

    @Test
    void signInGuardsGetTheParametersInEffectAtTheAttemptsTime() {
        FakeParams params = new FakeParams(Map.of("shop.closed", true));
        AtomicReference<SignInAttempt> first = new AtomicReference<>();
        AtomicReference<SignInAttempt> second = new AtomicReference<>();
        SignInGuards guards = signIn(params,
            signInGuard(List.of(GuardParam.required("shop.closed")), first, attempt -> SignInDecision.ALLOW),
            signInGuard(List.of(GuardParam.optional("shop.closed"), GuardParam.optional("shop.note")), second,
                attempt -> attempt.params().find("shop.note", String.class).isPresent()
                    ? SignInDecision.refuse("noted") : SignInDecision.ALLOW));

        assertThat(signInCheck(guards)).isEqualTo(SignInDecision.ALLOW);
        // One read for both guards, at the attempt's time; the unset optional key is absent.
        assertThat(params.asked).containsExactly(List.of("shop.closed", "shop.note"));
        assertThat(params.times).containsExactly(TIME);
        assertThat(first.get().params().get("shop.closed", Boolean.class)).isTrue();
        assertThat(second.get().params().has("shop.note")).isFalse();
        assertThat(second.get().params().asOf()).isEqualTo(TIME);
    }

    @Test
    void aRequiredParameterWithoutAValueOrAFailedReadRefusesWithoutAskingTheGuards() {
        AtomicReference<SignInAttempt> seen = new AtomicReference<>();
        SignInGuard guard = signInGuard(List.of(GuardParam.required("shop.closed")), seen,
            attempt -> SignInDecision.ALLOW);
        assertThat(signInCheck(signIn(new FakeParams(Map.of()), guard)))
            .isEqualTo(SignInDecision.refuse(SignInGuards.UNDECIDED));
        FakeParams failing = new FakeParams(Map.of("shop.closed", false));
        failing.failure = new IllegalStateException("database down");
        assertThat(signInCheck(signIn(failing, guard))).isEqualTo(SignInDecision.refuse(SignInGuards.UNDECIDED));
        assertThat(seen.get()).isNull();
    }

    @Test
    void guardsWithoutParametersReadNothing() {
        FakeParams params = new FakeParams(Map.of());
        AtomicReference<SignInAttempt> seen = new AtomicReference<>();
        assertThat(signInCheck(signIn(params, signInGuard(List.of(), seen, attempt -> SignInDecision.ALLOW))))
            .isEqualTo(SignInDecision.ALLOW);
        assertThat(params.asked).isEmpty();
        assertThat(seen.get().params().values()).isEmpty();
    }

    @Test
    void signUpGuardsReadTheParametersAlsoWithoutAUser() {
        FakeParams params = new FakeParams(Map.of("portal.sign-up.closed", true));
        AtomicReference<SignUpAttempt> seen = new AtomicReference<>();
        SignUpGuard closed = signUpGuard(List.of(GuardParam.optional("portal.sign-up.closed")), seen,
            attempt -> attempt.params().find("portal.sign-up.closed", Boolean.class).orElse(false)
                ? SignUpDecision.refuse(SignUpDecision.REFUSED) : SignUpDecision.ALLOW);
        assertThat(registration(signUp(params, closed))).isEqualTo(SignUpDecision.refuse(SignUpDecision.REFUSED));
        assertThat(seen.get().params().asOf()).isEqualTo(TIME);
        assertThat(params.asked).containsExactly(List.of("portal.sign-up.closed"));
        // Unset: the guard decides what that means (here: open).
        assertThat(registration(signUp(new FakeParams(Map.of()), closed))).isEqualTo(SignUpDecision.ALLOW);

        // A required key without a value refuses with the general reason; the guard is not asked.
        AtomicReference<SignUpAttempt> required = new AtomicReference<>();
        assertThat(registration(signUp(new FakeParams(Map.of()), signUpGuard(
            List.of(GuardParam.required("portal.sign-up.closed")), required, attempt -> SignUpDecision.ALLOW))))
            .isEqualTo(SignUpDecision.refuse(SignUpDecision.REFUSED));
        assertThat(required.get()).isNull();
    }

    @Test
    void theStartupCheckReportsMalformedKeysAndMixedDeclarations() {
        Map<String, GuardParam> seen = new LinkedHashMap<>();
        List<CheckProblem> problems = new ArrayList<>(GuardParams.problems("SignInGuard A", List.of(
            GuardParam.required("Shop Closed"), GuardParam.required(""), GuardParam.required("shop.closed")), seen));
        problems.addAll(GuardParams.problems("SignInGuard B", List.of(GuardParam.optional("shop.closed"),
            GuardParam.optional("shop.note")), seen));
        assertThat(problems).extracting(CheckProblem::toString).hasSize(3);
        assertThat(problems.get(0).toString()).contains("SignInGuard A", "'Shop Closed' is not a parameter key");
        assertThat(problems.get(1).toString()).contains("'' is not a parameter key");
        assertThat(problems.get(2).toString()).contains("SignInGuard B", "shop.closed", "read as required");

        // Through the guards' own checks, and a params() that throws.
        AtomicReference<SignInAttempt> unused = new AtomicReference<>();
        SignInGuard broken = new SignInGuard() {
            @Override
            public List<GuardParam> params() {
                throw new IllegalStateException("bug");
            }

            @Override
            public SignInDecision check(SignInAttempt attempt) {
                return SignInDecision.ALLOW;
            }
        };
        assertThat(signIn(new FakeParams(Map.of()), broken,
            signInGuard(List.of(GuardParam.optional("BAD")), unused, attempt -> SignInDecision.ALLOW)).check())
            .extracting(CheckProblem::toString)
            .anySatisfy(text -> assertThat(text).contains("params() failed: bug"))
            .anySatisfy(text -> assertThat(text).contains("'BAD' is not a parameter key"));
        assertThat(signUp(new FakeParams(Map.of()), signUpGuard(List.of(GuardParam.required("Bad")),
            new AtomicReference<>(), attempt -> SignUpDecision.ALLOW)).check())
            .extracting(CheckProblem::toString)
            .anySatisfy(text -> assertThat(text).contains("SignUpGuard", "'Bad' is not a parameter key"));
    }
}
