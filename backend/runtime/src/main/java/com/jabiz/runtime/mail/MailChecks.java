package com.jabiz.runtime.mail;

import com.jabiz.i18n.MessageCatalog;
import com.jabiz.mail.MailPlaceholders;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.mail.MailSenderSource;
import com.jabiz.mail.MailTemplate;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import com.jabiz.runtime.security.secret.SecretSealer;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import com.jabiz.runtime.security.SensitiveDataMasker;
import com.jabiz.runtime.security.SignInEntries;
import com.jabiz.runtime.web.JabizWebProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
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
 *   <li>every entry a text names in {@code {entryUrl.<entry>}} is a usable sign-in entry (decision D43 item 4), and
 *       so is every entry a template declares itself for ({@link MailTemplate#entries}, decision D49);</li>
 *   <li>a warning for transactional mail declaring entries, which only matter to notifications;</li>
 *   <li>every unsubscribe page of an entry ({@code jabiz.mail.unsubscribe-paths.<entry>}, decision D52 item 3) is a
 *       relative path of a usable entry and lies under an SPA served here;</li>
 *   <li>a warning for a notification without its display name {@code mail.<name>.name} in a language of the
 *       application: its users see the template's name among their mail preferences instead (decision D49).</li>
 * </ul>
 */
@Component
public class MailChecks implements PlatformCheck {

    public static final String CATEGORY = "MAIL";

    /** {@code jabiz.security.email-link.max-ttl} when not configured. */
    static final Duration DEFAULT_MAX_SIGN_IN_LINK = Duration.ofDays(30);

    private final boolean enabled;
    private final String from;
    private final String baseUrl;
    private final ObjectProvider<JavaMailSender> mail;
    private final List<MailTemplate> templates;
    private final MessageCatalog messages;
    private final Predicate<String> hidden;
    private final Predicate<String> entryExists;
    private final Map<String, String> unsubscribePaths;
    private final Duration maxSignInLink;
    private final Function<String, Optional<String>> appPathOf;
    private final Predicate<String> served;
    /** Decision D56: the application's mail sender sources, the entities they name, and whether secrets can be sealed. */
    private final List<MailSenderSource> sources;
    private final Function<String, Optional<EntityDefinition>> entityOf;
    private final boolean sealKeyConfigured;
    private final boolean trustAllForTests;
    private final boolean development;

    /** The checks as before decision D52, without the entries' unsubscribe pages. */
    public MailChecks(boolean enabled, String from, String baseUrl, ObjectProvider<JavaMailSender> mail,
        ObjectProvider<MailTemplate> templates, MessageCatalog messages, SensitiveDataMasker masker,
        SignInEntries entries) {
        this(enabled, from, baseUrl, mail, templates, messages, masker::hidesByName,
            name -> entries.find(name).filter(entry -> entry.name().equals(name)).isPresent());
    }

    /**
     * @param settings the templates' settings, of which the entries' unsubscribe pages are checked (decision D52)
     * @param web      the SPAs served, under one of which each unsubscribe page must lie; none known when absent
     */
    @Autowired
    public MailChecks(@Value("${jabiz.mail.enabled:false}") boolean enabled, @Value("${jabiz.mail.from:}") String from,
        @Value("${jabiz.mail.base-url:}") String baseUrl, ObjectProvider<JavaMailSender> mail,
        ObjectProvider<MailTemplate> templates, MessageCatalog messages, SensitiveDataMasker masker,
        SignInEntries entries, MailTemplates settings, ObjectProvider<JabizWebProperties> web,
        @Value("${jabiz.security.email-link.max-ttl:P30D}") Duration maxSignInLink,
        ObjectProvider<MailSenderSource> sources, ObjectProvider<EntityDefinitionRegistry> entityRegistry,
        SecretSealer sealer, @Value("${jabiz.mail.senders.trust-all-for-tests:false}") boolean trustAllForTests,
        Environment environment) {
        this(enabled, from, baseUrl, mail, templates, messages, masker::hidesByName,
            name -> entries.find(name).filter(entry -> entry.name().equals(name)).isPresent(),
            settings.unsubscribePaths(),
            name -> entries.find(name).filter(entry -> entry.name().equals(name)).map(SignInEntries.Entry::appPath),
            path -> {
                JabizWebProperties spas = web.getIfAvailable();
                return spas == null || spas.spaFor(path).isPresent();
            }, maxSignInLink, sources.orderedStream().toList(), name -> {
                EntityDefinitionRegistry registry = entityRegistry.getIfAvailable();
                return registry == null ? Optional.empty() : registry.find(name);
            }, sealer.configured(), trustAllForTests, environment.acceptsProfiles(Profiles.of("dev")));
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
        this(enabled, from, baseUrl, mail, templates, messages, hidden, entryExists, Map.of(), name -> Optional.empty(),
            path -> true, DEFAULT_MAX_SIGN_IN_LINK);
    }

    /**
     * @param unsubscribePaths {@code jabiz.mail.unsubscribe-paths}, by entry
     * @param appPathOf        the {@code app-path} of a usable entry; empty for an entry that is not usable
     * @param served           whether a path lies under an SPA this application serves
     */
    MailChecks(boolean enabled, String from, String baseUrl, ObjectProvider<JavaMailSender> mail,
        ObjectProvider<MailTemplate> templates, MessageCatalog messages, Predicate<String> hidden,
        Predicate<String> entryExists, Map<String, String> unsubscribePaths,
        Function<String, Optional<String>> appPathOf, Predicate<String> served) {
        this(enabled, from, baseUrl, mail, templates, messages, hidden, entryExists, unsubscribePaths, appPathOf,
            served, DEFAULT_MAX_SIGN_IN_LINK);
    }

    /** @param maxSignInLink the longest validity of a sign-in link an application mail may carry (decision D55) */
    MailChecks(boolean enabled, String from, String baseUrl, ObjectProvider<JavaMailSender> mail,
        ObjectProvider<MailTemplate> templates, MessageCatalog messages, Predicate<String> hidden,
        Predicate<String> entryExists, Map<String, String> unsubscribePaths,
        Function<String, Optional<String>> appPathOf, Predicate<String> served, Duration maxSignInLink) {
        this(enabled, from, baseUrl, mail, templates, messages, hidden, entryExists, unsubscribePaths, appPathOf, served,
            maxSignInLink, List.of(), name -> Optional.empty(), true, false, true);
    }

    /**
     * @param sources           the application's mail sender sources (decision D56)
     * @param entityOf          the entity of a name, if declared
     * @param sealKeyConfigured whether {@code jabiz.security.seal.key} is set
     * @param trustAllForTests  {@code jabiz.mail.senders.trust-all-for-tests}
     * @param development       whether the dev profile is active
     */
    MailChecks(boolean enabled, String from, String baseUrl, ObjectProvider<JavaMailSender> mail,
        ObjectProvider<MailTemplate> templates, MessageCatalog messages, Predicate<String> hidden,
        Predicate<String> entryExists, Map<String, String> unsubscribePaths,
        Function<String, Optional<String>> appPathOf, Predicate<String> served, Duration maxSignInLink,
        List<MailSenderSource> sources, Function<String, Optional<EntityDefinition>> entityOf,
        boolean sealKeyConfigured, boolean trustAllForTests, boolean development) {
        this.sources = List.copyOf(sources);
        this.entityOf = entityOf;
        this.sealKeyConfigured = sealKeyConfigured;
        this.trustAllForTests = trustAllForTests;
        this.development = development;
        this.unsubscribePaths = Map.copyOf(unsubscribePaths);
        this.appPathOf = appPathOf;
        this.maxSignInLink = maxSignInLink;
        this.served = served;
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
        problems.addAll(senderProblems());
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
                // Except the sign-in link an application declares as such (decision D55): that token is meant to be
                // opened by the platform's SPONSOR_EMAIL_LINK_OPEN.
                template.tokens().keySet().stream().filter(purpose -> purpose.startsWith(MailTemplate.PLATFORM_PREFIX)
                        && !MailTemplate.SIGN_IN_TOKEN.equals(purpose))
                    .forEach(purpose -> problems.add(CheckProblem.error(CATEGORY, location, "draws tokens of purpose "
                        + purpose + ", in the platform's namespace " + MailTemplate.PLATFORM_PREFIX
                        + ", which applications do not use")));
                if (template.signInLink() && template.tokens().get(MailTemplate.SIGN_IN_TOKEN).compareTo(maxSignInLink)
                    > 0) {
                    problems.add(CheckProblem.error(CATEGORY, location, "its sign-in link is valid for "
                        + template.tokens().get(MailTemplate.SIGN_IN_TOKEN) + ", longer than "
                        + "jabiz.security.email-link.max-ttl (" + maxSignInLink + ")"));
                }
            }
            for (String param : template.params()) {
                if (hidden.test(param)) {
                    problems.add(CheckProblem.error(CATEGORY, location, "parameter " + param + " is named like a "
                        + "secret or a masked field; a mail carries no secret but its one-time tokens"));
                }
            }
            if (!template.entries().isEmpty() && !template.unsubscribable()) {
                problems.add(CheckProblem.warning(CATEGORY, location, "declares sign-in entries " + template.entries()
                    + ", but it is transactional mail, which is in nobody's preferences: the entries change nothing"));
            }
            template.entries().stream().filter(entry -> !entryExists.test(entry))
                .forEach(entry -> problems.add(CheckProblem.error(CATEGORY, location, "is declared for the sign-in "
                    + "entry " + entry + ", but there is no usable sign-in entry " + entry + " (jabiz.security.entries."
                    + entry + ")")));
            for (Locale locale : messages.supportedLocales()) {
                if (template.listedAmongPreferences() && messages.find(template.nameKey(), locale).isEmpty()) {
                    problems.add(CheckProblem.warning(CATEGORY, location, "has no message " + template.nameKey()
                        + " [" + locale + "]: its users see the name of the template among their mail preferences"));
                }
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
        problems.addAll(unsubscribePageProblems());
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

    /**
     * Each entry's unsubscribe page (decision D52 item 3) is a relative path, of a usable entry, and lies under an SPA
     * served here, as an entry's own pages should ({@code SignInEntries}): otherwise the mailed links lead nowhere.
     */
    /** Decision D56: one source at most, naming an entity and a field it has, with a key to open the passwords. */
    private List<CheckProblem> senderProblems() {
        List<CheckProblem> problems = new ArrayList<>();
        if (sources.size() > 1) {
            problems.add(CheckProblem.error(CATEGORY, "MailSenderSource", "is declared " + sources.size()
                + " times; the delivery resolves sender keys through one source"));
        }
        for (MailSenderSource source : sources) {
            String location = "MailSenderSource " + source.entity() + "." + source.keyField();
            Optional<EntityDefinition> entity = entityOf.apply(source.entity());
            if (entity.isEmpty()) {
                problems.add(CheckProblem.error(CATEGORY, location, "names an entity that is not declared"));
            } else if (!entity.get().fields.containsKey(source.keyField())) {
                problems.add(CheckProblem.error(CATEGORY, location, "names a field the entity does not have"));
            }
            if (!sealKeyConfigured) {
                problems.add(CheckProblem.error(CATEGORY, location, "needs jabiz.security.seal.key (JABIZ_SEAL_KEY) "
                    + "to open the servers' passwords"));
            }
        }
        if (trustAllForTests && !development) {
            problems.add(CheckProblem.warning(CATEGORY, "jabiz.mail.senders.trust-all-for-tests",
                "is on: a mail server on this machine is accepted on any port with any certificate; for tests only"));
        }
        return problems;
    }

    private List<CheckProblem> unsubscribePageProblems() {
        List<CheckProblem> problems = new ArrayList<>();
        new TreeMap<>(unsubscribePaths).forEach((entry, path) -> {
            String location = MailTemplates.UNSUBSCRIBE_PATHS + "." + entry;
            if (!MailTemplates.validRelativePath(path)) {
                problems.add(CheckProblem.error(CATEGORY, location, "'" + path + "' is not a path relative to the "
                    + "entry's app-path (segments of letters, digits and . _ ~ -)"));
                return;
            }
            Optional<String> appPath = appPathOf.apply(entry);
            if (appPath.isEmpty()) {
                problems.add(CheckProblem.error(CATEGORY, location, "there is no usable sign-in entry " + entry
                    + " (jabiz.security.entries." + entry + ")"));
                return;
            }
            String root = appPath.get().endsWith("/") ? appPath.get() : appPath.get() + "/";
            String page = root + MailTemplates.relative(path);
            if (!served.test(page)) {
                problems.add(CheckProblem.error(CATEGORY, location, "'" + page + "' is under none of the SPAs served "
                    + "(jabiz.web.spa): the unsubscribe links of entry " + entry + " would lead nowhere"));
            }
        });
        return problems;
    }
}
