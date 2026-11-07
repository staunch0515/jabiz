package com.jabiz.runtime.mail;

import com.jabiz.mail.MailTemplate;
import com.jabiz.mail.MailTextRules;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Startup warnings of category {@value MailChecks#CATEGORY} about the mail texts administrators wrote (decision D72
 * item 4): an override of a template that no longer exists, in a language the application no longer has, or that no
 * longer passes the {@link MailTextRules} (the code dropped a parameter, renamed a token). Such mail is sent with its
 * default text, so this is a warning, not an error: the texts are data, and the application must start. Texts are
 * data, so this reads them, blocking on the startup thread as the other data checks do.
 */
@Component
class MailTextChecks implements PlatformCheck {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final MailTemplates templates;
    private final MailTexts texts;

    MailTextChecks(MailTemplates templates, MailTexts texts) {
        this.templates = templates;
        this.texts = texts;
    }

    @Override
    public List<CheckProblem> check() {
        Map<String, MailTexts.Stored> stored = texts.allStored().block(TIMEOUT);
        List<CheckProblem> problems = new ArrayList<>();
        new TreeMap<>(stored == null ? Map.<String, MailTexts.Stored>of() : stored).forEach((key, text) -> {
            if (!text.custom()) {
                return;
            }
            String[] parts = key.split("\n", 2);
            String location = "mail text " + parts[0] + " [" + parts[1] + "]";
            MailTemplate template = templates.find(parts[0]).orElse(null);
            if (template == null) {
                problems.add(CheckProblem.warning(MailChecks.CATEGORY, location, "overrides a mail template that is"
                    + " not declared (any more)"));
                return;
            }
            if (!texts.language(Locale.of(parts[1]))) {
                problems.add(CheckProblem.warning(MailChecks.CATEGORY, location, "is in a language the application"
                    + " does not have (any more)"));
                return;
            }
            List<String> failing = MailTextRules.describe(texts.problems(template, text.subject(), text.body()));
            if (!failing.isEmpty()) {
                problems.add(CheckProblem.warning(MailChecks.CATEGORY, location, "version " + text.versionNo()
                    + " no longer passes the rules, so the default is sent: " + String.join("; ", failing)));
            }
        });
        return List.copyOf(problems);
    }
}
