package com.jabiz.quizbuks.tree;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;
import com.jabiz.entity.i18n.I18nText;

import java.util.List;

/**
 * Knowledge graphs (F-S2, docs/quizbuks/plans/impl/F-S2.md §2): a graph (the platform's template, an operator's copy
 * of one, or a Sponsor's own), its versions (a draft, under review, in effect, superseded or sent back), its nodes
 * (a node keeps its id across versions, and an operator's copy keeps the template's ids, so that what a Sponsor lit
 * still counts after a move) and each version's definition of each node (its name and condition). Ordinary tables
 * written by {@link TreeProcesses} only; a version in effect is never changed again.
 */
public final class QbTrees {

    public static final String TREE = "QbTree";
    public static final String VERSION = "QbTreeVersion";
    public static final String NODE = "QbTreeNode";
    public static final String NODE_DEF = "QbTreeNodeDef";
    /** Which nodes a node's condition in effect names (F-S2b1): who to evaluate when a node is lit. */
    public static final String NODE_REF = "QbTreeNodeRef";
    /** A node lit by a person or a Sponsor (F-S2b1, written once). */
    public static final String LIT = "QbTreeLit";
    /** A lit record revoked (F-S2b, written once; the revocation itself comes in F-S2b2). */
    public static final String REVOCATION = "QbTreeLitRevocation";

    /** A request to revoke a lit record, reviewed by another member (F-S2b2). */
    public static final String REVOKE_REQUEST = "QbTreeLitRevokeRequest";

    /** A person's own lit records and revocations ({@code me:}). */
    public static final String MY_LITS = "urn:jabiz:dataset:me:" + LIT;
    public static final String MY_REVOCATIONS = "urn:jabiz:dataset:me:" + REVOCATION;

    public static String defaultDataset(String entity) {
        return "urn:jabiz:dataset:default:" + entity;
    }

    public static final String OWNER_DICTIONARY = "urn:jabiz:dict:quizbuks:tree-owner";
    public static final String SUBJECT_DICTIONARY = "urn:jabiz:dict:quizbuks:tree-subject";
    public static final String STATUS_DICTIONARY = "urn:jabiz:dict:quizbuks:tree-status";
    public static final String VERSION_STATUS_DICTIONARY = "urn:jabiz:dict:quizbuks:tree-version-status";
    public static final String LIT_SUBJECT_DICTIONARY = "urn:jabiz:dict:quizbuks:tree-lit-subject";
    public static final String REVOKE_STATUS_DICTIONARY = "urn:jabiz:dict:quizbuks:tree-revoke-status";

    /** A revocation request's states: in review, then revoked or rejected (or withdrawn by a new request, never). */
    public static final String REVOKE_REVIEW = "REVIEW";
    public static final String REVOKE_DONE = "REVOKED";
    public static final String REVOKE_REJECTED = "REJECTED";


    /** The platform's template (no owner id). */
    public static final String PLATFORM = "PLATFORM";
    /** An operator's copy of a template whose nodes Sponsors light. */
    public static final String OPERATOR = "OPERATOR";
    /** A Sponsor's own graph, which Takers light. */
    public static final String SPONSOR = "SPONSOR";
    public static final List<String> OWNERS = List.of(PLATFORM, OPERATOR, SPONSOR);

    /** Who lights the nodes. */
    public static final String TAKER = "TAKER";
    public static final List<String> SUBJECTS = List.of(TAKER, SPONSOR);

    /** Who lit a node: a person (a Taker) or a Sponsor (from F-S4); both ids are user ids. */
    public static final String USER = "USER";
    public static final List<String> LIT_SUBJECTS = List.of(USER, SPONSOR);

    public static final String ACTIVE = "ACTIVE";
    public static final String RETIRED = "RETIRED";

    public static final String DRAFT = "DRAFT";
    public static final String REVIEW = "REVIEW";
    public static final String IN_EFFECT = "ACTIVE";
    public static final String SUPERSEDED = "SUPERSEDED";
    public static final String REJECTED = "REJECTED";
    public static final List<String> VERSION_STATUSES = List.of(DRAFT, REVIEW, IN_EFFECT, SUPERSEDED, REJECTED);

    public static final int NAME_LENGTH = 120;
    public static final int DESCRIPTION_LENGTH = 1000;
    public static final int REASON_LENGTH = 500;
    public static final int ID_LENGTH = 36;
    public static final int OWNER_LENGTH = 128;

    /** Editing a graph (its drafts, review, retirement): one process at a time, before any publication's lock. */
    public static String lockName(Object treeId) {
        return treeId == null ? null : "qb.tree:" + treeId;
    }

    /** Lighting a subject's nodes (F-S2b1): never held with a publication's, start or wallet lock. */
    public static String litLockName(String subjectKind, Object subjectId) {
        return "qb.tree-lit:" + subjectKind + ":" + subjectId;
    }

    /** Creating an owner's graphs (their number is limited), before {@link #lockName}. */
    public static String ownerLockName(String ownerKind, Object ownerId) {
        return "qb.tree-owner:" + ownerKind + ":" + (ownerId == null ? "" : ownerId);
    }

    public static final EntityDefinition TREE_ENTITY = EntityDefinition.define(TREE, eb -> {
        eb.physicalTable("qb_tree");
        eb.primaryKey("treeId");
        eb.field("treeId", f -> f.physicalColumn("tree_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:tree"));
        eb.field("ownerKind", f -> f.physicalColumn("owner_kind").immutable(true).required(true)
            .asCode(OWNER_DICTIONARY, OWNERS.toArray(String[]::new)));
        // An operator's id or a Sponsor's user id; none for the platform's templates.
        eb.field("ownerId", f -> f.physicalColumn("owner_id").immutable(true).asText(OWNER_LENGTH));
        eb.field("subjectKind", f -> f.physicalColumn("subject_kind").immutable(true).required(true)
            .asCode(SUBJECT_DICTIONARY, SUBJECTS.toArray(String[]::new)));
        // An operator's copy: the template it copies and the template's version it has taken up to.
        eb.field("templateTreeId", f -> f.physicalColumn("template_tree_id").immutable(true).asText(ID_LENGTH));
        eb.field("templateVersionNo", f -> f.physicalColumn("template_version_no").processOnly().asNumeric(6, 0));
        eb.field("currentVersionId", f -> f.physicalColumn("current_version_id").processOnly().asText(ID_LENGTH));
        eb.field("status", f -> f.physicalColumn("status").required(true).processOnly()
            .asCode(STATUS_DICTIONARY, ACTIVE, RETIRED));
        eb.field("createdAt", f -> f.physicalColumn("created_at").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("createdBy", f -> f.physicalColumn("created_by").immutable(true).required(true)
            .asText(OWNER_LENGTH));
        eb.field("retiredAt", f -> f.physicalColumn("retired_at").processOnly().asTemporal(TemporalRole.EVENT_TIME));
        // Raised by every change to the graph or its draft: a save names the revision it read.
        eb.field("revision", f -> f.physicalColumn("revision").required(true).processOnly().asNumeric(9, 0));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.listView("default", lv -> lv
            .columns("ownerKind", "ownerId", "subjectKind", "templateTreeId", "status", "createdAt")
            .filters("ownerKind", "ownerId", "subjectKind", "status", "createdAt")
            .sorts("createdAt")
            .defaultSort("createdAt", false));
    });

    public static final EntityDefinition VERSION_ENTITY = EntityDefinition.define(VERSION, eb -> {
        eb.physicalTable("qb_tree_version");
        eb.primaryKey("versionId");
        eb.field("versionId", f -> f.physicalColumn("version_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:tree-version"));
        eb.field("treeId", f -> f.physicalColumn("tree_id").immutable(true).required(true).asText(ID_LENGTH));
        eb.field("versionNo", f -> f.physicalColumn("version_no").immutable(true).required(true).asNumeric(6, 0));
        eb.field("name", f -> f.physicalColumn("name").required(true).apply(I18nText.of(NAME_LENGTH)));
        eb.field("description", f -> f.physicalColumn("description")
            .apply(I18nText.of(DESCRIPTION_LENGTH).multiline()));
        eb.field("status", f -> f.physicalColumn("status").required(true).processOnly()
            .asCode(VERSION_STATUS_DICTIONARY, VERSION_STATUSES.toArray(String[]::new)));
        eb.field("contentHash", f -> f.physicalColumn("content_hash").processOnly().asText(64));
        eb.field("approvalRequestId", f -> f.physicalColumn("approval_request_id").processOnly().asText(64));
        eb.field("submittedAt", f -> f.physicalColumn("submitted_at").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("submittedBy", f -> f.physicalColumn("submitted_by").processOnly().asText(OWNER_LENGTH));
        eb.field("decidedAt", f -> f.physicalColumn("decided_at").processOnly().asTemporal(TemporalRole.EVENT_TIME));
        // F-S2b2: when a version waiting too long for its review was reminded (once).
        eb.field("remindedAt", f -> f.physicalColumn("reminded_at").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("rejectReason", f -> f.physicalColumn("reject_reason").processOnly().asText(REASON_LENGTH, true));
        eb.field("editedAt", f -> f.physicalColumn("edited_at").required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.unique("uk_qb_tree_version_no", "treeId", "versionNo");
        eb.display("name");
        eb.listView("default", lv -> lv
            .columns("treeId", "versionNo", "name", "status", "submittedAt", "decidedAt")
            .filters("treeId", "status", "submittedAt")
            .sorts("versionNo", "submittedAt")
            .defaultSort("versionNo", false));
    });

    public static final EntityDefinition NODE_ENTITY = EntityDefinition.define(NODE, eb -> {
        eb.physicalTable("qb_tree_node");
        eb.primaryKey("rowId");
        eb.field("rowId", f -> f.physicalColumn("row_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:tree-node"));
        eb.field("treeId", f -> f.physicalColumn("tree_id").immutable(true).required(true).asText(ID_LENGTH));
        // The node's id: kept across versions, and an operator's copy keeps the template's.
        eb.field("nodeId", f -> f.physicalColumn("node_id").immutable(true).required(true).asText(ID_LENGTH));
        eb.field("createdInVersion", f -> f.physicalColumn("created_in_version").immutable(true).required(true)
            .asNumeric(6, 0));
        // Whether the version in effect has the node (a removed node keeps its row: lit records name it).
        eb.field("active", f -> f.physicalColumn("active").required(true).processOnly().asBool());
        // The version in effect's nodes to be lit before answering, as a JSON array (read by a start, F-S2b).
        eb.field("beforeAnswer", f -> f.physicalColumn("before_answer").processOnly().asText(null, true));
        // Whether the version in effect's condition asks for a pass: only then may publications be bound to it.
        eb.field("bindable", f -> f.physicalColumn("bindable").required(true).processOnly().asBool());
        // F-S2b1: the version in effect's condition (TreeCondition#toMap as JSON), which lighting evaluates.
        eb.field("condition", f -> f.physicalColumn("condition").processOnly().asText(null, true));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.unique("uk_qb_tree_node", "treeId", "nodeId");
        eb.listView("default", lv -> lv
            .columns("treeId", "nodeId", "active", "bindable", "createdInVersion")
            .filters("treeId", "nodeId", "active")
            .sorts("createdInVersion")
            .defaultSort("createdInVersion", true));
    });

    public static final EntityDefinition NODE_DEF_ENTITY = EntityDefinition.define(NODE_DEF, eb -> {
        eb.physicalTable("qb_tree_node_def");
        eb.primaryKey("rowId");
        eb.field("rowId", f -> f.physicalColumn("row_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:tree-node-def"));
        eb.field("versionId", f -> f.physicalColumn("version_id").immutable(true).required(true).asText(ID_LENGTH));
        eb.field("nodeId", f -> f.physicalColumn("node_id").immutable(true).required(true).asText(ID_LENGTH));
        eb.field("name", f -> f.physicalColumn("name").required(true).apply(I18nText.of(NAME_LENGTH)));
        eb.field("description", f -> f.physicalColumn("description")
            .apply(I18nText.of(DESCRIPTION_LENGTH).multiline()));
        // The condition as JSON (TreeCondition#toMap).
        eb.field("condition", f -> f.physicalColumn("condition").required(true).asText(null, true));
        eb.field("seq", f -> f.physicalColumn("seq").required(true).asNumeric(4, 0));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.unique("uk_qb_tree_node_def", "versionId", "nodeId");
        eb.listView("default", lv -> lv
            .columns("versionId", "nodeId", "name", "seq")
            .filters("versionId", "nodeId")
            .sorts("seq")
            .defaultSort("seq", true));
    });

    public static final EntityDefinition NODE_REF_ENTITY = EntityDefinition.define(NODE_REF, eb -> {
        eb.physicalTable("qb_tree_node_ref");
        eb.primaryKey("rowId");
        eb.field("rowId", f -> f.physicalColumn("row_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:tree-node-ref"));
        eb.field("treeId", f -> f.physicalColumn("tree_id").immutable(true).required(true).asText(ID_LENGTH));
        eb.field("nodeId", f -> f.physicalColumn("node_id").immutable(true).required(true).asText(ID_LENGTH));
        // A node the condition names, in this graph or another it may name.
        eb.field("refNodeId", f -> f.physicalColumn("ref_node_id").immutable(true).required(true)
            .asText(ID_LENGTH));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.unique("uk_qb_tree_node_ref", "treeId", "nodeId", "refNodeId");
        eb.listView("default", lv -> lv
            .columns("treeId", "nodeId", "refNodeId")
            .filters("treeId", "nodeId", "refNodeId")
            .sorts("nodeId")
            .defaultSort("nodeId", true));
    });

    public static final EntityDefinition LIT_ENTITY = EntityDefinition.define(LIT, eb -> {
        eb.physicalTable("qb_tree_lit_version");
        eb.primaryKey("litId");
        eb.field("litId", f -> f.physicalColumn("lit_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:tree-lit"));
        eb.field("subjectKind", f -> f.physicalColumn("subject_kind").immutable(true).required(true)
            .asCode(LIT_SUBJECT_DICTIONARY, LIT_SUBJECTS.toArray(String[]::new)));
        eb.field("subjectId", f -> f.physicalColumn("subject_id").immutable(true).required(true)
            .asText(OWNER_LENGTH));
        eb.field("treeId", f -> f.physicalColumn("tree_id").immutable(true).required(true).asText(ID_LENGTH));
        eb.field("nodeId", f -> f.physicalColumn("node_id").immutable(true).required(true).asText(ID_LENGTH));
        eb.field("litAt", f -> f.physicalColumn("lit_at").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        // What lit it, as JSON: the attempt passed, or the node whose lighting made the condition hold.
        eb.field("basis", f -> f.physicalColumn("basis").immutable(true).asText(null, true));
        eb.temporal(t -> t.allowScheduled(false).writeOnce());
        eb.listView("default", lv -> lv
            .columns("subjectKind", "subjectId", "treeId", "nodeId", "litAt")
            .filters("subjectKind", "subjectId", "treeId", "nodeId")
            .sorts("litAt")
            .defaultSort("litAt", false));
    });

    public static final EntityDefinition REVOCATION_ENTITY = EntityDefinition.define(REVOCATION, eb -> {
        eb.physicalTable("qb_tree_lit_revocation_version");
        eb.primaryKey("revocationId");
        eb.field("revocationId", f -> f.physicalColumn("revocation_id").immutable(true).required(true)
            .generated(true).asSemanticIdentity("urn:jabiz:entity:quizbuks:tree-lit-revocation"));
        eb.field("litId", f -> f.physicalColumn("lit_id").immutable(true).required(true).asText(ID_LENGTH));
        // The lit record's subject again, so that a start reads a person's lit records and revocations at once.
        eb.field("subjectKind", f -> f.physicalColumn("subject_kind").immutable(true).required(true)
            .asCode(LIT_SUBJECT_DICTIONARY, LIT_SUBJECTS.toArray(String[]::new)));
        eb.field("subjectId", f -> f.physicalColumn("subject_id").immutable(true).required(true)
            .asText(OWNER_LENGTH));
        eb.field("reason", f -> f.physicalColumn("reason").immutable(true).required(true)
            .asText(REASON_LENGTH, true));
        eb.field("requestedBy", f -> f.physicalColumn("requested_by").immutable(true).required(true)
            .asText(OWNER_LENGTH));
        eb.field("approvalRequestId", f -> f.physicalColumn("approval_request_id").immutable(true)
            .asText(ID_LENGTH));
        eb.field("revokedAt", f -> f.physicalColumn("revoked_at").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.temporal(t -> t.allowScheduled(false).writeOnce());
        eb.listView("default", lv -> lv
            .columns("litId", "subjectKind", "subjectId", "revokedAt")
            .filters("litId", "subjectKind", "subjectId")
            .sorts("revokedAt")
            .defaultSort("revokedAt", false));
    });

    public static final EntityDefinition REVOKE_REQUEST_ENTITY = EntityDefinition.define(REVOKE_REQUEST, eb -> {
        eb.physicalTable("qb_tree_lit_revoke_request");
        eb.primaryKey("requestId");
        eb.field("requestId", f -> f.physicalColumn("request_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:tree-lit-revoke-request"));
        eb.field("litId", f -> f.physicalColumn("lit_id").immutable(true).required(true).processOnly()
            .asText(ID_LENGTH));
        eb.field("subjectKind", f -> f.physicalColumn("subject_kind").immutable(true).required(true).processOnly()
            .asCode(LIT_SUBJECT_DICTIONARY, LIT_SUBJECTS.toArray(String[]::new)));
        eb.field("subjectId", f -> f.physicalColumn("subject_id").immutable(true).required(true).processOnly()
            .asText(OWNER_LENGTH));
        eb.field("treeId", f -> f.physicalColumn("tree_id").immutable(true).required(true).processOnly()
            .asText(ID_LENGTH));
        eb.field("nodeId", f -> f.physicalColumn("node_id").immutable(true).required(true).processOnly()
            .asText(ID_LENGTH));
        // The operator whose members review it (the graph's Sponsor's or the copy's); none: the platform's.
        eb.field("operatorId", f -> f.physicalColumn("operator_id").immutable(true).processOnly()
            .asText(ID_LENGTH));
        eb.field("reason", f -> f.physicalColumn("reason").immutable(true).required(true).processOnly()
            .asText(REASON_LENGTH, true));
        eb.field("status", f -> f.physicalColumn("status").required(true).processOnly()
            .asCode(REVOKE_STATUS_DICTIONARY, REVOKE_REVIEW, REVOKE_DONE, REVOKE_REJECTED));
        eb.field("requestedBy", f -> f.physicalColumn("requested_by").immutable(true).required(true).processOnly()
            .asText(OWNER_LENGTH));
        eb.field("requestedAt", f -> f.physicalColumn("requested_at").immutable(true).required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("approvalRequestId", f -> f.physicalColumn("approval_request_id").processOnly().asText(ID_LENGTH));
        eb.field("decidedAt", f -> f.physicalColumn("decided_at").processOnly().asTemporal(TemporalRole.EVENT_TIME));
        eb.field("rejectReason", f -> f.physicalColumn("reject_reason").processOnly().asText(REASON_LENGTH, true));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.stateTransitions("status", st -> st
            .initial(REVOKE_REVIEW)
            .from(REVOKE_REVIEW).to(REVOKE_DONE, REVOKE_REJECTED));
        eb.listView("default", lv -> lv
            .columns("subjectId", "treeId", "nodeId", "status", "requestedBy", "requestedAt")
            .filters("subjectId", "treeId", "nodeId", "status", "operatorId", "requestedAt")
            .sorts("requestedAt")
            .defaultSort("requestedAt", false));
    });

    private QbTrees() {}
}
