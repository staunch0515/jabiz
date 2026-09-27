package com.jabiz.runtime.i18n;

import com.jabiz.i18n.MessageCatalog;
import com.jabiz.i18n.PlatformLanguages;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The message catalog: the platform bundle first, then the application's bundles
 * ({@code jabiz.i18n.bundles}, default {@code messages}), in the languages the platform supports.
 */
@Configuration
class MessagesConfig {

    static final List<Locale> SUPPORTED = PlatformLanguages.LOCALES;

    @Bean
    MessageCatalog messageCatalog(
        @Value("${jabiz.i18n.bundles:messages}") List<String> applicationBundles,
        @Value("${jabiz.i18n.default-locale:en}") Locale defaultLocale
    ) {
        List<String> bundles = new ArrayList<>();
        bundles.add(MessageCatalog.PLATFORM_BUNDLE);
        bundles.addAll(applicationBundles);
        return new MessageCatalog(bundles, SUPPORTED, defaultLocale, MessagesConfig.class.getClassLoader());
    }
}
