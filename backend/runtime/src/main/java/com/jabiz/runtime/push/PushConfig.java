package com.jabiz.runtime.push;

import com.jabiz.i18n.MessageCatalog;
import com.jabiz.mail.MailPlaceholders;
import com.jabiz.push.PushTemplate;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import com.jabiz.runtime.security.SignInEntries;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** The beans of push notifications and their startup check (decision D69). */
@Configuration
public class PushConfig {

    static final String CATEGORY = "PUSH";

    @Bean
    WebPushSender webPushSender(PushProperties properties, Clock clock) {
        return new WebPushSender(properties, clock);
    }

    /** Apple's push service for the iOS shell (17k-b); used only when {@code jabiz.push.apns.team-id} is set. */
    @Bean
    ApnsSender apnsSender(PushProperties properties, Clock clock) {
        return new ApnsSender(properties.apns(), clock);
    }

    /**
     * Reported at once: the settings ({@link PushProperties#problems}), a template declared twice, a template's title,
     * body or display name missing in a language of the application, a placeholder the template does not declare, an
     * entry that does not exist.
     */
    @Bean
    PlatformCheck pushChecks(PushProperties properties, PushTemplates templates, MessageCatalog messages,
        ObjectProvider<SignInEntries> entries) {
        return () -> {
            List<CheckProblem> problems = new ArrayList<>();
            properties.problems().forEach(problem -> problems.add(CheckProblem.error(CATEGORY, problem)));
            Set<String> names = new HashSet<>();
            SignInEntries known = entries.getIfAvailable();
            for (PushTemplate template : templates.all()) {
                String location = "PushTemplate " + template.name();
                if (!names.add(template.name())) {
                    problems.add(CheckProblem.error(CATEGORY, location, "is declared more than once"));
                }
                for (Locale locale : messages.supportedLocales()) {
                    for (String key : List.of(template.titleKey(), template.bodyKey(), template.nameKey())) {
                        String text = messages.find(key, locale).orElse(null);
                        if (text == null || text.isBlank()) {
                            problems.add(CheckProblem.error(CATEGORY, location, "has no message " + key + " in "
                                + locale.getLanguage()));
                            continue;
                        }
                        for (String placeholder : MailPlaceholders.of(text)) {
                            if (!template.params().contains(placeholder)) {
                                problems.add(CheckProblem.error(CATEGORY, location, key + " (" + locale.getLanguage()
                                    + ") uses {" + placeholder + "}, which the template does not declare"));
                            }
                        }
                    }
                }
                for (String entry : template.entries()) {
                    if (known != null && known.find(entry).isEmpty()) {
                        problems.add(CheckProblem.error(CATEGORY, location, "names the sign-in entry " + entry
                            + ", which does not exist"));
                    }
                }
            }
            return problems;
        };
    }
}
