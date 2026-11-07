package com.jabiz.quizbuks.usage;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;
import com.jabiz.quizbuks.identity.AccountLimits;

/**
 * The metering of what the sponsors occupy (F-C2, docs/quizbuks/plans/impl/F-C2.md): the daily snapshot
 * {@code QbUsageDay} (one row per sponsor and day, every meter in it), an ordinary table written by {@link UsageSnapshot} only, and the names
 * of the job, templates and event around it. Only metered, shown and noticed: nothing here refuses an upload.
 */
public final class QbUsage {

    public static final String DAY = "QbUsageDay";

    public static final String DEFAULT_DATASET = "urn:jabiz:dataset:default:" + DAY;
    public static final String MY_DATASET = "urn:jabiz:dataset:me:" + DAY;

    public static final String METER_DICTIONARY = "urn:jabiz:dict:quizbuks:usage-meter";

    /** The daily job: one item per sponsor, each a transaction of {@value UsageSnapshot#ITEM}. */
    public static final String JOB = "qb.usage-snapshot";
    /** The job's items: every sponsor with an onboarding record (an erased one has none). */
    public static final String SPONSORS_TEMPLATE = "qb.system.usage-sponsors";
    /** One sponsor's meters now: one row per meter of {@link UsageMeters#ALL}, in one statement. */
    public static final String MEASURE_TEMPLATE = "qb.system.usage-measure";
    /** The sponsor's card on their asset library: the latest snapshot and the free amount. */
    public static final String SPONSOR_TEMPLATE = "qb.sponsor.usage";
    /** The size and the places of each of the sponsor's assets (sorting by size, "unused" filter). */
    public static final String ASSET_STATS_TEMPLATE = "qb.sponsor.asset-stats";
    /** The administrators' and operators' months: days, average, peak and last value by sponsor and meter. */
    public static final String ADMIN_TEMPLATE = "qb.admin.usage";

    /** A sponsor's storage went above the free amount (from at or below it, or from no snapshot). */
    public static final String FREE_EXCEEDED_EVENT = "qb.usage.free-exceeded";

    /** The time of day the job runs, in {@code jabiz.fiscal-year-zone}: a deployment setting (restart to change). */
    public static final String SNAPSHOT_TIME_SETTING = "quizbuks.usage.snapshot-time";
    public static final String SNAPSHOT_TIME_DEFAULT = "03:17";

    /** The longest {@code meters} JSON: every meter with a 19-digit value (ample room for meters to come). */
    public static final int METERS_LENGTH = 4_000;

    public static final EntityDefinition DAY_ENTITY = EntityDefinition.define(DAY, eb -> {
        eb.physicalTable("qb_usage_day");
        eb.primaryKey("usageDayId");
        eb.field("usageDayId", f -> f.physicalColumn("usage_day_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:usage-day"));
        eb.field("sponsorId", f -> f.physicalColumn("sponsor_id").immutable(true).required(true)
            .asText(AccountLimits.USER_ID_LENGTH));
        // The day of jabiz.fiscal-year-zone the snapshot is of (the job's scheduled time there).
        eb.field("day", f -> f.physicalColumn("snapshot_day").immutable(true).required(true).asDate());
        // Every meter of the day: a JSON object {meter: value} with exactly the keys of UsageMeters (UsageRules.json);
        // one row a day, so one audit record a day (D27).
        eb.field("meters", f -> f.physicalColumn("meters").required(true).asText(METERS_LENGTH));
        // When the day's row was last written: a run again of the same day overwrites the values.
        eb.field("recordedAt", f -> f.physicalColumn("recorded_at").required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.unique("uk_qb_usage_day", "sponsorId", "day");
        eb.listView("default", lv -> lv
            .columns("sponsorId", "day", "meters", "recordedAt")
            .filters("sponsorId", "day")
            .sorts("day")
            .defaultSort("day", false));
    });

    private QbUsage() {}
}
