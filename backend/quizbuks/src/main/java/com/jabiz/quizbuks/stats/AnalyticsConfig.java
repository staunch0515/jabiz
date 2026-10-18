package com.jabiz.quizbuks.stats;

import com.jabiz.context.RequestContext;
import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.job.JobDefinition;
import com.jabiz.job.JobItems;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.play.QbPlay;
import com.jabiz.quizbuks.publishing.QbPublications;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Registers the sponsors' analytics data (docs/quizbuks/plans/Q9e-analytics.md, Q9e1): the three entities and their
 * datasets, the job's own read-only datasets of the tables it reads in bulk, the sponsor's own cards for the templates,
 * the item process and the per-item job {@code qb.analytics}.
 *
 * <p>The analytics tables are read by administrators ({@code qb.admin.publication.read}, as the other statistics) and
 * written by the job only ({@code processOnlyWrites}; the write permission is one no role holds). Sponsors read them
 * only through the templates {@code qb.sponsor.dashboard} and {@code qb.sponsor.analytics.*}, which join the
 * sponsor's own publication cards and apply the minimum group: a dataset would give them the cursor's current totals.
 */
@Configuration
public class AnalyticsConfig {

    /** The sponsor's own publication cards: the analytics templates' scope (review M2), read by nobody else. */
    public static final String SPONSOR_CARDS = "urn:jabiz:dataset:sponsor:" + QbPublications.CARD;

    private final String poolRef;
    private final ProcessDefinition<AnalyticsJob.ItemInput, AnalyticsJob.ItemOutput, ProcessContext> item;
    private final int chunkSize;

    /**
     * @param chunkSize   publications per chunk ({@code quizbuks.analytics.chunk-size})
     * @param maxAttempts finished attempts one chunk reads per run ({@code quizbuks.analytics.max-attempts-per-chunk})
     * @param maxRecount  most completions a publication is recounted whole for ({@code quizbuks.analytics.max-recount})
     * @param zone        the deployment's zone ({@code jabiz.fiscal-year-zone}, the platform's; D-Q9e-3)
     */
    AnalyticsConfig(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef,
        @Value("${quizbuks.analytics.chunk-size:" + AnalyticsLimits.CHUNK_DEFAULT + "}") int chunkSize,
        @Value("${quizbuks.analytics.max-attempts-per-chunk:" + AnalyticsLimits.ATTEMPTS_DEFAULT + "}")
        int maxAttempts,
        @Value("${quizbuks.analytics.max-recount:" + AnalyticsLimits.RECOUNT_DEFAULT + "}") long maxRecount,
        @Value("${jabiz.fiscal-year-zone:UTC}") String zone) {
        if (chunkSize < 1 || chunkSize > AnalyticsLimits.CHUNK_MAX) {
            throw new IllegalArgumentException("quizbuks.analytics.chunk-size must be between 1 and "
                + AnalyticsLimits.CHUNK_MAX + ": " + chunkSize);
        }
        this.poolRef = poolRef;
        this.chunkSize = chunkSize;
        // An invalid zone is the platform's startup error (RETENTION), an offset AnalyticsZoneCheck's: UTC here so
        // that they are reported, not this.
        ZoneId zoneId;
        try {
            zoneId = ZoneId.of(zone);
        } catch (RuntimeException e) {
            zoneId = ZoneOffset.UTC;
        }
        this.item = AnalyticsJob.item(zoneId, maxAttempts, maxRecount);
    }

    private DatasetDefinition defaultDataset(String entity, int maxWriteBatch) {
        return DatasetDefinition.define(QbAnalytics.defaultDataset(entity), d -> d
            .targetEntityType(entity)
            .asDefault()
            .permissions(QbPermissions.ADMIN_PUBLICATION_READ, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.processOnlyWrites().maxWriteBatchSize(maxWriteBatch)
                .queryTimeout(AnalyticsLimits.QUERY_TIMEOUT))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    /** The job's own read of a table: read only, without history, with the job's time limit; nobody else reads it. */
    private DatasetDefinition jobDataset(String entity) {
        return DatasetDefinition.define(QbAnalytics.jobDataset(entity), d -> d
            .targetEntityType(entity)
            .permissions(QbPermissions.STATS_SYSTEM, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.readOnly(true).allowTimeTravel(false).queryTimeout(AnalyticsLimits.QUERY_TIMEOUT))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    @Bean
    EntityDefinition qbAnalyticsHourEntity() {
        return QbAnalytics.HOUR_ENTITY;
    }

    @Bean
    EntityDefinition qbAnalyticsCursorEntity() {
        return QbAnalytics.CURSOR_ENTITY;
    }

    @Bean
    EntityDefinition qbQuestionStatEntity() {
        return QbAnalytics.QUESTION_STAT_ENTITY;
    }

    /** A chunk writes a bucket per publication; a run deletes at most {@link AnalyticsLimits#PURGE_BATCH}. */
    @Bean
    DatasetDefinition qbAnalyticsHourDataset() {
        return defaultDataset(QbAnalytics.HOUR, Math.max(AnalyticsLimits.CHUNK_MAX, AnalyticsLimits.PURGE_BATCH));
    }

    @Bean
    DatasetDefinition qbAnalyticsCursorDataset() {
        return defaultDataset(QbAnalytics.CURSOR, AnalyticsLimits.CHUNK_MAX);
    }

    /** A chunk may show new figures for every question of every publication. */
    @Bean
    DatasetDefinition qbQuestionStatDataset() {
        return defaultDataset(QbAnalytics.QUESTION_STAT,
            AnalyticsLimits.CHUNK_MAX * com.jabiz.quizbuks.content.ContentLimits.MAX_QUESTIONS);
    }

    @Bean
    DatasetDefinition qbStatsAttemptDataset() {
        return jobDataset(QbPlay.ATTEMPT);
    }

    @Bean
    DatasetDefinition qbStatsCardDataset() {
        return jobDataset(QbPublications.CARD);
    }

    @Bean
    DatasetDefinition qbStatsViewDataset() {
        return jobDataset(QbPlay.VIEW);
    }

    /**
     * The caller's own publication cards ({@code sponsorId} = the caller), read only, for the analytics templates:
     * a plain table read through its sponsor index, where the sponsor's dataset of the temporal publications reads the
     * latest version of every publication on the platform before it filters (review M2). A card exists from the
     * publication's going on, which is when it has figures. Its read permission is one no role holds: the generic API
     * reads none of it; the templates read it with the caller's scope.
     */
    @Bean
    DatasetDefinition qbSponsorCardDataset() {
        return DatasetDefinition.define(SPONSOR_CARDS, d -> d
            .targetEntityType(QbPublications.CARD)
            .permissions(QbPermissions.STATS_SYSTEM, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.readOnly(true).allowTimeTravel(false))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef))
            .scope(s -> s.fromContext("sponsorId", "actorId", RequestContext::actorId)));
    }

    @Bean
    ProcessDefinition<AnalyticsJob.ItemInput, AnalyticsJob.ItemOutput, ProcessContext> qbAnalyticsItem() {
        return item;
    }

    /**
     * Every ten minutes at :00, :10, … (requirement 6.1: the analytics page says so), one transaction per item
     * (platform decision D42, review M3): the chunks of the publications to read, the recounts, and the run's record
     * last. A failing item is retried by later runs (the platform's backoff) and holds up nothing else; a missed run is
     * made up by the next, whose increments go to the previous run's hour marked as a gap. Only the end-to-end tests
     * run it more often ({@link AnalyticsLimits#RUN_CRON_SETTING}).
     */
    @Bean
    JobDefinition<AnalyticsJob.ItemInput> qbAnalyticsJob(
        @Value("${" + AnalyticsLimits.RUN_CRON_SETTING + ":" + AnalyticsLimits.RUN_CRON + "}") String cron) {
        return JobDefinition.forEach(AnalyticsJob.JOB, cron, ZoneOffset.UTC,
                JobItems.template(AnalyticsJob.ITEMS_TEMPLATE, at -> AnalyticsJob.itemParams(at, chunkSize),
                    "itemKey"),
                item, AnalyticsJob::input)
            .maxItems(JobDefinition.MAX_MAX_ITEMS);
    }
}
