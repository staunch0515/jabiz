package com.jabiz.quizbuks.operator;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;
import com.jabiz.runtime.security.SecurityEntities;
import com.jabiz.quizbuks.content.QbContent;

import java.util.List;

/**
 * Operators (docs/quizbuks/plans/Q48-operators.md, Q48a): the agents who run sponsors in a back office of their own,
 * {@code /operator/} (sign-in entry {@value #ENTRY}, role {@code QB_OPERATOR}). Four ordinary tables, written by the
 * processes only ({@link OperatorProcesses}, {@link OperatorActs}):
 * <ul>
 *   <li>{@value #OPERATOR}: who the operator is, how its money is collected and whether it runs;</li>
 *   <li>{@value #MEMBER}: its staff, one operator at a time per person; a removed member's row stays;</li>
 *   <li>{@value #ASSIGNMENT}: which operator a sponsor belongs to; no row is headquarters ("direct", D-Q48-1);</li>
 *   <li>{@value #SPOT_CHECK}: headquarters' check of a review an operator decided (D-Q48-5).</li>
 * </ul>
 * Lock order: {@code qb.operator:<operator>} → {@code qb.operator-member:<user>} → the account, publication and
 * other locks of the act (an operator's acts hold the caller's membership shared for the process, so a removal takes
 * effect at the next act).
 */
public final class QbOperators {

    public static final String OPERATOR = "QbOperator";
    public static final String MEMBER = "QbOperatorMember";
    public static final String ASSIGNMENT = "QbSponsorOperator";
    /**
     * Who made an assignment by the sponsor's language (Q59c): the platform when they applied. A prospecting claim
     * (Q48d) replaces it; one by headquarters or a claim is never replaced by it.
     */
    public static final String BY_LANGUAGE = "auto:language";
    public static final String SPOT_CHECK = "QbReviewSpotCheck";

    /** The operators' sign-in entry ({@code jabiz.security.entries.operator}, {@code /operator/}). */
    public static final String ENTRY = "operator";

    public static final String STATUS_DICTIONARY = "urn:jabiz:dict:quizbuks:operator-status";
    public static final String COLLECTION_DICTIONARY = "urn:jabiz:dict:quizbuks:operator-collection";
    public static final String SPOT_CHECK_DICTIONARY = "urn:jabiz:dict:quizbuks:spot-check-result";

    /** The operator runs: its members sign in and act. */
    public static final String ACTIVE = "ACTIVE";
    /** The operator stopped (its contract ended, say): its members do not sign in; its sponsors stay its own. */
    public static final String SUSPENDED = "SUSPENDED";
    public static final List<String> STATUSES = List.of(ACTIVE, SUSPENDED);

    /** The platform collects the operator's sponsors' money and settles with it monthly (D-Q48-7). */
    public static final String PLATFORM = "PLATFORM";
    /** Later (D-Q48-8): the operator collects the money itself. Not offered yet. */
    public static final String DIRECT = "DIRECT";
    public static final List<String> COLLECTIONS = List.of(PLATFORM);

    /** Headquarters looked at the decision and found it right. */
    public static final String CHECK_OK = "OK";
    /** Headquarters found a problem: it acts itself (takes down, bans); the operator cannot undo that. */
    public static final String CHECK_PROBLEM = "PROBLEM";
    public static final List<String> CHECK_RESULTS = List.of(CHECK_OK, CHECK_PROBLEM);

    public static final int NAME_LENGTH = 200;
    public static final int CONTACT_NAME_LENGTH = 100;
    public static final int EMAIL_LENGTH = 254;
    public static final int NOTE_LENGTH = 2000;
    public static final int REASON_LENGTH = 500;
    /** Members of one operator at most (they are few, like a sponsor's staff, D-Q48-3). */
    public static final int MAX_MEMBERS = 50;

    /**
     * The entry scope of the operators' sessions (Q59c-2, platform decision D64): the operator the member works for.
     * The data an operator's permissions read is limited to it ({@link OperatorScopes}).
     */
    public static final String SCOPE = "operator";

    /** The lock of the languages of the operators: two active operators never take the same language (Q59c). */
    public static final String LANGUAGES_LOCK = "qb.operator.languages";

    /** The caller's own memberships (the operator's templates start from it). */
    public static final String OWN_MEMBERSHIP_DATASET = "urn:jabiz:dataset:operator:QbOperatorMember";

    public static String defaultDataset(String entity) {
        return "urn:jabiz:dataset:default:" + entity;
    }

    /** Taken exclusively by whoever changes the operator or its members. */
    public static String operatorLockName(Object operatorId) {
        return operatorId == null ? null : "qb.operator:" + operatorId;
    }

    /**
     * A person's membership: an operator's acts hold it shared for the process, adding and removing take it
     * exclusively, so a removal takes effect at the member's next act.
     */
    public static String memberLockName(Object userId) {
        return userId == null ? null : "qb.operator-member:" + userId;
    }

    /** Taken exclusively by the reassignment of a sponsor. */
    public static String assignmentLockName(Object sponsorId) {
        return sponsorId == null ? null : "qb.sponsor-operator:" + sponsorId;
    }

    public static final EntityDefinition OPERATOR_ENTITY = EntityDefinition.define(OPERATOR, eb -> {
        eb.physicalTable("qb_operator");
        eb.primaryKey("operatorId");
        eb.field("operatorId", f -> f.physicalColumn("operator_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:operator"));
        eb.field("name", f -> f.physicalColumn("name").required(true).asText(NAME_LENGTH));
        eb.field("contactName", f -> f.physicalColumn("contact_name").required(true).asText(CONTACT_NAME_LENGTH));
        eb.field("contactEmail", f -> f.physicalColumn("contact_email").required(true).asText(EMAIL_LENGTH));
        eb.field("collection", f -> f.physicalColumn("collection").required(true)
            .asCode(COLLECTION_DICTIONARY, PLATFORM, DIRECT));
        eb.field("status", f -> f.physicalColumn("status").required(true)
            .asCode(STATUS_DICTIONARY, STATUSES.toArray(String[]::new)));
        eb.field("note", f -> f.physicalColumn("note").asText(NOTE_LENGTH, true));
        // Q59c: the language whose new sponsors it takes (one active operator a language); none: by hand only.
        eb.field("language", f -> f.physicalColumn("language")
            .asCode(QbContent.LANGUAGE_DICTIONARY, QbContent.LANGUAGES.toArray(String[]::new)));
        eb.field("createdAt", f -> f.physicalColumn("created_at").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("createdBy", f -> f.physicalColumn("created_by").immutable(true).required(true).asText(128));
        eb.field("revision", f -> f.physicalColumn("revision").required(true).asNumeric(9, 0));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.display("name");
        eb.listView("default", lv -> lv
            .columns("name", "language", "contactName", "contactEmail", "collection", "status", "createdAt")
            .filters("name", "status", "createdAt")
            .sorts("name", "createdAt")
            .defaultSort("createdAt", false));
    });

    public static final EntityDefinition MEMBER_ENTITY = EntityDefinition.define(MEMBER, eb -> {
        eb.physicalTable("qb_operator_member");
        eb.primaryKey("memberId");
        eb.field("memberId", f -> f.physicalColumn("member_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:operator-member"));
        eb.field("operatorId", f -> f.physicalColumn("operator_id").immutable(true).required(true).asText(36));
        // A platform user, as a reference: the sign-in guard's read binds the user id as a UUID (as QbUserBan's).
        eb.field("userId", f -> f.physicalColumn("user_id").immutable(true).required(true)
            .asReference(SecurityEntities.USER));
        eb.field("lead", f -> f.physicalColumn("lead").required(true).asBool());
        eb.field("operatorActive", f -> f.physicalColumn("operator_active").required(true).asBool());
        eb.field("addedAt", f -> f.physicalColumn("added_at").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("addedBy", f -> f.physicalColumn("added_by").immutable(true).required(true).asText(128));
        eb.field("removedAt", f -> f.physicalColumn("removed_at").asTemporal(TemporalRole.EVENT_TIME));
        eb.field("removedBy", f -> f.physicalColumn("removed_by").asText(128));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.listView("default", lv -> lv
            .columns("operatorId", "userId", "lead", "addedAt", "removedAt")
            .filters("operatorId", "userId", "lead", "addedAt", "removedAt")
            .sorts("addedAt")
            .defaultSort("addedAt", false));
    });

    public static final EntityDefinition ASSIGNMENT_ENTITY = EntityDefinition.define(ASSIGNMENT, eb -> {
        eb.physicalTable("qb_sponsor_operator");
        eb.primaryKey("assignmentId");
        eb.field("assignmentId", f -> f.physicalColumn("assignment_id").immutable(true).required(true)
            .generated(true).asSemanticIdentity("urn:jabiz:entity:quizbuks:sponsor-operator"));
        eb.field("userId", f -> f.physicalColumn("user_id").immutable(true).required(true).asText(128));
        eb.field("operatorId", f -> f.physicalColumn("operator_id").required(true).asText(36));
        eb.field("assignedAt", f -> f.physicalColumn("assigned_at").required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("assignedBy", f -> f.physicalColumn("assigned_by").required(true).asText(128));
        eb.field("reason", f -> f.physicalColumn("reason").required(true).asText(REASON_LENGTH));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.unique("uk_qb_sponsor_operator_user", "userId");
        eb.listView("default", lv -> lv
            .columns("userId", "operatorId", "assignedAt", "assignedBy", "reason")
            .filters("userId", "operatorId", "assignedAt")
            .sorts("assignedAt")
            .defaultSort("assignedAt", false));
    });

    public static final EntityDefinition SPOT_CHECK_ENTITY = EntityDefinition.define(SPOT_CHECK, eb -> {
        eb.physicalTable("qb_review_spot_check");
        eb.primaryKey("checkId");
        eb.field("checkId", f -> f.physicalColumn("check_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:review-spot-check"));
        eb.field("decisionId", f -> f.physicalColumn("decision_id").immutable(true).required(true).asText(36));
        eb.field("result", f -> f.physicalColumn("result").required(true)
            .asCode(SPOT_CHECK_DICTIONARY, CHECK_RESULTS.toArray(String[]::new)));
        eb.field("note", f -> f.physicalColumn("note").asText(NOTE_LENGTH, true));
        eb.field("checkedAt", f -> f.physicalColumn("checked_at").required(true).asTemporal(TemporalRole.EVENT_TIME));
        eb.field("checkedBy", f -> f.physicalColumn("checked_by").required(true).asText(128));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.unique("uk_qb_review_spot_check_decision", "decisionId");
        eb.listView("default", lv -> lv
            .columns("decisionId", "result", "note", "checkedAt", "checkedBy")
            .filters("decisionId", "result", "checkedAt")
            .sorts("checkedAt")
            .defaultSort("checkedAt", false));
    });

    private QbOperators() {}
}
