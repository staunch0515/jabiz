package com.jabiz.runtime.process;

import jakarta.validation.MessageInterpolator;
import org.hibernate.validator.messageinterpolation.ResourceBundleMessageInterpolator;
import org.hibernate.validator.spi.messageinterpolation.LocaleResolverContext;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Hibernate Validator's interpolator with every message bundle read when it is built, for a fixed set of languages.
 *
 * <p>Messages are interpolated on event loops (a process body, an import row). Left to itself, Hibernate Validator
 * reads its bundles ({@code ValidationMessages}, {@code ContributorValidationMessages} and its own) through
 * {@code ResourceBundle.getBundle} at the first message of each language, and again whenever a GC has cleared the
 * JDK's bundle cache, which holds bundles only through soft references: a blocking read of the jar, which BlockHound
 * turns into a 500 instead of the 400. Here the bundles are preloaded and held by the interpolator, and any other
 * language is answered in the closest preloaded one, since asking a preloading interpolator for a language it has not
 * loaded is an error.
 */
public final class PreloadedMessageInterpolator implements MessageInterpolator {

    private final Set<Locale> locales;
    private final Locale defaultLocale;
    private final MessageInterpolator delegate;

    /**
     * @param languages     languages to preload, besides {@code defaultLocale}
     * @param defaultLocale language of a message whose locale is not given, and of any language not preloaded
     */
    public PreloadedMessageInterpolator(Collection<Locale> languages, Locale defaultLocale) {
        this.defaultLocale = Objects.requireNonNull(defaultLocale, "defaultLocale must not be null");
        Set<Locale> all = new LinkedHashSet<>(languages);
        all.add(defaultLocale);
        this.locales = Set.copyOf(all);
        // Built with the language set and preloading on, Hibernate Validator reads all three bundles of every
        // language here and keeps them, so interpolation never calls ResourceBundle.getBundle.
        this.delegate = new ResourceBundleMessageInterpolator(this.locales, defaultLocale,
            LocaleResolverContext::getDefaultLocale, true);
    }

    public Set<Locale> locales() {
        return locales;
    }

    @Override
    public String interpolate(String messageTemplate, Context context) {
        return delegate.interpolate(messageTemplate, context, defaultLocale);
    }

    @Override
    public String interpolate(String messageTemplate, Context context, Locale locale) {
        return delegate.interpolate(messageTemplate, context, preloaded(locale));
    }

    /** {@code requested} if preloaded, else its language if preloaded, else the default. */
    Locale preloaded(Locale requested) {
        if (requested == null) {
            return defaultLocale;
        }
        if (locales.contains(requested)) {
            return requested;
        }
        Locale language = Locale.of(requested.getLanguage());
        return locales.contains(language) ? language : defaultLocale;
    }
}
