package com.jabiz.runtime.process;

import com.jabiz.i18n.PlatformLanguages;
import org.springframework.boot.validation.autoconfigure.ValidationConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.beanvalidation.LocaleContextMessageInterpolator;

import java.util.Locale;

/**
 * The application's Bean Validation messages come from {@link PreloadedMessageInterpolator}, in the platform's
 * languages and the JVM's, so no message is ever read from the class path on an event loop.
 *
 * <p>This replaces the interpolator Spring Boot sets up, including its lookup of {@code {...}} parameters in the
 * Spring {@code MessageSource}: the platform's texts live in {@code MessageCatalog}, and a {@code MessageSource}
 * would read its bundles lazily as well. The locale still comes from {@code LocaleContextHolder}, as before.
 *
 * <p>The customizer is not ordered: an application's own {@code ValidationConfigurationCustomizer} that sets another
 * {@code MessageInterpolator} may run after it and replace it, which brings back lazy bundle loading on the request
 * path (a blocking read on an event loop). An application that needs its own interpolator must preload its bundles
 * the same way.
 */
@Configuration
class ValidationConfig {

    @Bean
    static ValidationConfigurationCustomizer preloadedValidationMessages() {
        PreloadedMessageInterpolator interpolator =
            new PreloadedMessageInterpolator(PlatformLanguages.LOCALES, Locale.getDefault());
        return configuration -> configuration.messageInterpolator(new LocaleContextMessageInterpolator(interpolator));
    }
}
