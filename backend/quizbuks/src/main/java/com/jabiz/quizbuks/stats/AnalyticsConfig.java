package com.jabiz.quizbuks.stats;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.job.JobDefinition;
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
 * datasets, the job's own read-only datasets of the tables it reads in bulk, the two processes and the job
 * {@code qb.analytics}.
 *
 * <p>The analytics tables are read by administrators ({@code qb.admin.publication.read}, as the other statistics) and
 * written by the job only ({@code processOnlyWrites}; the write permission is one no role holds). Sponsors read them
 * only through the templates {@code qb.sponsor.dashboard} and {@code qb.sponsor.analytics.*}, which join the
 * sponsor's own publications and apply the minimum group: a dataset would give them the cursor's current totals.
 */
@Configuration
public class AnalyticsConfig {

    private final String poolRef;
    private final ProcessDefinition<AnalyticsRefresh.RefreshInput, AnalyticsRefresh.RefreshOutput, ProcessContext>
        refresh;
    private final int maxAttempts;

    /**
     * @param chunkSize   publications per chunk ({@code quizbuks.analytics.chunk-size})
     * @param maxAttempts finished attempts one chunk reads per run ({@code quizbuks.analytics.max-attempts-per-chunk})
     * @param zone        the deployment's zone ({@code jabiz.fiscal-year-zone}, the platform's; D-Q9e-3)
     */
    AnalyticsConfig(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef,
        @Value("${quizbuks.analytics.chunk-size:" + AnalyticsLimits.CHUNK_DEFAULT + "}") int chunkSize,
        @Value("${quizbuks.analytics.max-attempts-per-chunk:" + AnalyticsLimits.ATTEMPTS_DEFAULT + "}")
        int maxAttempts,
        @Value("${jabiz.fiscal-year-zone:UTC}") String zone) {
        this.poolRef = poolRef;
        // An invalid zone is the platform's startup error (RETENTION); UTC here so that it is reported, not this.
        ZoneId zoneId;
        try {
            zoneId = ZoneId.of(zone);
        } catch (RuntimeException e) {
            zoneId = ZoneOffset.UTC;
        }
        this.refresh = AnalyticsRefresh.refresh(chunkSize, zoneId);
        this.maxAttempts = maxAttempts;
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

    @Bean
    ProcessDefinition<AnalyticsRefresh.RefreshInput, AnalyticsRefresh.RefreshOutput, ProcessContext>
        qbAnalyticsRefresh() {
        return refresh;
    }

    @Bean
    ProcessDefinition<AnalyticsRefresh.ChunkInput, AnalyticsRefresh.ChunkOutput, ProcessContext>
        qbAnalyticsChunk() {
        return AnalyticsRefresh.chunk(maxAttempts);
    }

    /**
     * Every ten minutes at :00, :10, … (requirement 6.1: the analytics page says so). One run is one transaction of
     * reads and the analytics tables' writes, without business locks; a missed run is made up by the next, whose
     * increments go to the previous run's hour marked as a gap. Only the end-to-end tests run it more often
     * ({@link AnalyticsLimits#RUN_CRON_SETTING}).
     */
    @Bean
    JobDefinition<AnalyticsRefresh.RefreshInput> qbAnalyticsJob(
        @Value("${" + AnalyticsLimits.RUN_CRON_SETTING + ":" + AnalyticsLimits.RUN_CRON + "}") String cron) {
        return JobDefinition.cron(AnalyticsRefresh.JOB, cron, ZoneOffset.UTC, refresh,
            AnalyticsRefresh.RefreshInput::new);
    }
}
