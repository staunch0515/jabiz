package com.jabiz.runtime.i18n;

import com.jabiz.i18n.MessageCatalog;
import com.jabiz.i18n.PlatformLanguages;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The message catalog: the platform bundle first, then the application's bundles
 * ({@code jabiz.i18n.bundles}, default {@code messages}), in the application's interface languages
 * ({@code jabiz.i18n.languages}, a subset of the platform's, default all; decision D22 item 7). The build writes the
 * languages of {@code jabizApp { languages(...) }} into {@code META-INF/jabiz-app.properties}, which the admin
 * frontend is built with as well. Requests for another language get the default one; {@link I18nConfigCheck}
 * reports a configuration outside the platform's languages.
 */
@Configuration
@PropertySource(value = "classpath:META-INF/jabiz-app.properties", ignoreResourceNotFound = true)
class MessagesConfig {

    static final String ALL_LANGUAGES = "zh,ja,en";

    @Bean
    MessageCatalog messageCatalog(
        @Value("${jabiz.i18n.bundles:messages}") List<String> applicationBundles,
        @Value("${jabiz.i18n.default-locale:en}") Locale defaultLocale,
        @Value("${jabiz.i18n.languages:" + ALL_LANGUAGES + "}") List<String> languages
    ) {
        List<String> bundles = new ArrayList<>();
        bundles.add(MessageCatalog.PLATFORM_BUNDLE);
        bundles.addAll(applicationBundles);
        List<Locale> supported = supported(languages);
        // A default outside the languages is reported by I18nConfigCheck; the catalog still starts so that the check
        // can report every problem at once.
        Locale fallback = supported.contains(Locale.of(defaultLocale.getLanguage())) ? defaultLocale : supported.getFirst();
        return new MessageCatalog(bundles, supported, fallback, MessagesConfig.class.getClassLoader());
    }

    MessageCatalog messageCatalog(List<String> applicationBundles, Locale defaultLocale) {
        return messageCatalog(applicationBundles, defaultLocale, List.of(ALL_LANGUAGES.split(",")));
    }

    /** The platform languages among the configured ones, in the platform's order; all when none is one. */
    static List<Locale> supported(List<String> configured) {
        List<String> codes = configured.stream().map(String::trim).toList();
        List<Locale> chosen = PlatformLanguages.CODES.stream().filter(codes::contains).map(Locale::of).toList();
        return chosen.isEmpty() ? PlatformLanguages.LOCALES : chosen;
    }
}
