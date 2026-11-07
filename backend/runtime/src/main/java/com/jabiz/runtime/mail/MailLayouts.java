package com.jabiz.runtime.mail;

import com.jabiz.entity.Violation;
import com.jabiz.i18n.MessageCatalog;
import com.jabiz.mail.MailLayout;
import com.jabiz.mail.MailLayoutRules;
import com.jabiz.mail.MailPlaceholders;
import com.jabiz.mail.MailRenderer;
import com.jabiz.mail.MailTemplate;
import com.jabiz.query.BoundValue;
import com.jabiz.runtime.observability.PlatformObservations;
import com.jabiz.runtime.storage.Rows;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import io.micrometer.common.KeyValues;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The layout mail of a language is sent on (decision D72; 17zh-b): the one administrators wrote
 * ({@link MailLayoutEntities}), when it still passes the {@link MailLayoutRules}, else the default of the configuration
 * {@code jabiz.mail.layout.<language>.display-name|logo-url|logo-alt|footer} (empty when not configured: mail as
 * before layouts). A stored layout that fails the rules (the allowed hosts were narrowed since) falls back to the
 * default with a warning and the observation {@value #FALLBACK}: mail is never sent on a layout that fails them. Every
 * mail of the platform is put on its layout here: template mail ({@code MAIL_SEND}), task notifications and document
 * mail, previews and test mail.
 *
 * <p>Setting: {@code jabiz.mail.layout.max-footer-length} (default {@value MailLayoutRules#DEFAULT_MAX_FOOTER}); the
 * footer's links follow {@code jabiz.mail.texts.allowed-link-hosts} as the texts do.
 */
@Component
public class MailLayouts {

    /** Observation of a stored layout that fails the rules and was not used. */
    public static final String FALLBACK = "jabiz.mail.layout.fallback";

    static final String PREFIX = "jabiz.mail.layout.";

    private static final Logger log = LoggerFactory.getLogger(MailLayouts.class);

    /**
     * The layout to send on.
     *
     * @param versionNo the version of the stored layout used; null for the default
     * @param fellBack  whether a stored layout exists but fails the rules, so that the default is used
     */
    public record Chosen(MailLayout layout, Integer versionNo, boolean fellBack) {}

    /**
     * A stored version of a language's layout.
     *
     * @param kind {@value MailTextEntities#CUSTOM} or {@value MailTextEntities#DEFAULT}
     */
    public record Stored(String id, int versionNo, String kind, MailLayout layout, String defaultHash) {

        public boolean custom() {
            return MailTextEntities.CUSTOM.equals(kind);
        }
    }

    private final MessageCatalog messages;
    private final MailTemplates templates;
    private final StorageAdapterRegistry storages;
    private final PlatformObservations observations;
    private final Environment environment;
    private final String poolRef;
    private final MailLayoutRules.Limits limits;

    MailLayouts(MessageCatalog messages, MailTemplates templates, MailTexts texts, StorageAdapterRegistry storages,
        PlatformObservations observations, Environment environment,
        @Value("${jabiz.storage.default-pool-ref:default}") String poolRef,
        @Value("${jabiz.mail.layout.max-footer-length:" + MailLayoutRules.DEFAULT_MAX_FOOTER + "}") int maxFooter) {
        this.messages = messages;
        this.templates = templates;
        this.storages = storages;
        this.observations = observations;
        this.environment = environment;
        this.poolRef = poolRef;
        this.limits = new MailLayoutRules.Limits(maxFooter, texts.limits());
    }

    /** The configured limits of the rules. */
    public MailLayoutRules.Limits limits() {
        return limits;
    }

    /** The problems of a layout under the configured limits. */
    public List<Violation> problems(MailLayout layout) {
        return MailLayoutRules.check(layout, limits);
    }

    /** Whether {@code locale} is one of the application's languages, exactly. */
    public boolean language(Locale locale) {
        return locale != null && messages.supportedLocales().contains(locale);
    }

    /** The default layout of a language, from configuration; empty parts when not configured. */
    public MailLayout defaults(Locale locale) {
        String prefix = PREFIX + locale.getLanguage() + ".";
        return new MailLayout(environment.getProperty(prefix + "display-name", ""),
            environment.getProperty(prefix + "logo-url", ""), environment.getProperty(prefix + "logo-alt", ""),
            environment.getProperty(prefix + "footer", ""));
    }

    /** The latest stored version of a language's layout; empty when there is none. */
    public Mono<Stored> stored(Locale locale) {
        return storages.getEngine(poolRef).select("""
                SELECT mail_layout_id, version_no, is_deleted, layout_kind, display_name, logo_url, logo_alt, footer,
                       default_hash
                FROM sys_mail_layout_version WHERE locale = :locale ORDER BY version_no DESC LIMIT 1""",
                Map.of("locale", BoundValue.of(locale.getLanguage())))
            .next()
            .filter(row -> !Boolean.TRUE.equals(row.get("is_deleted")))
            .map(MailLayouts::stored);
    }

    /** A given stored version of a language's layout (versions are never changed); empty when there is none. */
    public Mono<Stored> version(Locale locale, int versionNo) {
        return storages.getEngine(poolRef).select("""
                SELECT mail_layout_id, version_no, is_deleted, layout_kind, display_name, logo_url, logo_alt, footer,
                       default_hash
                FROM sys_mail_layout_version WHERE locale = :locale AND version_no = :version""",
                Map.of("locale", BoundValue.of(locale.getLanguage()), "version", BoundValue.of(versionNo)))
            .next()
            .map(MailLayouts::stored);
    }

    /** The latest stored version of every language, by language. */
    public Mono<Map<String, Stored>> allStored() {
        return storages.getEngine(poolRef).select("""
                SELECT DISTINCT ON (locale) locale, mail_layout_id, version_no, is_deleted, layout_kind, display_name,
                       logo_url, logo_alt, footer, default_hash
                FROM sys_mail_layout_version ORDER BY locale, version_no DESC""", Map.of())
            .filter(row -> !Boolean.TRUE.equals(row.get("is_deleted")))
            .collectMap(row -> Rows.string(row.get("locale")), MailLayouts::stored);
    }

    private static Stored stored(Map<String, Object> row) {
        return new Stored(Rows.string(row.get("mail_layout_id")),
            Math.toIntExact(Rows.longValue(row.get("version_no"))), Rows.string(row.get("layout_kind")),
            new MailLayout(Rows.string(row.get("display_name")), Rows.string(row.get("logo_url")),
                Rows.string(row.get("logo_alt")), Rows.string(row.get("footer"))),
            Rows.string(row.get("default_hash")));
    }

    /** The layout to send mail in {@code locale} on now: the stored one if it passes the rules, else the default. */
    public Mono<Chosen> effective(Locale locale) {
        return Mono.defer(() -> stored(locale).map(stored -> choose(locale, stored))
            .defaultIfEmpty(new Chosen(defaults(locale), null, false)));
    }

    /**
     * The layout a mail recorded with {@code versionNo} is sent on (a task notification or document mail records the
     * version when it is recorded and is sent after the commit): that version, or the default for none.
     */
    public Mono<Chosen> recorded(Locale locale, Integer versionNo) {
        if (versionNo == null) {
            return Mono.fromSupplier(() -> new Chosen(defaults(locale), null, false));
        }
        return version(locale, versionNo).map(stored -> choose(locale, stored))
            .defaultIfEmpty(new Chosen(defaults(locale), null, true));
    }

    /** The stored layout when it is one and passes the rules; the default otherwise. */
    Chosen choose(Locale locale, Stored stored) {
        if (!stored.custom()) {
            return new Chosen(defaults(locale), null, false);
        }
        List<Violation> problems = problems(stored.layout());
        if (problems.isEmpty()) {
            return new Chosen(stored.layout(), stored.versionNo(), false);
        }
        log.warn("The mail layout [{}], version {}, no longer passes the rules ({}); sending on the default",
            locale.getLanguage(), stored.versionNo(), String.join("; ", MailLayoutRules.describe(problems)));
        observations.event(FALLBACK, KeyValues.of("locale", locale.getLanguage()));
        return new Chosen(defaults(locale), null, true);
    }

    /** Puts a rendered mail on a layout, with the application's addresses for the footer and logo. */
    public MailRenderer.Rendered wrap(MailRenderer.Rendered mail, MailLayout layout) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(MailTemplate.BASE_URL, templates.baseUrl());
        MailPlaceholders.of(layout.footer()).stream().filter(MailTemplate::namedEntryUrl)
            .forEach(name -> values.put(name, templates.entryUrl(MailTemplate.entryOf(name))));
        return MailRenderer.wrap(mail, layout, values);
    }

    /** The hash of a language's default layout. */
    public String defaultHash(Locale locale) {
        return defaults(locale).hash();
    }
}
