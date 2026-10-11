package com.jabiz.runtime.security;

import com.jabiz.mail.MailTemplate;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SignUpProcessesTest {

    @Test
    void aRegisteredUserIsNamedAfterTheAddressInLowerCase() {
        assertThat(SignUpProcesses.baseName(" Ann@Example.COM ")).isEqualTo("ann@example.com");
        String longer = "a".repeat(120) + "@example.com";
        assertThat(SignUpProcesses.baseName(longer)).hasSize(SignUpProcesses.MAX_USER_NAME).startsWith("aaa");
    }

    @Test
    void aTakenNameGetsADashAndSixLettersOrDigits() {
        byte[] random = {0, 25, 26, 35, (byte) 255, 100};
        assertThat(SignUpProcesses.suffixed("ann@example.com", random)).isEqualTo("ann@example.com-az09d2");
        String cut = SignUpProcesses.suffixed("b".repeat(100), random);
        assertThat(cut).hasSize(SignUpProcesses.MAX_USER_NAME).endsWith("-az09d2");
    }

    @Test
    void theMailOfRegistrationVerificationAndResetIsTransactionalWithTokensOfTheirOwn() {
        AuthMailTemplates.Templates templates = new AuthMailTemplates().authMailTemplateSet(Duration.ofHours(24),
            Duration.ofHours(24), Duration.ofHours(1));
        assertThat(List.of(templates.verifyEmail(), templates.accountExists(), templates.passwordReset()))
            .allSatisfy(template -> {
                assertThat(template.unsubscribable()).isFalse();
                assertThat(template.params()).containsExactly(AuthMailTemplates.ENTRY);
            })
            .extracting(MailTemplate::name)
            .containsExactly(AuthMailTemplates.VERIFY_EMAIL, AuthMailTemplates.ACCOUNT_EXISTS,
                AuthMailTemplates.PASSWORD_RESET);
        assertThat(templates.verifyEmail().tokens()).containsExactly(
            java.util.Map.entry(AuthMailTemplates.VERIFY, Duration.ofHours(24)));
        assertThat(templates.accountExists().tokens()).containsOnlyKeys(AuthMailTemplates.JOIN);
        assertThat(templates.passwordReset().tokens()).containsExactly(
            java.util.Map.entry(AuthMailTemplates.RESET, Duration.ofHours(1)));
    }
}
