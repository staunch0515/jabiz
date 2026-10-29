package com.jabiz.quizbuks.prospect;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * AI prospecting (docs/quizbuks/plans/Q48-operators.md, Q48c): an owner (an operator, or headquarters as
 * {@value #DIRECT}) finds businesses on the web along its directions, Claude researches each and drafts a trial quiz
 * and a mail, a person approves it, and it goes out within the day's cap through the owner's own mail server. Five
 * ordinary tables, written by the processes only ({@link ProspectProcesses}):
 * <ul>
 *   <li>{@value #SETTING}: one per owner (switch, daily cap, score, cost cap, the sender the mails name);</li>
 *   <li>{@value #DIRECTION}: what to search for;</li>
 *   <li>{@value #PROSPECT}: a business found, one per owner and domain, through its states ({@link #STATUSES});</li>
 *   <li>{@value #BLOCK}: addresses and domains never written to again, for every owner;</li>
 *   <li>{@value #USAGE}: what prospecting cost an owner in a month.</li>
 * </ul>
 * Lock order: {@code qb.prospect-owner:<owner>} (settings, directions, the day's starts and sends) →
 * {@code qb.prospect:<id>} → {@code qb.prospect-usage:<owner>}; the membership lock of an operator's member comes
 * before all of them.
 */
public final class QbProspects {

    public static final String SETTING = "QbProspectSetting";
    public static final String DIRECTION = "QbProspectDirection";
    public static final String PROSPECT = "QbProspect";
    public static final String BLOCK = "QbProspectBlock";
    public static final String USAGE = "QbProspectUsage";

    /** Headquarters as an owner: it prospects too, as the direct "operator" (no operator row, D-Q48-1). */
    public static final String DIRECT = "direct";
    /** The trial quizzes that went out, read by the public trial page. */
    public static final String PUBLIC_TRIALS = "urn:jabiz:dataset:public:QbProspect";

    public static final String STATUS_DICTIONARY = "urn:jabiz:dict:quizbuks:prospect-status";
    public static final String BLOCK_REASON_DICTIONARY = "urn:jabiz:dict:quizbuks:prospect-block-reason";

    /** Found by a search, not researched yet. */
    public static final String FOUND = "FOUND";
    /** Its research job runs. */
    public static final String RESEARCHING = "RESEARCHING";
    /** Researched, scored at or above the threshold, with a trial quiz and a mail drafted: waiting for a person. */
    public static final String DRAFTED = "DRAFTED";
    /** Scored below the owner's threshold: kept on record only. */
    public static final String LOW_SCORE = "LOW_SCORE";
    /** Not to be written to: no public business address, the site refuses marketing mail, or the address is blocked. */
    public static final String SKIPPED = "SKIPPED";
    /** The research failed for good. */
    public static final String FAILED = "FAILED";
    /** Approved by a person: goes out with the day's sends. */
    public static final String APPROVED = "APPROVED";
    /** The mail went out. */
    public static final String SENT = "SENT";
    /** A person discarded the draft. */
    public static final String DISCARDED = "DISCARDED";
    public static final List<String> STATUSES = List.of(FOUND, RESEARCHING, DRAFTED, LOW_SCORE, SKIPPED, FAILED,
        APPROVED, SENT, DISCARDED);

    public static final String KIND_EMAIL = "EMAIL";
    public static final String KIND_DOMAIN = "DOMAIN";
    public static final List<String> BLOCK_KINDS = List.of(KIND_EMAIL, KIND_DOMAIN);

    /** The recipient unsubscribed (the platform's opt-out by address, D55). */
    public static final String UNSUBSCRIBED = "UNSUBSCRIBED";
    /** The business answered that it does not want this. */
    public static final String DECLINED = "DECLINED";
    public static final String BOUNCED = "BOUNCED";
    public static final String COMPLAINED = "COMPLAINED";
    /** The business's site says it refuses marketing mail. */
    public static final String REFUSES_MARKETING = "REFUSES_MARKETING";
    /** Blocked by a person for another reason. */
    public static final String MANUAL = "MANUAL";
    public static final List<String> BLOCK_REASONS = List.of(UNSUBSCRIBED, DECLINED, BOUNCED, COMPLAINED,
        REFUSES_MARKETING, MANUAL);

    public static final int NAME_LENGTH = 100;
    public static final int BRIEF_LENGTH = 1000;
    public static final int SUBJECT_LENGTH = 200;
    public static final int BODY_LENGTH = 4000;
    public static final int SENDER_ADDRESS_LENGTH = 300;
    public static final int EMAIL_LENGTH = 254;
    public static final int NOTE_LENGTH = 500;
    /** The fewest and most mails an owner sends a day (D-Q48 §3.1 step 7: from 20 up to 50 over weeks). */
    public static final int MIN_DAILY_CAP = 1;
    public static final int MAX_DAILY_CAP = 50;
    /** The default and the bounds of the score a business needs. */
    public static final int DEFAULT_MIN_SCORE = 70;
    /** The most directions an owner keeps, and candidates one search may bring. */
    public static final int MAX_DIRECTIONS = 50;
    public static final int MAX_PER_SEARCH = 20;
    /** The most of its domains known already a search is told about, so it does not bring them again. */
    public static final int MAX_EXCLUDED_DOMAINS = 300;

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM", Locale.ROOT);

    /** The month a time falls in, as the usage is kept (Japan's calendar month: {@code jabiz.fiscal-year-zone}). */
    public static String month(Instant time, ZoneId zone) {
        return MONTH.format(time.atZone(zone));
    }

    public static String defaultDataset(String entity) {
        return "urn:jabiz:dataset:default:" + entity;
    }

    /** The owner's settings, directions and day: taken by whoever changes them and by the day's starts and sends. */
    public static String ownerLockName(Object owner) {
        return owner == null ? null : "qb.prospect-owner:" + owner;
    }

    public static String prospectLockName(Object prospectId) {
        return prospectId == null ? null : "qb.prospect:" + prospectId;
    }

    public static String usageLockName(Object owner) {
        return owner == null ? null : "qb.prospect-usage:" + owner;
    }

    /** The sender key of the owner's mail server (the sponsors' table holds it, Q29: {@code SendMail.via}). */
    public static String senderKey(Object owner) {
        return "prospect:" + owner;
    }

    /** A domain as kept: lower case, without a leading {@code www.}. */
    public static String domain(String value) {
        if (value == null) {
            return null;
        }
        String domain = value.strip().toLowerCase(Locale.ROOT);
        if (domain.endsWith(".")) {
            domain = domain.substring(0, domain.length() - 1);
        }
        return domain.startsWith("www.") ? domain.substring(4) : domain;
    }

    public static final EntityDefinition SETTING_ENTITY = EntityDefinition.define(SETTING, eb -> {
        eb.physicalTable("qb_prospect_setting");
        eb.primaryKey("settingId");
        eb.field("settingId", f -> f.physicalColumn("setting_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:prospect-setting"));
        eb.field("owner", f -> f.physicalColumn("owner").immutable(true).required(true).asText(36));
        eb.field("enabled", f -> f.physicalColumn("enabled").required(true).asBool());
        eb.field("dailyCap", f -> f.physicalColumn("daily_cap").required(true).asNumeric(4, 0));
        eb.field("minScore", f -> f.physicalColumn("min_score").required(true).asNumeric(3, 0));
        eb.field("monthlyCostCap", f -> f.physicalColumn("monthly_cost_cap").required(true).asNumeric(10, 2));
        eb.field("senderName", f -> f.physicalColumn("sender_name").required(true).asText(NAME_LENGTH));
        eb.field("senderAddress", f -> f.physicalColumn("sender_address").required(true)
            .asText(SENDER_ADDRESS_LENGTH));
        eb.field("senderContact", f -> f.physicalColumn("sender_contact").required(true).asText(EMAIL_LENGTH));
        // Q48d: a draft scoring at least this is approved without a person; null: never.
        eb.field("autoApproveScore", f -> f.physicalColumn("auto_approve_score").asNumeric(3, 0));
        eb.field("updatedAt", f -> f.physicalColumn("updated_at").required(true).asTemporal(TemporalRole.EVENT_TIME));
        eb.field("updatedBy", f -> f.physicalColumn("updated_by").required(true).asText(128));
        eb.field("revision", f -> f.physicalColumn("revision").required(true).asNumeric(9, 0));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.unique("uk_qb_prospect_setting_owner", "owner");
        eb.listView("default", lv -> lv
            .columns("owner", "enabled", "dailyCap", "minScore", "monthlyCostCap", "senderName", "updatedAt")
            .filters("owner", "enabled")
            .sorts("updatedAt")
            .defaultSort("updatedAt", false));
    });

    public static final EntityDefinition DIRECTION_ENTITY = EntityDefinition.define(DIRECTION, eb -> {
        eb.physicalTable("qb_prospect_direction");
        eb.primaryKey("directionId");
        eb.field("directionId", f -> f.physicalColumn("direction_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:prospect-direction"));
        eb.field("owner", f -> f.physicalColumn("owner").immutable(true).required(true).asText(36));
        eb.field("name", f -> f.physicalColumn("name").required(true).asText(NAME_LENGTH));
        eb.field("brief", f -> f.physicalColumn("brief").required(true).asText(BRIEF_LENGTH, true));
        eb.field("country", f -> f.physicalColumn("country").asText(2));
        eb.field("language", f -> f.physicalColumn("language").asText(5));
        eb.field("active", f -> f.physicalColumn("active").required(true).asBool());
        eb.field("perSearch", f -> f.physicalColumn("per_search").required(true).asNumeric(3, 0));
        eb.field("lastSearchAt", f -> f.physicalColumn("last_search_at").asTemporal(TemporalRole.EVENT_TIME));
        eb.field("createdAt", f -> f.physicalColumn("created_at").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("createdBy", f -> f.physicalColumn("created_by").immutable(true).required(true).asText(128));
        eb.field("revision", f -> f.physicalColumn("revision").required(true).asNumeric(9, 0));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.display("name");
        eb.listView("default", lv -> lv
            .columns("owner", "name", "country", "language", "active", "perSearch", "createdAt")
            .filters("owner", "active")
            .sorts("name", "createdAt")
            .defaultSort("createdAt", false));
    });

    public static final EntityDefinition PROSPECT_ENTITY = EntityDefinition.define(PROSPECT, eb -> {
        eb.physicalTable("qb_prospect");
        eb.primaryKey("prospectId");
        eb.field("prospectId", f -> f.physicalColumn("prospect_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:prospect"));
        eb.field("owner", f -> f.physicalColumn("owner").immutable(true).required(true).asText(36));
        eb.field("directionId", f -> f.physicalColumn("direction_id").immutable(true).required(true).asText(36));
        eb.field("domain", f -> f.physicalColumn("domain").immutable(true).required(true).asText(253));
        eb.field("websiteUrl", f -> f.physicalColumn("website_url").required(true).asText(500));
        eb.field("company", f -> f.physicalColumn("company").asText(200));
        eb.field("status", f -> f.physicalColumn("status").required(true)
            .asCode(STATUS_DICTIONARY, STATUSES.toArray(String[]::new)));
        eb.field("foundAt", f -> f.physicalColumn("found_at").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("researchJobId", f -> f.physicalColumn("research_job_id").asText(36));
        eb.field("researchStartedAt", f -> f.physicalColumn("research_started_at")
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("researchedAt", f -> f.physicalColumn("researched_at").asTemporal(TemporalRole.EVENT_TIME));
        eb.field("language", f -> f.physicalColumn("language").asText(5));
        eb.field("country", f -> f.physicalColumn("country").asText(2));
        eb.field("sellsOnline", f -> f.physicalColumn("sells_online").asBool());
        eb.field("shopify", f -> f.physicalColumn("shopify").asBool());
        eb.field("publicEmail", f -> f.physicalColumn("public_email").asText(EMAIL_LENGTH));
        eb.field("refusesMarketing", f -> f.physicalColumn("refuses_marketing").asBool());
        eb.field("score", f -> f.physicalColumn("score").asNumeric(3, 0));
        eb.field("scoreReasons", f -> f.physicalColumn("score_reasons").asText(4000, true));
        eb.field("products", f -> f.physicalColumn("products").asText(8000, true));
        eb.field("trial", f -> f.physicalColumn("trial").asText(20_000, true));
        eb.field("mailSubject", f -> f.physicalColumn("mail_subject").asText(SUBJECT_LENGTH));
        eb.field("mailBody", f -> f.physicalColumn("mail_body").asText(BODY_LENGTH, true));
        eb.field("failure", f -> f.physicalColumn("failure").asText(500));
        // Opens the trial page and marks the sign-ups it brings: only for what went out, and random.
        eb.field("trialKey", f -> f.physicalColumn("trial_key").asText(64));
        eb.field("approvedAt", f -> f.physicalColumn("approved_at").asTemporal(TemporalRole.EVENT_TIME));
        eb.field("approvedBy", f -> f.physicalColumn("approved_by").asText(128));
        eb.field("sentAt", f -> f.physicalColumn("sent_at").asTemporal(TemporalRole.EVENT_TIME));
        eb.field("discardedAt", f -> f.physicalColumn("discarded_at").asTemporal(TemporalRole.EVENT_TIME));
        eb.field("discardedBy", f -> f.physicalColumn("discarded_by").asText(128));
        // Q48d: the sponsor the trial brought, and the one follow-up.
        eb.field("convertedUserId", f -> f.physicalColumn("converted_user_id").asText(128));
        eb.field("convertedAt", f -> f.physicalColumn("converted_at").asTemporal(TemporalRole.EVENT_TIME));
        eb.field("followedUpAt", f -> f.physicalColumn("followed_up_at").asTemporal(TemporalRole.EVENT_TIME));
        eb.field("revision", f -> f.physicalColumn("revision").required(true).asNumeric(9, 0));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.unique("uk_qb_prospect_domain", "owner", "domain");
        eb.display("domain");
        eb.listView("default", lv -> lv
            .columns("owner", "domain", "company", "status", "score", "foundAt", "sentAt")
            .filters("owner", "status", "foundAt", "sentAt")
            .sorts("foundAt", "score", "sentAt")
            .defaultSort("foundAt", false));
    });

    public static final EntityDefinition BLOCK_ENTITY = EntityDefinition.define(BLOCK, eb -> {
        eb.physicalTable("qb_prospect_block");
        eb.primaryKey("blockId");
        eb.field("blockId", f -> f.physicalColumn("block_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:prospect-block"));
        eb.field("kind", f -> f.physicalColumn("kind").immutable(true).required(true).asText(10));
        eb.field("value", f -> f.physicalColumn("value").immutable(true).required(true).asText(EMAIL_LENGTH));
        eb.field("reason", f -> f.physicalColumn("reason").immutable(true).required(true)
            .asCode(BLOCK_REASON_DICTIONARY, BLOCK_REASONS.toArray(String[]::new)));
        eb.field("owner", f -> f.physicalColumn("owner").immutable(true).required(true).asText(36));
        eb.field("note", f -> f.physicalColumn("note").immutable(true).asText(NOTE_LENGTH));
        eb.field("createdAt", f -> f.physicalColumn("created_at").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("createdBy", f -> f.physicalColumn("created_by").immutable(true).required(true).asText(128));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.unique("uk_qb_prospect_block", "kind", "value");
        eb.listView("default", lv -> lv
            .columns("kind", "value", "reason", "owner", "createdAt")
            .filters("kind", "reason", "owner", "createdAt")
            .sorts("createdAt")
            .defaultSort("createdAt", false));
    });

    public static final EntityDefinition USAGE_ENTITY = EntityDefinition.define(USAGE, eb -> {
        eb.physicalTable("qb_prospect_usage");
        eb.primaryKey("usageId");
        eb.field("usageId", f -> f.physicalColumn("usage_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:prospect-usage"));
        eb.field("owner", f -> f.physicalColumn("owner").immutable(true).required(true).asText(36));
        eb.field("month", f -> f.physicalColumn("month").immutable(true).required(true).asText(7));
        eb.field("inputTokens", f -> f.physicalColumn("input_tokens").required(true).asNumeric(14, 0));
        eb.field("outputTokens", f -> f.physicalColumn("output_tokens").required(true).asNumeric(14, 0));
        eb.field("searches", f -> f.physicalColumn("searches").required(true).asNumeric(9, 0));
        eb.field("jobs", f -> f.physicalColumn("jobs").required(true).asNumeric(9, 0));
        eb.field("costUsd", f -> f.physicalColumn("cost_usd").required(true).asNumeric(12, 4));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.unique("uk_qb_prospect_usage", "owner", "month");
        eb.listView("default", lv -> lv
            .columns("owner", "month", "inputTokens", "outputTokens", "searches", "jobs", "costUsd")
            .filters("owner", "month")
            .sorts("month")
            .defaultSort("month", false));
    });

    private QbProspects() {}
}
