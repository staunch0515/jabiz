package com.jabiz.i18n;

import com.jabiz.entity.Violation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessageCatalogTest {

    private static final List<Locale> SUPPORTED = List.of(Locale.CHINESE, Locale.JAPANESE, Locale.ENGLISH);

    private final MessageCatalog catalog = new MessageCatalog(
        List.of(MessageCatalog.PLATFORM_BUNDLE, "i18ntest/messages"), SUPPORTED, Locale.ENGLISH,
        MessageCatalogTest.class.getClassLoader());

    @Test
    void sameViolationInThreeLanguages() {
        Violation required = new Violation("title", "REQUIRED", "Field 'title' is required");

        assertThat(catalog.message(required, Locale.ENGLISH)).isEqualTo("Field \"title\" is required.");
        assertThat(catalog.message(required, Locale.JAPANESE)).isEqualTo("項目「title」は必須です。");
        assertThat(catalog.message(required, Locale.SIMPLIFIED_CHINESE)).isEqualTo("字段“title”为必填项。");
    }

    @Test
    void parametersFillPlaceholders() {
        Violation v = new Violation(null, "BATCH_TOO_LARGE", "x", Map.of("size", 4, "limit", 3));
        assertThat(catalog.message(v, Locale.ENGLISH)).isEqualTo("Too many changes at once: 4 (at most 3).");
        assertThat(catalog.message(new Violation(null, "GREETING", "x", Map.of("name", "Ken")), Locale.JAPANESE))
            .isEqualTo("こんにちは、Kenさん");
    }

    @Test
    void bundlesAreSearchedInOrder() {
        assertThat(catalog.find("REQUIRED", Locale.ENGLISH)).contains("Field \"{field}\" is required.");
    }

    @Test
    void unsupportedLanguageUsesTheDefault() {
        assertThat(catalog.supported(Locale.GERMAN)).isEqualTo(Locale.ENGLISH);
        assertThat(catalog.supported(null)).isEqualTo(Locale.ENGLISH);
        assertThat(catalog.supported(Locale.JAPAN)).isEqualTo(Locale.JAPANESE);
        assertThat(catalog.find("GREETING", Locale.GERMAN)).contains("Hello {name}");
    }

    @Test
    void noFallbackToAnotherLanguageAndCodeAsLastResort() {
        assertThat(catalog.find("ONLY_EN", Locale.JAPANESE)).isEmpty();
        assertThat(catalog.message(new Violation("f", "ONLY_EN", "x"), Locale.JAPANESE)).isEqualTo("ONLY_EN");
        assertThat(catalog.message(new Violation("f", "UNKNOWN_CODE", "x"), Locale.ENGLISH)).isEqualTo("UNKNOWN_CODE");
    }

    @Test
    void missingListsEveryCodeAndLanguageGap() {
        assertThat(catalog.missing(List.of("ONLY_EN", "GREETING", "NOPE", "ONLY_EN")))
            .containsExactly("NOPE [zh]", "NOPE [ja]", "NOPE [en]", "ONLY_EN [zh]", "ONLY_EN [ja]");
    }

    @Test
    void platformBundleCoversEveryPlatformCodeInEveryLanguage() {
        MessageCatalog platform = new MessageCatalog(List.of(MessageCatalog.PLATFORM_BUNDLE), SUPPORTED,
            Locale.ENGLISH, MessageCatalogTest.class.getClassLoader());
        assertThat(platform.missing(PlatformErrorCodes.ALL)).isEmpty();
    }

    @Test
    void invalidConfigurationIsRejected() {
        ClassLoader loader = MessageCatalogTest.class.getClassLoader();
        assertThatThrownBy(() -> new MessageCatalog(List.of(), SUPPORTED, Locale.ENGLISH, loader))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MessageCatalog(List.of("x"), List.of(), Locale.ENGLISH, loader))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MessageCatalog(List.of("x"), SUPPORTED, Locale.GERMAN, loader))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not among");
    }
}
