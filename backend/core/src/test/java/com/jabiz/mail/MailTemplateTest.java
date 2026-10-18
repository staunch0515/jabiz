package com.jabiz.mail;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
        assertThat(template.urlSafePlaceholders()).containsExactlyInAnyOrder("verify", "baseUrl", "entryUrl",
            "unsubscribeUrl");
    }

    @Test
    void aNotificationOffersItsUnsubscribeLink() {
        MailTemplate template = MailTemplate.define("shop.news", t -> t.category(MailCategory.NOTIFICATION));

        assertThat(template.unsubscribable()).isTrue();
        assertThat(template.placeholders()).containsExactly("baseUrl", "unsubscribeUrl");
    }

    @Test
    void aTemplateIsForEveryEntryUnlessItNamesSome() {
        MailTemplate everywhere = MailTemplate.define("shop.news", t -> t.category(MailCategory.NOTIFICATION));
        MailTemplate app = MailTemplate.define("shop.offers", t -> t.category(MailCategory.NOTIFICATION)
            .entries("app", "seller"));

        assertThat(everywhere.entries()).isEmpty();
        assertThat(everywhere.appliesTo("app")).isTrue();
        assertThat(everywhere.appliesTo(null)).isTrue();
        assertThat(app.entries()).containsExactly("app", "seller");
        assertThat(app.appliesTo("seller")).isTrue();
        assertThat(app.appliesTo("admin")).isFalse();
        // No entry named is the administration's, as everywhere.
        assertThat(app.appliesTo(null)).isFalse();
        assertThat(MailTemplate.define("a.b", t -> t.category(MailCategory.NOTIFICATION).entries("admin"))
            .appliesTo(" ")).isTrue();
        assertThat(app.nameKey()).isEqualTo("mail.shop.offers.name");
        // The constructor of before phase 16s: every entry.
        assertThat(new MailTemplate("a.b", MailCategory.NOTIFICATION, List.of(), Map.of()).entries()).isEmpty();
    }

    @Test
    void refusesMalformedOrNoEntries() {
        assertThatThrownBy(() -> MailTemplate.define("a.b", t -> t.category(MailCategory.NOTIFICATION)
            .entries("App")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sign-in entry 'App'");
        assertThatThrownBy(() -> MailTemplate.define("a.b", t -> t.category(MailCategory.NOTIFICATION).entries()))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("names no sign-in entry");
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
    void tokenPurposesMayHaveANamespaceThatTheirPlaceholdersKeep() {
        MailTemplate template = MailTemplate.define("jabiz.auth.reset", t -> t.category(MailCategory.TRANSACTIONAL)
            .token("jabiz.reset", Duration.ofHours(1)));
        assertThat(template.placeholders()).contains("jabiz.reset");
        assertThat(MailPlaceholders.of("[x]({baseUrl}/r?token={jabiz.reset})")).containsExactly("baseUrl",
            "jabiz.reset");
        assertThat(MailPlaceholders.problems(template, "s", "{baseUrl}?t={jabiz.reset}")).isEmpty();
        assertThat(MailRenderer.render("s", "[x]({baseUrl}/r?token={jabiz.reset})", java.util.Map.of("baseUrl",
            "https://a.example", "jabiz.reset", "tok"), template.urlSafePlaceholders()).text())
            .contains("https://a.example/r?token=tok");
        // Parameters have no namespace, and a namespace is one segment.
        assertThatThrownBy(() -> MailTemplate.define("a.b", t -> t.category(MailCategory.TRANSACTIONAL)
            .param("jabiz.entry"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MailTemplate.define("a.b", t -> t.category(MailCategory.TRANSACTIONAL)
            .token("a.b.c", Duration.ofHours(1)))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theRootOfAnEntrysFrontendIsAPlaceholderOfThePlatform() {
        MailTemplate withEntry = MailTemplate.define("shop.welcome", t -> t.category(MailCategory.TRANSACTIONAL)
            .param("entry"));
        MailTemplate without = MailTemplate.define("shop.other", t -> t.category(MailCategory.TRANSACTIONAL));
        // {entryUrl} needs the parameter naming the entry; {entryUrl.<entry>} names it itself.
        assertThat(withEntry.placeholders()).containsExactly("entry", "baseUrl", "entryUrl");
        assertThat(without.placeholders()).doesNotContain("entryUrl");
        assertThat(MailPlaceholders.problems(withEntry, "s", "[x]({entryUrl}a?e={entry})")).isEmpty();
        assertThat(MailPlaceholders.problems(without, "s", "[x]({entryUrl}a)")).singleElement().asString()
            .contains("needs the parameter entry");
        assertThat(MailPlaceholders.problems(without, "s", "[x]({entryUrl.portal}a) [y]({entryUrl.my-app-2}b)"))
            .isEmpty();
        assertThat(MailPlaceholders.of("{entryUrl.my-app}")).containsExactly("entryUrl.my-app");
        assertThat(MailTemplate.namedEntryUrl("entryUrl.portal")).isTrue();
        assertThat(MailTemplate.namedEntryUrl("entryUrl.Portal")).isFalse();
        assertThat(MailTemplate.namedEntryUrl("entryUrl.")).isFalse();
        assertThat(MailTemplate.namedEntryUrl("entryUrl")).isFalse();
        assertThat(MailTemplate.entryOf("entryUrl.my-app")).isEqualTo("my-app");
        assertThatThrownBy(() -> MailTemplate.entryOf("baseUrl")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MailTemplate.define("a.b", t -> t.category(MailCategory.TRANSACTIONAL)
            .param("entryUrl"))).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("placeholder of the platform");
        // Made by the platform from configuration: inserted into links as they are, parameters still encoded.
        String text = MailRenderer.render("s", "[x]({entryUrl}auth/v?entry={entry}) [y]({entryUrl.portal}auth/w)",
            java.util.Map.of("entryUrl", "https://a.example/portal/", "entry", "a b&c",
                "entryUrl.portal", "https://a.example/portal/"), withEntry.urlSafePlaceholders()).text();
        assertThat(text).contains("https://a.example/portal/auth/v?entry=a%20b%26c")
            .contains("https://a.example/portal/auth/w");
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
