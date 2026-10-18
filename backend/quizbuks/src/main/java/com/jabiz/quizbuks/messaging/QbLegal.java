package com.jabiz.quizbuks.messaging;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;
import com.jabiz.entity.i18n.I18nText;

import java.util.List;

/**
 * The entities of terms, privacy and help (docs/quizbuks/plans/Q7-messages-legal.md, Q7b, D-Q7-4 – D-Q7-6):
 * <ul>
 *   <li>{@code QbLegalDoc}: the draft of one kind of legal text, edited by the content administrators in the
 *       administration (several languages, Markdown with preview); one per kind, created by {@code QB_SETUP} with a
 *       marked test placeholder;</li>
 *   <li>{@code QbLegalVersion}: a published version, written once ({@code QB_LEGAL_PUBLISH}); what users accept and
 *       what the public pages show. The version in effect at a time is the highest number whose {@code effectiveAt}
 *       is not after it ({@link LegalRules}): a version announced for later needs no job to take effect;</li>
 *   <li>{@code QbConsent}: a user's acceptance of one version, written once;</li>
 *   <li>{@code QbFaqEntry}: a question and its answer of the help centre, edited by the content administrators.</li>
 * </ul>
 */
public final class QbLegal {

    public static final String DOC = "QbLegalDoc";
    public static final String VERSION = "QbLegalVersion";
    public static final String CONSENT = "QbConsent";
    public static final String FAQ = "QbFaqEntry";

    public static final String KIND_DICTIONARY = "urn:jabiz:dict:quizbuks:legal-kind";
    public static final String FAQ_CATEGORY_DICTIONARY = "urn:jabiz:dict:quizbuks:faq-category";
    public static final String FAQ_AUDIENCE_DICTIONARY = "urn:jabiz:dict:quizbuks:faq-audience";
    public static final String CONSENT_SOURCE_DICTIONARY = "urn:jabiz:dict:quizbuks:consent-source";

    /** The takers' terms of use. */
    public static final String TERMS_APP = "TERMS_APP";
    /** The sponsors' terms. */
    public static final String TERMS_SPONSOR = "TERMS_SPONSOR";
    /** The privacy policy, one for everybody. */
    public static final String PRIVACY = "PRIVACY";
    /** The notice under the Act on Specified Commercial Transactions (特定商取引法に基づく表記), shown to sponsors. */
    public static final String COMMERCE_NOTICE = "COMMERCE_NOTICE";
    public static final List<String> KINDS = List.of(TERMS_APP, TERMS_SPONSOR, PRIVACY, COMMERCE_NOTICE);

    /** What the app's users accept: the takers' terms and the privacy policy. */
    public static final List<String> APP_KINDS = List.of(TERMS_APP, PRIVACY);
    /** What sponsors accept: the sponsors' terms and the privacy policy. */
    public static final List<String> SPONSOR_KINDS = List.of(TERMS_SPONSOR, PRIVACY);
    /** What must be in effect before going live (the startup warning, D-Q7-4). */
    public static final List<String> REQUIRED_FOR_LIVE = List.of(TERMS_APP, TERMS_SPONSOR, PRIVACY);

    /** The languages a published version must have (Q7-4: Japanese governs, English is supplied too). */
    public static final List<String> REQUIRED_LANGUAGES = List.of("ja", "en");

    /** Where a consent was given. */
    public static final String SOURCE_WELCOME = "WELCOME";
    public static final String SOURCE_ONBOARDING = "ONBOARDING";
    public static final String SOURCE_RECONSENT = "RECONSENT";
    public static final List<String> SOURCES = List.of(SOURCE_WELCOME, SOURCE_ONBOARDING, SOURCE_RECONSENT);

    public static final String AUDIENCE_APP = "APP";
    public static final String AUDIENCE_SPONSOR = "SPONSOR";
    public static final String AUDIENCE_ALL = "ALL";
    public static final List<String> AUDIENCES = List.of(AUDIENCE_APP, AUDIENCE_SPONSOR, AUDIENCE_ALL);

    /** The help centre's categories (Q7-7): the takers' four, then the sponsors' three. */
    public static final List<String> FAQ_CATEGORIES = List.of("ACCOUNT", "QUIZ_RULES", "REWARDS", "OTHER",
        "ONBOARDING", "PUBLISHING", "BILLING");

    public static final int TITLE_LENGTH = 200;
    public static final int BODY_LENGTH = 100_000;
    public static final int SUMMARY_LENGTH = 5_000;
    public static final int QUESTION_LENGTH = 300;
    public static final int ANSWER_LENGTH = 10_000;
    static final int USER_ID_LENGTH = 128;

    public static String defaultDataset(String entity) {
        return "urn:jabiz:dataset:default:" + entity;
    }

    /** The published versions, readable by anybody: public pages (decision D17). */
    public static final String PUBLIC_VERSIONS = "urn:jabiz:dataset:public:" + VERSION;
    /** The published questions and answers, readable by anybody. */
    public static final String PUBLIC_FAQ = "urn:jabiz:dataset:public:" + FAQ;
    /** One's own consents, read only. */
    public static final String MY_CONSENTS = "urn:jabiz:dataset:me:" + CONSENT;

    /** The SQL templates (queries/qb/public, queries/qb/me, queries/qb/admin, queries/qb/system). */
    public static final String PUBLIC_LEGAL_TEMPLATE = "qb.public.legal";
    public static final String PUBLIC_LEGAL_VERSION_TEMPLATE = "qb.public.legal-version";
    public static final String PUBLIC_LEGAL_HISTORY_TEMPLATE = "qb.public.legal-history";
    public static final String PUBLIC_FAQ_TEMPLATE = "qb.public.faq";
    public static final String MY_CONSENTS_TEMPLATE = "qb.me.consents";
    public static final String MY_SUPPORT_TEMPLATE = "qb.me.support";
    public static final String ADMIN_CONSENTS_TEMPLATE = "qb.admin.consents";
    public static final String CONSENT_STATE_TEMPLATE = "qb.system.consent-state";

    /** The lock of one kind's publications ({@code HoldLock}): two publications of a kind take turns. */
    public static String lockName(Object docId) {
        return "qb.legal:" + docId;
    }

    public static final EntityDefinition DOC_ENTITY = EntityDefinition.define(DOC, eb -> {
        eb.physicalTable("qb_legal_doc_version");
        eb.primaryKey("docId");
        eb.field("docId", f -> f.physicalColumn("doc_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:legal-doc"));
        eb.field("kind", f -> f.physicalColumn("kind").immutable(true).required(true)
            .asCode(KIND_DICTIONARY, KINDS.toArray(String[]::new)));
        // The draft: any language may be missing while it is edited; publishing needs Japanese and English.
        eb.field("title", f -> f.physicalColumn("title").apply(I18nText.of(TITLE_LENGTH)));
        eb.field("body", f -> f.physicalColumn("body").apply(I18nText.markdown(BODY_LENGTH)));
        eb.field("changeSummary", f -> f.physicalColumn("change_summary")
            .apply(I18nText.markdown(SUMMARY_LENGTH)));
        eb.field("latestVersionNo", f -> f.physicalColumn("latest_version_no").processOnly().asNumeric(6, 0));
        eb.field("lastPublishedAt", f -> f.physicalColumn("last_published_at").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.unique("uk_qb_legal_doc_kind", "kind");
        eb.display("title");
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("kind", "title", "latestVersionNo", "lastPublishedAt")
            .filters("kind")
            .sorts("kind", "lastPublishedAt")
            .defaultSort("kind", true));
    });

    public static final EntityDefinition VERSION_ENTITY = EntityDefinition.define(VERSION, eb -> {
        eb.physicalTable("qb_legal_version_version");
        eb.primaryKey("legalVersionId");
        eb.field("legalVersionId", f -> f.physicalColumn("legal_version_id").immutable(true).required(true)
            .generated(true).asSemanticIdentity("urn:jabiz:entity:quizbuks:legal-version"));
        eb.field("kind", f -> f.physicalColumn("kind").immutable(true).required(true)
            .asCode(KIND_DICTIONARY, KINDS.toArray(String[]::new)));
        // versionNo is the platform's own row version of every temporal entity.
        eb.field("versionNumber", f -> f.physicalColumn("legal_version_no").immutable(true).required(true)
            .asNumeric(6, 0));
        eb.field("title", f -> f.physicalColumn("title").immutable(true).required(true)
            .apply(I18nText.of(TITLE_LENGTH).required(REQUIRED_LANGUAGES.toArray(String[]::new))));
        eb.field("body", f -> f.physicalColumn("body").immutable(true).required(true)
            .apply(I18nText.markdown(BODY_LENGTH).required(REQUIRED_LANGUAGES.toArray(String[]::new))));
        eb.field("changeSummary", f -> f.physicalColumn("change_summary").immutable(true)
            .apply(I18nText.markdown(SUMMARY_LENGTH)));
        eb.field("requiresConsent", f -> f.physicalColumn("requires_consent").immutable(true).required(true)
            .asBool());
        eb.field("publishedAt", f -> f.physicalColumn("published_at").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("publishedBy", f -> f.physicalColumn("published_by").immutable(true).required(true)
            .asText(USER_ID_LENGTH));
        eb.field("effectiveAt", f -> f.physicalColumn("effective_at").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("contentHash", f -> f.physicalColumn("content_hash").immutable(true).required(true).asText(64));
        eb.unique("uk_qb_legal_version_no", "kind", "versionNumber");
        eb.temporal(t -> t.allowScheduled(false).writeOnce());
        eb.listView("default", lv -> lv
            .columns("kind", "versionNumber", "title", "requiresConsent", "effectiveAt", "publishedAt",
                "publishedBy")
            .filters("kind", "requiresConsent", "effectiveAt", "publishedAt")
            .sorts("kind", "versionNumber", "effectiveAt", "publishedAt")
            .defaultSort("publishedAt", false));
    });

    public static final EntityDefinition CONSENT_ENTITY = EntityDefinition.define(CONSENT, eb -> {
        eb.physicalTable("qb_consent_version");
        eb.primaryKey("consentId");
        eb.field("consentId", f -> f.physicalColumn("consent_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:consent"));
        // The caller's id as text, as the profile's userId.
        eb.field("userId", f -> f.physicalColumn("user_id").immutable(true).required(true).asText(USER_ID_LENGTH));
        eb.field("kind", f -> f.physicalColumn("kind").immutable(true).required(true)
            .asCode(KIND_DICTIONARY, KINDS.toArray(String[]::new)));
        eb.field("legalVersionId", f -> f.physicalColumn("legal_version_id").immutable(true).required(true)
            .asReference(VERSION));
        eb.field("versionNumber", f -> f.physicalColumn("legal_version_no").immutable(true).required(true)
            .asNumeric(6, 0));
        eb.field("acceptedAt", f -> f.physicalColumn("accepted_at").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        // The sign-in entry of the session (app, sponsor); none outside an entry (scenarios, the system).
        eb.field("entry", f -> f.physicalColumn("entry").immutable(true).asText(20));
        eb.field("source", f -> f.physicalColumn("source").immutable(true).required(true)
            .asCode(CONSENT_SOURCE_DICTIONARY, SOURCES.toArray(String[]::new)));
        eb.temporal(t -> t.allowScheduled(false).writeOnce());
        eb.listView("default", lv -> lv
            .columns("userId", "kind", "versionNumber", "acceptedAt", "entry", "source")
            .filters("userId", "kind", "versionNumber", "acceptedAt", "source")
            .sorts("acceptedAt", "kind", "versionNumber")
            .defaultSort("acceptedAt", false));
    });

    public static final EntityDefinition FAQ_ENTITY = EntityDefinition.define(FAQ, eb -> {
        eb.physicalTable("qb_faq_entry_version");
        eb.primaryKey("faqId");
        eb.field("faqId", f -> f.physicalColumn("faq_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:faq-entry"));
        eb.field("audience", f -> f.physicalColumn("audience").required(true)
            .asCode(FAQ_AUDIENCE_DICTIONARY, AUDIENCES.toArray(String[]::new)));
        eb.field("category", f -> f.physicalColumn("category").required(true)
            .asCode(FAQ_CATEGORY_DICTIONARY, FAQ_CATEGORIES.toArray(String[]::new)));
        eb.field("question", f -> f.physicalColumn("question").required(true)
            .apply(I18nText.of(QUESTION_LENGTH).required("en")));
        eb.field("answer", f -> f.physicalColumn("answer").required(true)
            .apply(I18nText.markdown(ANSWER_LENGTH).required("en")));
        eb.field("seq", f -> f.physicalColumn("seq").required(true).asNumeric(6, 0));
        eb.field("published", f -> f.physicalColumn("published").required(true).asBool());
        eb.display("question");
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("audience", "category", "question", "seq", "published")
            .filters("audience", "category", "published")
            .sorts("seq", "category")
            .defaultSort("seq", true));
    });

    private QbLegal() {}
}
