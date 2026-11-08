package com.jabiz.security;

import com.jabiz.param.ParamValues;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The business parameters guards declare and receive (decision D79). */
class GuardParamTest {

    private static final Instant TIME = Instant.parse("2026-11-08T09:00:00Z");

    @Test
    void requiredAndOptional() {
        assertThat(GuardParam.required("shop.closed")).isEqualTo(new GuardParam("shop.closed", true));
        assertThat(GuardParam.optional("shop.closed").required()).isFalse();
        assertThatThrownBy(() -> GuardParam.required(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void guardsDeclareNoParametersByDefault() {
        SignInGuard signIn = attempt -> SignInDecision.ALLOW;
        SignUpGuard signUp = attempt -> SignUpDecision.ALLOW;
        assertThat(signIn.params()).isEmpty();
        assertThat(signUp.params()).isEmpty();
    }

    @Test
    void attemptsCarryTheParametersAndTheOldConstructorsNone() {
        ParamValues values = new ParamValues(TIME, Map.of("shop.closed", true));
        SignInAttempt signIn = new SignInAttempt("u1", "ann", "portal", Set.of(), true, SignInAttempt.Factor.PASSWORD,
            TIME, Map.of(), values);
        assertThat(signIn.params().get("shop.closed", Boolean.class)).isTrue();
        SignInAttempt before = new SignInAttempt("u1", "ann", "portal", Set.of(), true, SignInAttempt.Factor.PASSWORD,
            TIME, Map.of());
        assertThat(before.params().values()).isEmpty();
        assertThat(before.params().asOf()).isEqualTo(TIME);
        assertThat(before.params().find("shop.closed", Boolean.class)).isEmpty();

        SignUpAttempt signUp = new SignUpAttempt("portal", SignUpAttempt.Path.REGISTER, null, "a@example.com", false,
            TIME, Map.of("rows", List.of()), values);
        assertThat(signUp.params().has("shop.closed")).isTrue();
        SignUpAttempt old = new SignUpAttempt("portal", SignUpAttempt.Path.REGISTER, null, null, false, TIME, null);
        assertThat(old.params().values()).isEmpty();
        assertThat(old.params().asOf()).isEqualTo(TIME);
    }
}
