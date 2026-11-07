package com.jabiz.runtime.mail;

import com.jabiz.i18n.MessageCatalog;
import com.jabiz.mail.MailLayout;
import com.jabiz.mail.MailLayoutRules;
import com.jabiz.mail.MailPlaceholders;
import com.jabiz.mail.MailTemplate;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import com.jabiz.runtime.security.SignInEntries;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Startup checks of category {@value MailChecks#CATEGORY} for the mail layout (decision D72; 17zh-b):
 * <ul>
 *   <li>an error for a default layout ({@code jabiz.mail.layout.<language>.*}) that fails the {@link MailLayoutRules}
 *       or whose footer names an entry ({@code {entryUrl.<entry>}}) that is not configured: configuration, so the
 *       application must not start with it;</li>
 *   <li>an error for a default layout of a language the application does not have (a misspelt key);</li>
 *   <li>a warning for a stored layout that no longer passes the rules (the allowed hosts were narrowed since): mail
 *       is sent on the default, so the application starts. Stored layouts are data, read blocking on the startup
 *       thread as the other data checks do.</li>
 * </ul>
 */
@Component
class MailLayoutChecks implements PlatformCheck {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final MailLayouts layouts;
    private final MessageCatalog messages;
    private final SignInEntries entries;
    private final org.springframework.core.env.Environment environment;

    MailLayoutChecks(MailLayouts layouts, MessageCatalog messages, SignInEntries entries,
        org.springframework.core.env.Environment environment) {
        this.layouts = layouts;
        this.messages = messages;
        this.entries = entries;
        this.environment = environment;
    }

    @Override
    public List<CheckProblem> check() {
        List<CheckProblem> problems = new ArrayList<>();
        for (Locale locale : messages.supportedLocales()) {
            MailLayout defaults = layouts.defaults(locale);
            String location = MailLayouts.PREFIX + locale.getLanguage();
            MailLayoutRules.describe(layouts.problems(defaults)).forEach(problem -> problems.add(
                CheckProblem.error(MailChecks.CATEGORY, location, "the default mail layout " + problem)));
            MailPlaceholders.of(defaults.footer()).stream().filter(MailTemplate::namedEntryUrl)
                .map(MailTemplate::entryOf).filter(entry -> entries.find(entry)
                    .filter(found -> found.name().equals(entry)).isEmpty())
                .forEach(entry -> problems.add(CheckProblem.error(MailChecks.CATEGORY, location + ".footer",
                    "names the sign-in entry " + entry + ", which is not configured (jabiz.security.entries)")));
        }
        for (String language : configuredLanguages()) {
            if (messages.supportedLocales().stream().noneMatch(locale -> locale.getLanguage().equals(language))) {
                problems.add(CheckProblem.error(MailChecks.CATEGORY, MailLayouts.PREFIX + language,
                    "is a mail layout for a language the application does not have"));
            }
        }
        Map<String, MailLayouts.Stored> stored = layouts.allStored().block(TIMEOUT);
        new TreeMap<>(stored == null ? Map.<String, MailLayouts.Stored>of() : stored).forEach((language, layout) -> {
            if (!layout.custom()) {
                return;
            }
            String location = "mail layout [" + language + "]";
            if (!layouts.language(Locale.of(language))) {
                problems.add(CheckProblem.warning(MailChecks.CATEGORY, location, "is in a language the application"
                    + " does not have (any more)"));
                return;
            }
            List<String> failing = MailLayoutRules.describe(layouts.problems(layout.layout()));
            if (!failing.isEmpty()) {
                problems.add(CheckProblem.warning(MailChecks.CATEGORY, location, "version " + layout.versionNo()
                    + " no longer passes the rules, so mail is sent on the default: " + String.join("; ", failing)));
            }
        });
        return List.copyOf(problems);
    }

    /** The languages {@code jabiz.mail.layout.<language>.*} is configured for. */
    private List<String> configuredLanguages() {
        List<String> found = new ArrayList<>();
        if (environment instanceof org.springframework.core.env.ConfigurableEnvironment configurable) {
            configurable.getPropertySources().forEach(source -> {
                if (source instanceof org.springframework.core.env.EnumerablePropertySource<?> enumerable) {
                    for (String name : enumerable.getPropertyNames()) {
                        if (name.startsWith(MailLayouts.PREFIX)) {
                            String rest = name.substring(MailLayouts.PREFIX.length());
                            int dot = rest.indexOf('.');
                            String language = dot < 0 ? rest : rest.substring(0, dot);
                            if (dot > 0 && !found.contains(language)) {
                                found.add(language);
                            }
                        }
                    }
                }
            });
        }
        return found;
    }
}
