package com.jabiz.security;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The values of sign-up guards (decision D66). */
class SignUpGuardTypesTest {

    @Test
    void attemptsCopyTheirDataAndTellExistingUsers() {
        Map<String, Object> row = new HashMap<>();
        row.put("blocked", null);
        Map<String, List<Map<String, Object>>> data = new HashMap<>();
        data.put("customers", List.of(row));
        data.put("none", null);
        SignUpAttempt join = new SignUpAttempt("portal", SignUpAttempt.Path.SIGNED_IN_JOIN, "u", "a@example.com",
            true, Instant.EPOCH, data);
        data.clear();

        assertThat(join.existingUser()).isTrue();
        assertThat(join.rows("customers")).hasSize(1);
        assertThat(join.rows("none")).isEmpty();
        assertThat(join.rows("missing")).isEmpty();
        assertThatThrownBy(() -> join.data().put("x", List.of())).isInstanceOf(UnsupportedOperationException.class);

        SignUpAttempt registration = new SignUpAttempt("portal", SignUpAttempt.Path.REGISTER, null, "a@example.com",
            false, Instant.EPOCH, null);
        assertThat(registration.existingUser()).isFalse();
        assertThat(registration.data()).isEmpty();
        assertThatThrownBy(() -> new SignUpAttempt(null, SignUpAttempt.Path.REGISTER, null, null, false,
            Instant.EPOCH, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SignUpAttempt("portal", null, null, null, false, Instant.EPOCH, null))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SignUpAttempt("portal", SignUpAttempt.Path.OIDC, null, null, false, null, null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void decisionsCarryAReasonCode() {
        assertThat(SignUpDecision.ALLOW.allowed()).isTrue();
        assertThat(SignUpDecision.ALLOW.reason()).isNull();
        assertThat(new SignUpDecision(true, "ignored").reason()).isNull();
        assertThat(SignUpDecision.refuse("commerce.customer-blocked").reason()).isEqualTo("commerce.customer-blocked");
        assertThat(SignUpDecision.refuse(null).reason()).isEqualTo(SignUpDecision.REFUSED);
        assertThat(SignUpDecision.refuse(" ").reason()).isEqualTo(SignUpDecision.REFUSED);
        assertThatThrownBy(() -> SignUpDecision.refuse("Not a code")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SignUpDecision.refuse("x".repeat(65))).isInstanceOf(IllegalArgumentException.class);
        SignUpGuard guard = attempt -> SignUpDecision.ALLOW;
        assertThat(guard.loads()).isEmpty();
        assertThat(SignUpGuard.MAX_ROWS).isEqualTo(SignInGuard.MAX_ROWS);
    }
}
