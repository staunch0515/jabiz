package com.jabiz.runtime.mail;

import com.jabiz.i18n.MessageCatalog;
import com.jabiz.mail.MailPlaceholders;
import com.jabiz.mail.MailTemplate;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import com.jabiz.runtime.security.SensitiveDataMasker;
import com.jabiz.runtime.security.SignInEntries;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Startup self-check of e-mail (docs/design/18-numbering-approvals-tasks.md sections 5.4 and 5.6), category
 * {@value #CATEGORY}:
 * <ul>
 *   <li>with {@code jabiz.mail.enabled=true} a mail server ({@code spring.mail.host}) and a sender address
 *       ({@code jabiz.mail.from}) must be configured, and an address of the application ({@code jabiz.mail.base-url})
 *       when a template has links to it (every notification has its unsubscribe link), rather than mail failing one
 *       by one;</li>
 *   <li>every mail template has a unique name, a subject and a body in every language of the application, and uses
 *       exactly the placeholders it declares ({@link MailPlaceholders});</li>
 *   <li>no parameter is named like a secret or a masked field: parameters are stored and sent as they are, and a mail
 *       carries no secret but its one-time tokens;</li>
 *   <li>every entry a text names in {@code {entryUrl.<entry>}} is a usable sign-in entry (decision D43 item 4).</li>
 * </ul>
 */
@Component
public class MailChecks implements PlatformCheck {

    public static final String CATEGORY = "MAIL";

    private final boolean enabled;
    private final String from;
    private final String baseUrl;
    private final ObjectProvider<JavaMailSender> mail;
    private final List<MailTemplate> templates;
    private final MessageCatalog messages;
    private final Predicate<String> hidden;
    private final Predicate<String> entryExists;

    @Autowired
    public MailChecks(@Value("${jabiz.mail.enabled:false}") boolean enabled, @Value("${jabiz.mail.from:}") String from,
        @Value("${jabiz.mail.base-url:}") String baseUrl, ObjectProvider<JavaMailSender> mail,
        ObjectProvider<MailTemplate> templates, MessageCatalog messages, SensitiveDataMasker masker,
        SignInEntries entries) {
        this(enabled, from, baseUrl, mail, templates, messages, masker::hidesByName,
            name -> entries.find(name).filter(entry -> entry.name().equals(name)).isPresent());
    }

    /** @param hidden whether values under a name are hidden (secrets, masked fields) */
    MailChecks(boolean enabled, String from, String baseUrl, ObjectProvider<JavaMailSender> mail,
        ObjectProvider<MailTemplate> templates, MessageCatalog messages, Predicate<String> hidden) {
        this(enabled, from, baseUrl, mail, templates, messages, hidden, name -> true);
    }

    /**
     * @param hidden      whether values under a name are hidden (secrets, masked fields)
     * @param entryExists whether a sign-in entry of that name is usable
     */
    MailChecks(boolean enabled, String from, String baseUrl, ObjectProvider<JavaMailSender> mail,
        ObjectProvider<MailTemplate> templates, MessageCatalog messages, Predicate<String> hidden,
        Predicate<String> entryExists) {
        this.hidden = hidden;
        this.entryExists = entryExists;
        this.enabled = enabled;
        this.from = from;
        this.baseUrl = baseUrl;
        this.mail = mail;
        this.templates = templates.orderedStream().toList();
        this.messages = messages;
    }

    @Override
    public List<CheckProblem> check() {
        List<CheckProblem> problems = new ArrayList<>();
        Set<String> names = new HashSet<>();
        boolean links = false;
        for (MailTemplate template : templates) {
            String location = "mail template " + template.name();
            if (!names.add(template.name())) {
                problems.add(CheckProblem.error(CATEGORY, location, "is declared more than once"));
                continue;
            }
            // The platform's namespace: a token of purpose jabiz.reset drawn by any other template would be taken by
            // the platform's processes (docs/design/10-security.md section 15.7).
            if (!com.jabiz.runtime.security.AuthMailTemplates.NAMES.contains(template.name())) {
                if (template.name().startsWith(MailTemplate.PLATFORM_PREFIX)) {
                    problems.add(CheckProblem.error(CATEGORY, location, "is named in the platform's namespace "
                        + MailTemplate.PLATFORM_PREFIX + ", which applications do not use"));
                }
                template.tokens().keySet().stream().filter(purpose -> purpose.startsWith(MailTemplate.PLATFORM_PREFIX))
                    .forEach(purpose -> problems.add(CheckProblem.error(CATEGORY, location, "draws tokens of purpose "
                        + purpose + ", in the platform's namespace " + MailTemplate.PLATFORM_PREFIX
                        + ", which applications do not use")));
            }
            for (String param : template.params()) {
                if (hidden.test(param)) {
                    problems.add(CheckProblem.error(CATEGORY, location, "parameter " + param + " is named like a "
                        + "secret or a masked field; a mail carries no secret but its one-time tokens"));
                }
            }
            for (Locale locale : messages.supportedLocales()) {
                Optional<String> subject = messages.find(template.subjectKey(), locale);
                Optional<String> body = messages.find(template.bodyKey(), locale);
                if (subject.isEmpty()) {
                    problems.add(CheckProblem.error(CATEGORY, location, "has no message " + template.subjectKey()
                        + " [" + locale + "]"));
                }
                if (body.isEmpty()) {
                    problems.add(CheckProblem.error(CATEGORY, location, "has no message " + template.bodyKey()
                        + " [" + locale + "]"));
                }
                if (subject.isPresent() && body.isPresent()) {
                    for (String problem : MailPlaceholders.problems(template, subject.get(), body.get())) {
                        problems.add(CheckProblem.error(CATEGORY, location, "[" + locale + "] " + problem));
                    }
                    Set<String> used = MailPlaceholders.of(subject.get() + "\n" + body.get());
                    used.stream().filter(MailTemplate::namedEntryUrl).map(MailTemplate::entryOf)
                        .filter(entry -> !entryExists.test(entry))
                        .forEach(entry -> problems.add(CheckProblem.error(CATEGORY, location, "[" + locale
                            + "] uses {" + MailTemplate.NAMED_ENTRY_URL_PREFIX + entry + "}, but there is no usable "
                            + "sign-in entry " + entry + " (jabiz.security.entries." + entry + ")")));
                    links |= used.contains(MailTemplate.BASE_URL) || used.contains(MailTemplate.ENTRY_URL)
                        || used.stream().anyMatch(MailTemplate::namedEntryUrl) || template.unsubscribable();
                }
            }
        }
        if (!enabled) {
            return problems;
        }
        if (from == null || from.isBlank()) {
            problems.add(CheckProblem.error(CATEGORY, "jabiz.mail.from", "mail is enabled but has no sender address"));
        }
        if (mail.getIfAvailable() == null) {
            problems.add(CheckProblem.error(CATEGORY, "spring.mail.host", "mail is enabled but no mail server is "
                + "configured"));
        }
        if (links && (baseUrl == null || baseUrl.isBlank())) {
            problems.add(CheckProblem.error(CATEGORY, "jabiz.mail.base-url", "mail is enabled and templates link to "
                + "the application, but its address is not configured"));
        }
        return problems;
    }
}
