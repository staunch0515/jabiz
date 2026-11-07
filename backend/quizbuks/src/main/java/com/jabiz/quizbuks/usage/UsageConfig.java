package com.jabiz.quizbuks.usage;

import com.jabiz.context.RequestContext;
import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.dictionary.StaticDictionary;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.job.JobDefinition;
import com.jabiz.job.JobItems;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.operator.OperatorScopes;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * Registers the metering of the sponsors' usage (F-C2): the snapshot table and its datasets (support and the
 * sponsor's operator read it, written by the job only; the sponsor reads their own), the meters' dictionary, the item
 * process and the daily job.
 */
@Configuration
public class UsageConfig {

    private final String poolRef;
    private final ZoneId zone;

    /**
     * @param zone the deployment's zone ({@code jabiz.fiscal-year-zone}): the days of the snapshots and the zone of the
     *             job's time of day
     */
    UsageConfig(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef,
        @Value("${jabiz.fiscal-year-zone:UTC}") String zone) {
        this.poolRef = poolRef;
        // An invalid zone is the platform's startup error (RETENTION): UTC here so that it is reported, not this.
        ZoneId zoneId;
        try {
            zoneId = ZoneId.of(zone);
        } catch (RuntimeException e) {
            zoneId = ZoneOffset.UTC;
        }
        this.zone = zoneId;
    }

    @Bean
    EntityDefinition qbUsageDayEntity() {
        return QbUsage.DAY_ENTITY;
    }

    /** A run writes a sponsor's meters of the day and deletes their rows past the retention. */
    @Bean
    DatasetDefinition qbUsageDayDataset() {
        return DatasetDefinition.define(QbUsage.DEFAULT_DATASET, d -> d
            .targetEntityType(QbUsage.DAY)
            .asDefault()
            .permissions(QbPermissions.ADMIN_PUBLICATION_READ, QbPermissions.SPONSOR_VIEW_WRITE)
            // Q59c-2: an operator's session sees its own sponsors' usage only (platform decision D64).
            .scope(OperatorScopes.of(QbUsage.DAY))
            .policy(p -> p.processOnlyWrites().maxWriteBatchSize(UsageSnapshot.MAX_OLD_ROWS
                + UsageMeters.ALL.size()))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    /** The sponsor's own snapshots; read only (the write permission is one no role holds). */
    @Bean
    DatasetDefinition qbMyUsageDayDataset() {
        return DatasetDefinition.define(QbUsage.MY_DATASET, d -> d
            .targetEntityType(QbUsage.DAY)
            .permissions(QbPermissions.SPONSOR_ME, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.readOnly(true).allowTimeTravel(false))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef))
            .scope(s -> s.fromContext("sponsorId", "actorId", RequestContext::actorId)));
    }

    /** The meters' names, from their one list. */
    @Bean
    StaticDictionary qbUsageMeterDictionary() {
        return StaticDictionary.define(QbUsage.METER_DICTIONARY, d -> {
            for (UsageMeters.Meter meter : UsageMeters.ALL) {
                d.item(meter.code(), "en", meter.names().get("en"), "zh", meter.names().get("zh"),
                    "ja", meter.names().get("ja"));
            }
        });
    }

    @Bean
    ProcessDefinition<UsageSnapshot.ItemInput, UsageSnapshot.ItemOutput, ProcessContext> qbUsageSnapshotItem() {
        return UsageSnapshot.item();
    }

    /**
     * Daily at {@value QbUsage#SNAPSHOT_TIME_SETTING} (default {@value QbUsage#SNAPSHOT_TIME_DEFAULT}) of the
     * deployment's zone, not a time of Japan written in UTC (the spec's §B): each sponsor one transaction of
     * {@value UsageSnapshot#ITEM}. A deployment setting, read at start (the requester, 2026-11-07); a time that is
     * not {@code HH:mm} fails the start. A day is run again by hand with {@code JobRunner} at that day's time
     * (operations.md §7.22): its rows are overwritten, never doubled.
     */
    @Bean
    JobDefinition<UsageSnapshot.ItemInput> qbUsageSnapshotJob(
        @Value("${" + QbUsage.SNAPSHOT_TIME_SETTING + ":" + QbUsage.SNAPSHOT_TIME_DEFAULT + "}") String time,
        ProcessDefinition<UsageSnapshot.ItemInput, UsageSnapshot.ItemOutput, ProcessContext> qbUsageSnapshotItem) {
        ZoneId days = zone;
        return JobDefinition.forEach(QbUsage.JOB, UsageRules.cron(time), days,
                JobItems.template(QbUsage.SPONSORS_TEMPLATE, scheduledTime -> Map.of(), "sponsorId"),
                qbUsageSnapshotItem, (scheduledTime, row) -> UsageSnapshot.input(scheduledTime, row, days))
            .maxItems(JobDefinition.MAX_MAX_ITEMS);
    }
}
