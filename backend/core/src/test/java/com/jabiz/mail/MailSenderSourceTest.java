package com.jabiz.mail;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class MailSenderSourceTest {

    private static final MailSenderSettings SETTINGS = new MailSenderSettings("smtp.example.com", 587,
        MailSenderSettings.Tls.STARTTLS, "shop", "s1:k:iv:sealed", "shop@example.com", "The Shop");

    @Test
    void aSourceNamesTheEntityAndItsKeyAndHandsOutSettingsOfUsableRowsOnly() {
        MailSenderSource source = MailSenderSource.of("MerchantMailServer", "sponsorId",
            row -> Boolean.TRUE.equals(row.get("verified")) ? Optional.of(SETTINGS) : Optional.empty());

        assertThat(source.entity()).isEqualTo("MerchantMailServer");
        assertThat(source.keyField()).isEqualTo("sponsorId");
        assertThat(source.settings(Map.of("verified", true))).contains(SETTINGS);
        assertThat(source.settings(Map.of("verified", false))).isEmpty();
        assertThat(source).hasToString("MailSenderSource[MerchantMailServer.sponsorId]");
    }

    @Test
    void aSourceNeedsAllThreeParts() {
        assertThatNullPointerException().isThrownBy(() -> MailSenderSource.of(null, "k", row -> Optional.empty()));
        assertThatNullPointerException().isThrownBy(() -> MailSenderSource.of("E", null, row -> Optional.empty()));
        assertThatNullPointerException().isThrownBy(() -> MailSenderSource.of("E", "k", null));
    }
}
