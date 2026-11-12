package com.jabiz.quizbuks.setup;

import static org.assertj.core.api.Assertions.assertThat;

import com.jabiz.mail.MailLayout;
import com.jabiz.mail.MailLayoutRules;
import com.jabiz.mail.MailTextRules;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ByteArrayResource;

/**
 * F-C9: the default letterhead (jabiz.mail.layout.*) the build writes from the brand profile passes the platform's
 * rules, with the site of the brand listed in jabiz.mail.texts.allowed-link-hosts and the mail's own base address on
 * another host (staging has its own); and a brand without a site or a support address gets a footer without that link
 * rather than one the application refuses to start with. The platform's start-up check stops the application on a
 * default that fails (decision D72 item 6); this test says so before a deployment does.
 */
class MailLayoutDefaultsTest {

    private static final String STAGING = "staging.quizbuks.com";

    @Test
    void theBrandsDefaultLetterheadPassesTheRulesWithTheBrandsSiteOnTheAllowedHosts() throws IOException {
        Properties properties = load(processed());
        Set<String> allowed = hosts(properties.getProperty("jabiz.mail.texts.allowed-link-hosts", ""));
        Brand brand = Brand.current();
        assertThat(brand.siteUrl()).isNotBlank();
        assertThat(allowed).containsExactly(java.net.URI.create(brand.siteUrl()).getHost());
        for (String language : List.of("en", "ja", "zh")) {
            MailLayout layout = layout(properties, language);
            assertThat(layout.displayName()).as("display name in " + language).isNotBlank();
            assertThat(layout.logoUrl()).as("no logo yet").isEmpty();
            assertThat(layout.footer()).contains(brand.supportEmail(), brand.siteUrl()).doesNotContain("@QB_BRAND");
            assertThat(MailLayoutRules.check(layout, limits(allowed))).as("layout in " + language).isEmpty();
            // The footer is on every mail: no token, sign-in link or unsubscribe link may be in it.
            assertThat(layout.footer()).doesNotContain("{unsubscribeUrl}", "{jabiz.signIn}");
        }
    }

    @Test
    void theFooterLinksTheBrandsSiteOnlyWhenTheSiteHostIsAllowed() throws IOException {
        // Without the site on the allowed hosts the link to it is refused: the reason the host token exists.
        Properties properties = load(processed());
        assertThat(MailLayoutRules.check(layout(properties, "en"), limits(Set.of()))).isNotEmpty();
    }

    @Test
    void aBrandWithoutASiteOrASupportAddressGetsAFooterWithoutThoseLinks() throws IOException {
        String source = Files.readString(Path.of("src/main/resources/application.yml"));
        String yaml = source.replace("@QB_BRAND_FOOTER_SITE@", "").replace("@QB_BRAND_FOOTER_SUPPORT@", "")
            .replace("@QB_BRAND_SITE_HOST@", "").replace("@QB_BRAND_NAME_en@", "Sample")
            .replace("@QB_BRAND_NAME_ja@", "サンプル").replace("@QB_BRAND_NAME_zh@", "示例");
        Properties properties = load(yaml);
        Set<String> allowed = hosts(properties.getProperty("jabiz.mail.texts.allowed-link-hosts", ""));
        assertThat(allowed).isEmpty();
        for (String language : List.of("en", "ja", "zh")) {
            MailLayout layout = layout(properties, language);
            assertThat(layout.footer()).doesNotContain("](");
            assertThat(MailLayoutRules.check(layout, limits(allowed))).as("layout in " + language).isEmpty();
        }
    }

    private static MailLayoutRules.Limits limits(Set<String> allowed) {
        // The mail's own base address is another host than the brand's site.
        return new MailLayoutRules.Limits(MailLayoutRules.DEFAULT_MAX_FOOTER,
            new MailTextRules.Limits(MailTextRules.DEFAULT_MAX_SUBJECT, MailTextRules.DEFAULT_MAX_BODY, Set.of(STAGING),
                allowed));
    }

    private static MailLayout layout(Properties properties, String language) {
        String prefix = "jabiz.mail.layout." + language + ".";
        return new MailLayout(properties.getProperty(prefix + "display-name", ""),
            properties.getProperty(prefix + "logo-url", ""), properties.getProperty(prefix + "logo-alt", ""),
            properties.getProperty(prefix + "footer", ""));
    }

    private static Set<String> hosts(String list) {
        return Arrays.stream(list.split(",")).map(String::strip).filter(host -> !host.isEmpty())
            .collect(Collectors.toSet());
    }

    private static String processed() throws IOException {
        try (InputStream in = MailLayoutDefaultsTest.class.getResourceAsStream("/application.yml")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Properties load(String yaml) {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8)));
        return factory.getObject();
    }
}
