package com.jabiz.runtime.i18n;

import com.jabiz.i18n.PlatformLanguages;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Startup self-check of the interface languages (decision D22 item 7): {@code jabiz.i18n.languages} names only
 * platform languages, at least one, and contains {@code jabiz.i18n.default-locale}. All problems at once.
 */
@Component
public class I18nConfigCheck implements PlatformCheck {

    private final List<String> languages;
    private final Locale defaultLocale;

    public I18nConfigCheck(
        @Value("${jabiz.i18n.languages:" + MessagesConfig.ALL_LANGUAGES + "}") List<String> languages,
        @Value("${jabiz.i18n.default-locale:en}") Locale defaultLocale
    ) {
        this.languages = languages.stream().map(String::trim).filter(code -> !code.isEmpty()).toList();
        this.defaultLocale = defaultLocale;
    }

    @Override
    public List<CheckProblem> check() {
        List<CheckProblem> problems = new ArrayList<>();
        for (String code : languages) {
            if (!PlatformLanguages.isSupported(code)) {
                problems.add(CheckProblem.error("I18N", "jabiz.i18n.languages",
                    "'" + code + "' is not a platform language " + PlatformLanguages.CODES));
            }
        }
        if (languages.stream().noneMatch(PlatformLanguages::isSupported)) {
            problems.add(CheckProblem.error("I18N", "jabiz.i18n.languages", "names no platform language"));
        } else if (!languages.contains(defaultLocale.getLanguage())) {
            problems.add(CheckProblem.error("I18N", "jabiz.i18n.default-locale",
                "'" + defaultLocale.getLanguage() + "' is not among the languages " + languages));
        }
        return problems;
    }
}
