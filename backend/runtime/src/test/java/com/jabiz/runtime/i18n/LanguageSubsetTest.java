package com.jabiz.runtime.i18n;

import com.jabiz.i18n.MessageCatalog;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheckRunner;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** An application that uses only some of the platform languages (decision D22 item 7). */
class LanguageSubsetTest {

    private static final EntityDefinitionRegistry NO_ENTITIES =
        new EntityDefinitionRegistry(new StaticListableBeanFactory().getBeanProvider(com.jabiz.entity.EntityDefinition.class));

    private static MessageCatalog catalog(String languages, Locale defaultLocale) {
        return new MessagesConfig().messageCatalog(List.of(), defaultLocale, List.of(languages.split(",")));
    }

    @Test
    void theCatalogOffersOnlyTheApplicationsLanguagesInThePlatformOrder() {
        assertThat(catalog("en", Locale.ENGLISH).supportedLocales()).containsExactly(Locale.ENGLISH);
        assertThat(catalog("en, zh", Locale.ENGLISH).supportedLocales()).containsExactly(Locale.CHINESE, Locale.ENGLISH);
        assertThat(catalog("zh,ja,en", Locale.ENGLISH).supportedLocales())
            .containsExactly(Locale.CHINESE, Locale.JAPANESE, Locale.ENGLISH);
    }

    @Test
    void messagesAreRequiredOnlyInTheChosenLanguages() {
        MessageCatalog englishOnly = catalog("en", Locale.ENGLISH);
        assertThat(englishOnly.missing(List.of("REQUIRED", "APP_ONLY_CODE"))).containsExactly("APP_ONLY_CODE [en]");
        assertThatCode(() -> PlatformCheckRunner.verify(new MessageCatalogChecker(englishOnly, NO_ENTITIES)))
            .doesNotThrowAnyException();
    }

    @Test
    void aRequestForAnotherLanguageGetsTheDefault() {
        MessageCatalog englishOnly = catalog("en", Locale.ENGLISH);
        assertThat(englishOnly.defaultLocale()).isEqualTo(Locale.ENGLISH);
        assertThat(Locale.lookup(Locale.LanguageRange.parse("ja"), englishOnly.supportedLocales())).isNull();
    }

    @Test
    void theCheckReportsEveryProblemAtOnce() {
        assertThat(new I18nConfigCheck(List.of("en"), Locale.ENGLISH).check()).isEmpty();
        assertThat(new I18nConfigCheck(List.of("zh", "ja", "en"), Locale.ENGLISH).check()).isEmpty();

        List<CheckProblem> problems = new I18nConfigCheck(List.of("en", "fr", "de"), Locale.JAPANESE).check();
        assertThat(problems).extracting(CheckProblem::message).containsExactly(
            "'fr' is not a platform language [zh, ja, en]",
            "'de' is not a platform language [zh, ja, en]",
            "'ja' is not among the languages [en, fr, de]");
        assertThat(new I18nConfigCheck(List.of("fr"), Locale.ENGLISH).check()).extracting(CheckProblem::message)
            .containsExactly("'fr' is not a platform language [zh, ja, en]", "names no platform language");
        assertThat(new I18nConfigCheck(List.of(""), Locale.ENGLISH).check()).extracting(CheckProblem::message)
            .containsExactly("names no platform language");
    }

    @Test
    void anInvalidConfigurationStillBuildsTheCatalogSoTheCheckCanReport() {
        MessageCatalog catalog = catalog("fr", Locale.JAPANESE);
        assertThat(catalog.supportedLocales()).hasSize(3);
        assertThat(catalog("en", Locale.JAPANESE).defaultLocale()).isEqualTo(Locale.ENGLISH);
    }
}
