package com.jabiz.runtime.mail;

import com.jabiz.mail.MailCategory;
import com.jabiz.mail.MailTemplate;
import com.jabiz.runtime.security.SignInEntries;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The unsubscribe link of a template (decision D52 item 3): the page of an entry, or the application's. */
class MailTemplatesTest {

    private static final SignInEntries ENTRIES = new SignInEntries(new MockEnvironment()
        .withProperty("jabiz.security.entries.shop.accepted-roles", "CUSTOMER")
        .withProperty("jabiz.security.entries.shop.app-path", "/shop/")
        .withProperty("jabiz.security.entries.app.accepted-roles", "TAKER")
        .withProperty("jabiz.security.entries.app.app-path", "/app")
        .withProperty("jabiz.security.entries.bad.accepted-roles", "TAKER")
        .withProperty("jabiz.security.entries.bad.app-path", "/bad/"), null);

    private static MailTemplates templates(Map<String, String> paths) {
        return new MailTemplates(new StaticListableBeanFactory().getBeanProvider(MailTemplate.class), true,
            "https://example.com/", "/mail/unsubscribe", ENTRIES, paths);
    }

    private static MailTemplate news(String... entries) {
        return MailTemplate.define("t.news", t -> {
            t.category(MailCategory.NOTIFICATION);
            if (entries.length > 0) {
                t.entries(entries);
            }
        });
    }

    @Test
    void theFirstEntryWithAPageGivesTheLink() {
        Map<String, String> paths = new LinkedHashMap<>();
        paths.put("app", "/mail/unsubscribe");
        paths.put("shop", "mail/off");
        MailTemplates templates = templates(paths);
        // admin has no page: the next entry's, in the template's order.
        assertThat(templates.unsubscribeUrl("tok", news("admin", "shop", "app")))
            .isEqualTo("https://example.com/shop/mail/off?token=tok");
        assertThat(templates.unsubscribeUrl("tok", news("app", "shop")))
            .isEqualTo("https://example.com/app/mail/unsubscribe?token=tok");
    }

    @Test
    void otherwiseTheApplicationsPage() {
        MailTemplates templates = templates(Map.of("shop", "mail/off"));
        assertThat(templates.unsubscribeUrl("tok", news())).isEqualTo("https://example.com/mail/unsubscribe?token=tok");
        assertThat(templates.unsubscribeUrl("tok", news("admin"))).isEqualTo(
            "https://example.com/mail/unsubscribe?token=tok");
        assertThat(templates.unsubscribeUrl("tok", null)).isEqualTo("https://example.com/mail/unsubscribe?token=tok");
        assertThat(templates(Map.of()).unsubscribeUrl("tok", news("shop")))
            .isEqualTo("https://example.com/mail/unsubscribe?token=tok");
    }

    /** A path the startup check refuses is never used, should the application start all the same. */
    @Test
    void anInvalidPathFallsBackAtRuntime() {
        Map<String, String> paths = new LinkedHashMap<>();
        paths.put("bad", "//evil.example.com/x");
        paths.put("shop", "../admin");
        paths.put("app", "mail/unsubscribe");
        MailTemplates templates = templates(paths);
        assertThat(templates.unsubscribeUrl("tok", news("bad"))).isEqualTo(
            "https://example.com/mail/unsubscribe?token=tok");
        assertThat(templates.unsubscribeUrl("tok", news("bad", "shop", "app")))
            .isEqualTo("https://example.com/app/mail/unsubscribe?token=tok");
        assertThat(templates.unsubscribePaths()).containsEntry("bad", "//evil.example.com/x");
    }
}
