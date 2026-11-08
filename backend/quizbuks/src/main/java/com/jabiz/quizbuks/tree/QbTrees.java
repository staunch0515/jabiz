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

    public static String defaultDataset(String entity) {
        return "urn:jabiz:dataset:default:" + entity;
    }

    public static final String OWNER_DICTIONARY = "urn:jabiz:dict:quizbuks:tree-owner";
    public static final String SUBJECT_DICTIONARY = "urn:jabiz:dict:quizbuks:tree-subject";
    public static final String STATUS_DICTIONARY = "urn:jabiz:dict:quizbuks:tree-status";
    public static final String VERSION_STATUS_DICTIONARY = "urn:jabiz:dict:quizbuks:tree-version-status";

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

    private QbTrees() {}
}
