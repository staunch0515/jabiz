package com.jabiz.mail;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MailTemplateTest {

    @Test
    void declaresParametersTokensAndItsMessages() {
        MailTemplate template = MailTemplate.define("jabiz.verify-email", t -> t
            .category(MailCategory.TRANSACTIONAL).param("userName", "appName").token("verify", Duration.ofHours(24)));

        assertThat(template.params()).containsExactly("userName", "appName");
        assertThat(template.tokens()).containsEntry("verify", Duration.ofHours(24));
        assertThat(template.subjectKey()).isEqualTo("mail.jabiz.verify-email.subject");
        assertThat(template.bodyKey()).isEqualTo("mail.jabiz.verify-email.body");
        assertThat(template.unsubscribable()).isFalse();
        assertThat(template.placeholders()).containsExactly("userName", "appName", "verify", "baseUrl");
        assertThat(template.urlSafePlaceholders()).containsExactlyInAnyOrder("verify", "baseUrl", "unsubscribeUrl");
    }

    @Test
    void aNotificationOffersItsUnsubscribeLink() {
        MailTemplate template = MailTemplate.define("shop.news", t -> t.category(MailCategory.NOTIFICATION));

        assertThat(template.unsubscribable()).isTrue();
        assertThat(template.placeholders()).containsExactly("baseUrl", "unsubscribeUrl");
    }

    @Test
    void namesFollowTheRulesOfEventNames() {
        assertThatThrownBy(() -> MailTemplate.define("has space", t -> t.category(MailCategory.NOTIFICATION)))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must match");
        assertThatThrownBy(() -> MailTemplate.define(null, t -> t.category(MailCategory.NOTIFICATION)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MailTemplate.define("x".repeat(101), t -> t.category(MailCategory.NOTIFICATION)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aCategoryIsRequired() {
        assertThatThrownBy(() -> MailTemplate.define("a.b", t -> t.param("x")))
            .isInstanceOf(NullPointerException.class).hasMessageContaining("category");
    }

    @Test
    void refusesRepeatedOrMalformedParameters() {
        assertThatThrownBy(() -> MailTemplate.define("a.b", t -> t.category(MailCategory.TRANSACTIONAL)
            .param("orderNo", "orderNo")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("twice");
        assertThatThrownBy(() -> MailTemplate.define("a.b", t -> t.category(MailCategory.TRANSACTIONAL)
            .param("order_no")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must match");
        assertThatThrownBy(() -> MailTemplate.define("a.b", t -> t.category(MailCategory.TRANSACTIONAL)
            .param("baseUrl")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("placeholder of the platform");
    }

    @Test
    void refusesTokenPurposesThatClashOrLastForNothing() {
        assertThatThrownBy(() -> MailTemplate.define("a.b", t -> t.category(MailCategory.TRANSACTIONAL)
            .param("verify").token("verify", Duration.ofHours(1))))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("twice");
        assertThatThrownBy(() -> MailTemplate.define("a.b", t -> t.category(MailCategory.TRANSACTIONAL)
            .token("reset", Duration.ofHours(1)).token("reset", Duration.ofHours(2))))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("twice");
        assertThatThrownBy(() -> MailTemplate.define("a.b", t -> t.category(MailCategory.TRANSACTIONAL)
            .token("reset", Duration.ZERO)))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("positive");
        assertThatThrownBy(() -> MailTemplate.define("a.b", t -> t.category(MailCategory.TRANSACTIONAL)
            .token("Reset-Token", Duration.ofHours(1))))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must match");
        assertThatThrownBy(() -> MailTemplate.define("a.b", t -> t.category(MailCategory.TRANSACTIONAL)
            .token("unsubscribeUrl", Duration.ofHours(1))))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("placeholder of the platform");
    }

    @Test
    void recipientsAreUsersOrPlausibleAddresses() {
        assertThat(MailRecipient.user(42)).isEqualTo(new MailRecipient("42", null, null));
        assertThat(MailRecipient.user("u", "a@example.com", Locale.JAPANESE).locale()).isEqualTo(Locale.JAPANESE);
        assertThat(MailRecipient.address("a@example.com", null).userId()).isNull();
        assertThatThrownBy(() -> new MailRecipient(null, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MailRecipient.address("a@example.com\r\nBcc: b@example.com", null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MailRecipient.address("not-an-address", null))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
