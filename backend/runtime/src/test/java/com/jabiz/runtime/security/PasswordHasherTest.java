package com.jabiz.runtime.security;

import com.jabiz.entity.Violation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PasswordHasherTest {

    private final PasswordHasher hasher = new PasswordHasher(4, 10);

    @Test
    void hashesAreSaltedAndVerifiable() {
        String first = hasher.hash("correct horse");
        String second = hasher.hash("correct horse");

        assertThat(first).startsWith("$2a$04$").isNotEqualTo(second).doesNotContain("correct horse");
        assertThat(hasher.matches("correct horse", first)).isTrue();
        assertThat(hasher.matches("wrong horse!", first)).isFalse();
    }

    @Test
    void missingHashesAndOversizedPasswordsNeverMatch() {
        assertThat(hasher.matches("correct horse", null)).isFalse();
        assertThat(hasher.matches("correct horse", " ")).isFalse();
        assertThat(hasher.matches(null, hasher.hash("correct horse"))).isFalse();
        String longPassword = "x".repeat(PasswordHasher.MAX_BYTES + 1);
        assertThat(hasher.matches(longPassword, hasher.hash("x".repeat(PasswordHasher.MAX_BYTES)))).isFalse();
    }

    @Test
    void newPasswordsFollowTheLengthRules() {
        assertThat(hasher.check("password", "long enough")).isEmpty();
        assertThat(hasher.check("password", "short")).extracting(Violation::ruleCode)
            .containsExactly("PASSWORD_TOO_SHORT");
        assertThat(hasher.check("password", null)).extracting(Violation::ruleCode)
            .containsExactly("PASSWORD_TOO_SHORT");
        // Characters are counted, bytes are limited: 25 three-byte characters are 75 bytes.
        assertThat(hasher.check("password", "あ".repeat(25))).extracting(Violation::ruleCode)
            .containsExactly("PASSWORD_TOO_LONG");
        assertThatThrownBy(() -> new PasswordHasher(4, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
