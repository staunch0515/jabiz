package com.jabiz.runtime.mail;

import com.jabiz.entity.Violation;
import com.jabiz.i18n.MessageCatalog;
import com.jabiz.mail.MailPlaceholders;
import com.jabiz.mail.MailRenderer;
import com.jabiz.mail.MailTemplate;
import com.jabiz.mail.MailTextRules;
import com.jabiz.query.BoundValue;
import com.jabiz.runtime.observability.PlatformObservations;
import com.jabiz.runtime.storage.Rows;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import io.micrometer.common.KeyValues;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The texts a template's mail is sent with (decision D72): the override administrators wrote for the template and
 * language ({@link MailTextEntities}), when it still passes the {@link MailTextRules}, else the default, the messages
 * {@code mail.<name>.subject|body}. An override the code has outgrown (a parameter removed, a token renamed) falls back
 * to the default with a warning and the observation {@value #FALLBACK} (tag {@code template} only): a mail is never
 * sent with a text that fails the rules. Every mail of the platform reads its texts here: template mail
 * ({@code MAIL_SEND}), task notifications ({@code TASK_NOTIFY}) and documents ({@code DOCUMENT_SEND}).
 *
 * <p>Settings: {@code jabiz.mail.texts.max-subject-length} (default {@value MailTextRules#DEFAULT_MAX_SUBJECT}),
 * {@code jabiz.mail.texts.max-body-length} (default {@value MailTextRules#DEFAULT_MAX_BODY}) and
 * {@code jabiz.mail.texts.allowed-link-hosts} (hosts links may lead to besides the application's own, the host of
 * {@code jabiz.mail.base-url}; none by default).
 */
@Component
public class MailTexts {

    /** Observation of an override that fails the rules and was not used. */
    public static final String FALLBACK = "jabiz.mail.text.fallback";

    /** The value every token has in a preview or a test mail: it opens nothing, as no hash of it is stored. */
    public static final String SAMPLE_TOKEN = "sample-token-not-valid";

    private static final Logger log = LoggerFactory.getLogger(MailTexts.class);

    /**
     * The texts to send with.
     *
     * @param versionNo the version of the override used; null for the default
     * @param fellBack  whether an override exists but fails the rules, so that the default is used
     */
    public record Text(String subject, String body, Integer versionNo, boolean fellBack) {}

    /**
     * The latest version stored for a template and language.
     *
     * @param kind {@value MailTextEntities#CUSTOM} or {@value MailTextEntities#DEFAULT}
     */
    public record Stored(String id, int versionNo, String kind, String subject, String body, String defaultHash,
        boolean approved) {

        public boolean custom() {
            return MailTextEntities.CUSTOM.equals(kind);
        }
    }

    private final MessageCatalog messages;
    private final MailTemplates templates;
    private final StorageAdapterRegistry storages;
    private final PlatformObservations observations;
    private final String poolRef;
    private final MailTextRules.Limits limits;

    @Autowired
    public MailTexts(MessageCatalog messages, MailTemplates templates, StorageAdapterRegistry storages,
        PlatformObservations observations, @Value("${jabiz.storage.default-pool-ref:default}") String poolRef,
        @Value("${jabiz.mail.texts.max-subject-length:" + MailTextRules.DEFAULT_MAX_SUBJECT + "}") int maxSubject,
        @Value("${jabiz.mail.texts.max-body-length:" + MailTextRules.DEFAULT_MAX_BODY + "}") int maxBody,
        @Value("${jabiz.mail.texts.allowed-link-hosts:}") List<String> allowedLinkHosts) {
        this.messages = messages;
        this.templates = templates;
        this.storages = storages;
        this.observations = observations;
        this.poolRef = poolRef;
        this.limits = new MailTextRules.Limits(maxSubject, maxBody, siteHosts(templates.baseUrl()),
            Set.copyOf(allowedLinkHosts == null ? List.of() : allowedLinkHosts));
    }

    /** The host of {@code jabiz.mail.base-url}, the application's own; none when it is not configured. */
    static Set<String> siteHosts(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return Set.of();
        }
        try {
            String host = URI.create(baseUrl.strip()).getHost();
            return host == null ? Set.of() : Set.of(host);
        } catch (IllegalArgumentException e) {
            return Set.of();
        }
    }

    /** The configured limits of the rules. */
    public MailTextRules.Limits limits() {
        return limits;
    }

    /** The problems of a subject and body of {@code template} under the configured limits. */
    public List<Violation> problems(MailTemplate template, String subject, String body) {
        return MailTextRules.check(template, subject, body, limits);
    }

    /** Whether {@code locale} is one of the application's languages, exactly. */
    public boolean language(Locale locale) {
        return locale != null && messages.supportedLocales().contains(locale);
    }

    /** The default texts of a template in a language: its messages; empty when one is missing. */
    public Optional<Text> defaults(MailTemplate template, Locale locale) {
        Optional<String> subject = messages.find(template.subjectKey(), locale);
        Optional<String> body = messages.find(template.bodyKey(), locale);
        return subject.isPresent() && body.isPresent()
            ? Optional.of(new Text(subject.get(), body.get(), null, false)) : Optional.empty();
    }

    /** The hash of a template's default texts in a language; that of empty texts when they are missing. */
    public String defaultHash(MailTemplate template, Locale locale) {
        Text defaults = defaults(template, locale).orElse(new Text("", "", null, false));
        return MailTextRules.hash(defaults.subject(), defaults.body());
    }

    /** The latest stored version for a template and language; empty when there is none. */
    public Mono<Stored> stored(String template, Locale locale) {
        // One instance per template and language (unique); its versions are never scheduled.
        return storages.getEngine(poolRef).select("""
                SELECT mail_text_id, version_no, is_deleted, text_kind, subject, body, default_hash, approved
                FROM sys_mail_text_version WHERE template = :template AND locale = :locale
                ORDER BY version_no DESC LIMIT 1""",
                Map.of("template", BoundValue.of(template), "locale", BoundValue.of(locale.getLanguage())))
            .next()
            .filter(row -> !Boolean.TRUE.equals(row.get("is_deleted")))
            .map(row -> new Stored(Rows.string(row.get("mail_text_id")),
                Math.toIntExact(Rows.longValue(row.get("version_no"))), Rows.string(row.get("text_kind")),
                Rows.string(row.get("subject")), Rows.string(row.get("body")), Rows.string(row.get("default_hash")),
                Boolean.TRUE.equals(row.get("approved"))));
    }

    /** The latest stored version of every template and language, by {@link #key}. */
    public Mono<Map<String, Stored>> allStored() {
        return storages.getEngine(poolRef).select("""
                SELECT DISTINCT ON (template, locale) template, locale, mail_text_id, version_no, is_deleted,
                       text_kind, subject, body, default_hash, approved
                FROM sys_mail_text_version ORDER BY template, locale, version_no DESC""", Map.of())
            .filter(row -> !Boolean.TRUE.equals(row.get("is_deleted")))
            .collectMap(row -> key(Rows.string(row.get("template")), Rows.string(row.get("locale"))),
                row -> new Stored(Rows.string(row.get("mail_text_id")),
                    Math.toIntExact(Rows.longValue(row.get("version_no"))), Rows.string(row.get("text_kind")),
                    Rows.string(row.get("subject")), Rows.string(row.get("body")),
                    Rows.string(row.get("default_hash")), Boolean.TRUE.equals(row.get("approved"))));
    }

    /** The key of a template and language in {@link #allStored()}. */
    public static String key(String template, String language) {
        return template + "\n" + language;
    }

    /**
     * The texts to send {@code template}'s mail in {@code locale} with: the override if it passes the rules, else
     * the messages.
     *
     * @throws IllegalStateException (in the Mono) when a message of the default is missing (the startup check
     *                               reports it)
     */
    public Mono<Text> effective(MailTemplate template, Locale locale) {
        return Mono.defer(() -> effective(template, locale, defaults(template, locale).orElseThrow(() ->
            new IllegalStateException("No message " + template.subjectKey() + " or " + template.bodyKey() + " ["
                + locale + "]"))));
    }

    /** As {@link #effective(MailTemplate, Locale)}, with {@code defaults} as the default texts. */
    public Mono<Text> effective(MailTemplate template, Locale locale, Text defaults) {
        Objects.requireNonNull(defaults, "defaults must not be null");
        return stored(template.name(), locale)
            .map(stored -> choose(template, locale, stored, defaults))
            .defaultIfEmpty(defaults);
    }

    /** The override when it is one and passes the rules; the default otherwise. */
    Text choose(MailTemplate template, Locale locale, Stored stored, Text defaults) {
        if (!stored.custom()) {
            return defaults;
        }
        List<Violation> problems = problems(template, stored.subject(), stored.body());
        if (problems.isEmpty() && !unapproved(template, stored)) {
            return new Text(stored.subject(), stored.body(), stored.versionNo(), false);
        }
        if (problems.isEmpty()) {
            log.warn("The text of mail {} [{}], version {}, was saved without approval, which the template needs now;"
                + " sending the default", template.name(), locale.getLanguage(), stored.versionNo());
        } else {
            log.warn("The text of mail {} [{}], version {}, no longer passes the rules ({}); sending the default",
                template.name(), locale.getLanguage(), stored.versionNo(), String.join("; ",
                    MailTextRules.describe(problems)));
        }
        observations.event(FALLBACK, KeyValues.of("template", template.name()));
        return new Text(defaults.subject(), defaults.body(), defaults.versionNo(), true);
    }

    /**
     * Whether an override lacks the approval its template needs now: saved without one before the template declared a
     * token, a sign-in link or {@code protectedText()} (17zh-a review, F5). Such an override is not sent.
     */
    public static boolean unapproved(MailTemplate template, Stored stored) {
        return stored.custom() && template.textChangeNeedsApproval() && !stored.approved();
    }

    /**
     * Renders texts of {@code template} with {@code values}, adding the roots of the named entries' frontends
     * ({@code {entryUrl.<entry>}}, from configuration), the subject cut to the configured length.
     */
    public MailRenderer.Rendered render(MailTemplate template, String subject, String body, Map<String, String> values) {
        Map<String, String> filled = new LinkedHashMap<>(values);
        MailPlaceholders.of(subject + "\n" + body).stream().filter(MailTemplate::namedEntryUrl)
            .forEach(name -> filled.put(name, templates.entryUrl(MailTemplate.entryOf(name))));
        return MailRenderer.render(subject, body, filled, template.urlSafePlaceholders(), limits.maxSubjectLength());
    }

    /**
     * The values a preview or a test mail shows (decision D72): the template's samples, {@code [name]} for the
     * parameters without one, a token that opens nothing, and the links of the application as configured.
     */
    public Map<String, String> sampleValues(MailTemplate template) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String param : template.params()) {
            values.put(param, template.sampleOf(param));
        }
        Set<String> tokens = new LinkedHashSet<>(template.tokens().keySet());
        tokens.forEach(purpose -> values.put(purpose, SAMPLE_TOKEN));
        values.put(MailTemplate.BASE_URL, templates.baseUrl());
        if (template.params().contains(MailTemplate.ENTRY_PARAM)) {
            String entry = template.samples().get(MailTemplate.ENTRY_PARAM);
            values.put(MailTemplate.ENTRY_URL, templates.entryUrl(entry));
        }
        if (template.unsubscribable()) {
            values.put(MailTemplate.UNSUBSCRIBE_URL, templates.unsubscribeUrl(SAMPLE_TOKEN, template));
        }
        return values;
    }
}
