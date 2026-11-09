package com.jabiz.quizbuks.help;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;
import com.jabiz.entity.i18n.I18nText;
import com.jabiz.file.FileKind;

import java.util.List;

/**
 * What the AI help answers from (F-C10b, docs/quizbuks/plans/impl/F-C10.md §6):
 * <ul>
 *   <li>{@value #MANUAL}: the usage manual of an entry (the Takers' app, the Sponsors' pages) in the three languages,
 *       edited by the content administrators in the management system like the legal texts' drafts and published by
 *       {@code QB_HELP_PUBLISH} (the draft copied into {@code publishedBody}); {@code QB_SETUP} creates the first drafts
 *       from the repository's texts ({@link HelpManualDrafts}). Temporal: the platform keeps and audits each version;</li>
 *   <li>{@value #MATERIAL}: the snapshot of one entry's and language's materials, the published FAQ, terms and privacy
 *       policy in effect and the published manual ({@link HelpMaterialsText}), stored as a text file of the policy
 *       {@value #POLICY} and given to each {@value HelpJob#TYPE} job of that entry and language as its file input.
 *       Written by {@code QB_HELP_MATERIALS_BUILD} and the outcomes of the job {@value HelpMaterialsJob#TYPE} only.</li>
 * </ul>
 */
public final class QbHelpMaterials {

    public static final String MANUAL = "QbHelpManual";
    public static final String MATERIAL = "QbHelpMaterial";

    /** The file policy of the snapshots: text only, read by those who ask the help (the job's requester). */
    public static final String POLICY = "qb.help.materials";

    /** The event of a published manual or materials to rebuild ({@code QB_HELP_MATERIALS_BUILD} consumes it). */
    public static final String CHANGED_EVENT = "qb.help.materials-changed";

    /** The languages a published manual must have: every language the help answers in. */
    public static final List<String> LANGUAGES = QbHelp.LANGUAGES;

    /** The longest manual in one language, in characters (the parameter limits the whole snapshot in bytes). */
    public static final int MANUAL_LENGTH = 100_000;
    public static final int HASH_LENGTH = 64;

    /**
     * The largest snapshot ever stored, in UTF-8 bytes, whatever {@code qb.help.manual-max-bytes} says: the job that
     * stores it carries it in its input, at most the platform's 1 MiB of JSON, and its escapes need room.
     */
    public static final int MAX_FILE_BYTES = 800 * 1024;

    /** The lock of the snapshots: builds and their outcomes take turns. */
    public static final String LOCK = "qb.help.materials";

    public static String defaultDataset(String entity) {
        return "urn:jabiz:dataset:default:" + entity;
    }

    /** The reference of a snapshot's job: the entry and the language. */
    public static String reference(String entry, String language) {
        return entry + "/" + language;
    }

    public static final EntityDefinition MANUAL_ENTITY = EntityDefinition.define(MANUAL, eb -> {
        eb.physicalTable("qb_help_manual_version");
        eb.primaryKey("manualId");
        eb.field("manualId", f -> f.physicalColumn("manual_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:help-manual"));
        eb.field("entry", f -> f.physicalColumn("entry").immutable(true).required(true)
            .asCode(QbHelp.ENTRY_DICTIONARY, QbHelp.ENTRIES.toArray(String[]::new)));
        // The draft: any language may be missing while it is edited; publishing needs all three.
        eb.field("body", f -> f.physicalColumn("body").apply(I18nText.markdown(MANUAL_LENGTH)));
        eb.field("publishedBody", f -> f.physicalColumn("published_body").processOnly()
            .apply(I18nText.markdown(MANUAL_LENGTH)));
        eb.field("publishedAt", f -> f.physicalColumn("published_at").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("sourceHash", f -> f.physicalColumn("source_hash").processOnly().asText(HASH_LENGTH));
        eb.unique("uk_qb_help_manual_entry", "entry");
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("entry", "publishedAt")
            .filters("entry")
            .sorts("entry", "publishedAt")
            .defaultSort("entry", true));
    });

    public static final EntityDefinition MATERIAL_ENTITY = EntityDefinition.define(MATERIAL, eb -> {
        eb.physicalTable("qb_help_material");
        eb.primaryKey("materialId");
        eb.field("materialId", f -> f.physicalColumn("material_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:help-material"));
        eb.field("entry", f -> f.physicalColumn("entry").immutable(true).required(true).processOnly()
            .asCode(QbHelp.ENTRY_DICTIONARY, QbHelp.ENTRIES.toArray(String[]::new)));
        eb.field("language", f -> f.physicalColumn("language").immutable(true).required(true).processOnly()
            .asText(5));
        eb.field("fileId", f -> f.physicalColumn("file_id").processOnly().kind(FileKind.of(POLICY)));
        eb.field("contentHash", f -> f.physicalColumn("content_hash").processOnly().asText(HASH_LENGTH));
        eb.field("bytes", f -> f.physicalColumn("bytes").processOnly().asNumeric(9, 0));
        eb.field("builtAt", f -> f.physicalColumn("built_at").processOnly().asTemporal(TemporalRole.EVENT_TIME));
        eb.field("latestHash", f -> f.physicalColumn("latest_hash").required(true).processOnly()
            .asText(HASH_LENGTH));
        eb.field("latestBytes", f -> f.physicalColumn("latest_bytes").required(true).processOnly()
            .asNumeric(9, 0));
        eb.field("latestAt", f -> f.physicalColumn("latest_at").required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("pendingHash", f -> f.physicalColumn("pending_hash").processOnly().asText(HASH_LENGTH));
        eb.field("pendingAt", f -> f.physicalColumn("pending_at").processOnly().asTemporal(TemporalRole.EVENT_TIME));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.unique("uk_qb_help_material_language", "entry", "language");
        eb.listView("default", lv -> lv
            .columns("entry", "language", "bytes", "builtAt", "latestBytes", "latestAt", "pendingAt")
            .filters("entry", "language")
            .sorts("entry", "language", "builtAt")
            .defaultSort("entry", true));
    });

    private QbHelpMaterials() {}
}
