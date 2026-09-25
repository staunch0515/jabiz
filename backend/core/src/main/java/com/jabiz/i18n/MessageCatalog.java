package com.jabiz.i18n;

import com.jabiz.entity.Violation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.Objects;
import java.util.Optional;
import java.util.ResourceBundle;

/**
 * Localized texts of error codes (docs/design/02-metamodel.md section 3.1), read from properties bundles
 * such as {@code messages_ja.properties} (UTF-8).
 *
 * <p>Bundles are searched in the given order, so business bundles can be listed after the platform's.
 * A language only ever uses its own bundle: there is no fallback to the JVM default locale, which would
 * silently answer a Japanese client in whatever language the server happens to run in.
 */
public final class MessageCatalog {

    /** Base name of the platform's own bundle, shipped with jabiz-core. */
    public static final String PLATFORM_BUNDLE = "jabiz/messages";

    private static final ResourceBundle.Control NO_FALLBACK =
        ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES);

    private final List<String> baseNames;
    private final List<Locale> supportedLocales;
    private final Locale defaultLocale;
    private final ClassLoader classLoader;

    /**
     * @param baseNames        bundle base names, searched in order
     * @param supportedLocales languages clients may ask for; each must have a text for every code
     * @param defaultLocale    language used when the requested one is not supported; must be supported
     * @param classLoader      loader the bundles are read from
     */
    public MessageCatalog(List<String> baseNames, List<Locale> supportedLocales, Locale defaultLocale,
        ClassLoader classLoader) {
        if (baseNames == null || baseNames.isEmpty()) {
            throw new IllegalArgumentException("At least one bundle base name is required");
        }
        if (supportedLocales == null || supportedLocales.isEmpty()) {
            throw new IllegalArgumentException("At least one supported locale is required");
        }
        this.baseNames = List.copyOf(baseNames);
        this.supportedLocales = supportedLocales.stream().map(MessageCatalog::language).toList();
        this.defaultLocale = language(Objects.requireNonNull(defaultLocale, "defaultLocale must not be null"));
        if (!this.supportedLocales.contains(this.defaultLocale)) {
            throw new IllegalArgumentException(
                "Default locale " + defaultLocale + " is not among the supported locales " + supportedLocales);
        }
        this.classLoader = Objects.requireNonNull(classLoader, "classLoader must not be null");
    }

    public List<Locale> supportedLocales() {
        return supportedLocales;
    }

    public Locale defaultLocale() {
        return defaultLocale;
    }

    /** The supported language closest to {@code requested}: same language, otherwise the default. */
    public Locale supported(Locale requested) {
        if (requested != null) {
            Locale language = language(requested);
            if (supportedLocales.contains(language)) {
                return language;
            }
        }
        return defaultLocale;
    }

    /** The raw text of a code in one language, if any bundle defines it. */
    public Optional<String> find(String code, Locale locale) {
        Locale language = supported(locale);
        for (String baseName : baseNames) {
            ResourceBundle bundle = bundle(baseName, language);
            if (bundle != null && bundle.containsKey(code)) {
                return Optional.of(bundle.getString(code));
            }
        }
        return Optional.empty();
    }

    /**
     * The localized message of a violation. Placeholders are filled from its parameters, plus
     * {@code {field}}; a code without text yields the code itself.
     */
    public String message(Violation violation, Locale locale) {
        Optional<String> template = find(violation.ruleCode(), locale);
        if (template.isEmpty()) {
            return violation.ruleCode();
        }
        Map<String, Object> params = new HashMap<>(violation.params());
        if (violation.field() != null) {
            params.putIfAbsent("field", violation.field());
        }
        return MessageTemplate.format(template.get(), params);
    }

    /** Every {@code code [locale]} pair among the given codes that has no text, in a stable order. */
    public List<String> missing(Collection<String> codes) {
        List<String> missing = new ArrayList<>();
        for (String code : codes.stream().distinct().sorted().toList()) {
            for (Locale locale : supportedLocales) {
                if (find(code, locale).isEmpty()) {
                    missing.add(code + " [" + locale + "]");
                }
            }
        }
        return missing;
    }

    private ResourceBundle bundle(String baseName, Locale language) {
        try {
            return ResourceBundle.getBundle(baseName, language, classLoader, NO_FALLBACK);
        } catch (MissingResourceException e) {
            return null;
        }
    }

    private static Locale language(Locale locale) {
        return Locale.of(locale.getLanguage());
    }
}
