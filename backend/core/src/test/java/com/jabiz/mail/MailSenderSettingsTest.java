package com.jabiz.mail;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MailSenderSettingsTest {

    private static MailSenderSettings settings(String host, int port, MailSenderSettings.Tls tls) {
        return new MailSenderSettings(host, port, tls, "merchant", "s1:k:iv:sealed", "shop@example.com", "The Shop");
    }

    @Test
    void aPublicHostAMailPortTlsCredentialsAndASenderAddressPass() {
        assertThat(settings("smtp.example.com", 587, MailSenderSettings.Tls.STARTTLS).problems()).isEmpty();
        assertThat(settings("Mail.Example.COM ", 465, MailSenderSettings.Tls.SMTPS).problems()).isEmpty();
        assertThat(settings("smtp.example.com", 587, MailSenderSettings.Tls.STARTTLS).host())
            .isEqualTo("smtp.example.com");
    }

    @Test
    void localHostsAddressesOddPortsAndMissingPartsAreRefused() {
        assertThat(settings("localhost", 587, MailSenderSettings.Tls.STARTTLS).problems())
            .singleElement().asString().contains("public host name");
        assertThat(settings("mail.corp.internal", 587, MailSenderSettings.Tls.STARTTLS).problems()).hasSize(1);
        assertThat(settings("10.0.0.5", 587, MailSenderSettings.Tls.STARTTLS).problems()).hasSize(1);
        assertThat(settings("smtp", 587, MailSenderSettings.Tls.STARTTLS).problems()).hasSize(1);
        assertThat(settings("smtp.example.com", 8025, MailSenderSettings.Tls.STARTTLS).problems())
            .singleElement().asString().contains("port");
        assertThat(settings("smtp.example.com", 587, null).problems()).singleElement().asString().contains("tls");
        assertThat(new MailSenderSettings("smtp.example.com", 587, MailSenderSettings.Tls.SMTPS, " ", "", "shop", null)
            .problems()).containsExactly("username is required", "password is required",
            "fromAddress must be an e-mail address");
        assertThat(new MailSenderSettings("smtp.example.com", 587, MailSenderSettings.Tls.SMTPS, "u", "p",
            "shop@example.com", "x".repeat(101)).problems()).singleElement().asString().contains("fromName");
    }

    @Test
    void aLenientCheckAllowsALocalHostAndAnyPortForTests() {
        assertThat(settings("localhost", 465, MailSenderSettings.Tls.SMTPS).problems(true)).isEmpty();
        assertThat(settings("localhost", 3465, MailSenderSettings.Tls.SMTPS).problems(true)).isEmpty();
        assertThat(settings("localhost", 0, MailSenderSettings.Tls.SMTPS).problems(true)).hasSize(1);
        assertThat(settings("", 465, MailSenderSettings.Tls.SMTPS).problems(true)).hasSize(1);
    }

    @Test
    void theSealedPasswordNeverShows() {
        MailSenderSettings settings = settings("smtp.example.com", 587, MailSenderSettings.Tls.STARTTLS);
        assertThat(settings.toString()).contains("sealedPassword=***").doesNotContain("s1:k:iv:sealed");
        assertThat(settings).isEqualTo(settings("smtp.example.com", 587, MailSenderSettings.Tls.STARTTLS));
        assertThat(List.of(settings)).contains(settings);
    }
}
