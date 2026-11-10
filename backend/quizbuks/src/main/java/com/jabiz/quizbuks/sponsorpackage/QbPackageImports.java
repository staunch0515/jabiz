package com.jabiz.quizbuks.sponsorpackage;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;
import com.jabiz.quizbuks.identity.AccountLimits;

/**
 * The imports of sponsor packages (F-C4, docs/quizbuks/plans/impl/F-C4.md): {@value #IMPORT}, one row per import a
 * Sponsor starts: the package's hash, how it imports ({@value #FULL} or only the profile, {@value #PROFILE}), its state,
 * and every step done with what it made (a JSON object: step key to result). An ordinary table written only by the
 * package's processes ({@code processOnlyWrites}), every change audited by the platform; deleted after
 * {@code qb.package.import-retention-days} and with the Sponsor's account.
 */
public final class QbPackageImports {

    public static final String IMPORT = "QbPackageImport";
    public static final String DEFAULT_DATASET = "urn:jabiz:dataset:default:" + IMPORT;
    /** The Sponsor's own imports (read only): the console's "go on or give up". */
    public static final String MY_DATASET = "urn:jabiz:dataset:me:" + IMPORT;

    public static final String MODE_DICTIONARY = "urn:jabiz:dict:quizbuks:package-import-mode";
    public static final String STATUS_DICTIONARY = "urn:jabiz:dict:quizbuks:package-import-status";

    /** The whole package: profile, graphs, quizzes and publications. */
    public static final String FULL = "FULL";
    /** Only the profile part (Q100's import): an account that submitted a publication. */
    public static final String PROFILE = "PROFILE";

    public static final String RUNNING = "RUNNING";
    public static final String DONE = "DONE";
    public static final String ABANDONED = "ABANDONED";

    private QbPackageImports() {}

    /** A Sponsor's imports: started, stepped and given up one at a time. */
    public static String lockName(Object sponsorId) {
        return "qb.package-import:" + sponsorId;
    }

    public static final EntityDefinition ENTITY = EntityDefinition.define(IMPORT, eb -> {
        eb.physicalTable("qb_package_import");
        eb.primaryKey("importId");
        eb.field("importId", f -> f.physicalColumn("import_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:package-import"));
        eb.field("sponsorId", f -> f.physicalColumn("sponsor_id").immutable(true).required(true).processOnly()
            .asText(AccountLimits.USER_ID_LENGTH));
        eb.field("packageHash", f -> f.physicalColumn("package_hash").immutable(true).required(true).processOnly()
            .asText(PackageImportRules.HASH_LENGTH));
        eb.field("formatVersion", f -> f.physicalColumn("format_version").immutable(true).required(true)
            .processOnly().asNumeric(3, 0));
        eb.field("mode", f -> f.physicalColumn("mode").immutable(true).required(true).processOnly()
            .asCode(MODE_DICTIONARY, FULL, PROFILE));
        eb.field("status", f -> f.physicalColumn("status").required(true).processOnly()
            .asCode(STATUS_DICTIONARY, RUNNING, DONE, ABANDONED));
        // The questions a batch of a large bank holds, as the import started (a resumed import keeps its batches).
        eb.field("batchSize", f -> f.physicalColumn("batch_size").immutable(true).required(true).processOnly()
            .asNumeric(6, 0));
        eb.field("stepsTotal", f -> f.physicalColumn("steps_total").required(true).processOnly().asNumeric(6, 0));
        eb.field("stepsDone", f -> f.physicalColumn("steps_done").required(true).processOnly().asNumeric(6, 0));
        eb.field("lastStep", f -> f.physicalColumn("last_step").processOnly()
            .asText(PackageImportRules.STEP_LENGTH));
        // Every step done and what it made (ids only: no content, no secret).
        eb.field("done", f -> f.physicalColumn("done").required(true).processOnly().asText(null, true));
        eb.field("startedAt", f -> f.physicalColumn("started_at").immutable(true).required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("updatedAt", f -> f.physicalColumn("updated_at").required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("finishedAt", f -> f.physicalColumn("finished_at").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.listView("default", lv -> lv
            .columns("startedAt", "mode", "status", "stepsDone", "stepsTotal", "lastStep", "finishedAt")
            .filters("sponsorId", "status", "mode", "startedAt")
            .sorts("startedAt", "updatedAt")
            .defaultSort("startedAt", false));
    });
}
