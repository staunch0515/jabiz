package com.jabiz.quizbuks.exposure;

import com.jabiz.context.RequestContext;
import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.job.JobDefinition;
import com.jabiz.job.JobItems;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.operator.OperatorScopes;
import com.jabiz.quizbuks.publishing.PublicationExposure;
import com.jabiz.quizbuks.publishing.PublicationOutput;
import com.jabiz.quizbuks.publishing.PublishingLimits;
import com.jabiz.runtime.process.steps.CallProcess;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

/**
 * Registers the exposures and the pushes (F-S13, docs/quizbuks/plans/impl/F-S13.md): the entity, its default dataset
 * (written by the report only, read directly by no role: templates give the sponsors their own figures and the
 * administrators the suspicious accounts, an operator's session its own sponsors' rows only), the caller's own rows for
 * the feed's pushes, the report, the purchased exposures' sub-process for F-M8, and the two jobs: the minute's end of
 * what used up its purchase (one transaction per publication, platform decision D42) and the daily reconciliation.
 */
@Configuration
public class ExposureConfig {

    /** The job ending the publications whose successes reached their purchased exposures. */
    public static final String ENDS_JOB = "qb.exposure-ends";

    private static final ProcessDefinition<PublicationExposure.EndInput, PublicationOutput, ProcessContext> END =
        PublicationExposure.end();

    private final String poolRef;
    /** The deployment's zone ({@code jabiz.fiscal-year-zone}): the days of the daily cap and of the daily check. */
    private final ZoneId zone;

    ExposureConfig(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef,
        @Value("${jabiz.fiscal-year-zone:UTC}") String zone) {
        this.poolRef = poolRef;
        // An invalid zone is the platform's startup error (RETENTION): UTC here so that it is reported, not this.
        ZoneId zoneId;
        try {
            zoneId = ZoneId.of(zone);
        } catch (java.time.DateTimeException e) {
            zoneId = ZoneOffset.UTC;
        }
        this.zone = zoneId;
    }

    @Bean
    EntityDefinition qbExposureEntity() {
        return QbExposures.ENTITY;
    }

    @Bean
    DatasetDefinition qbExposureDataset() {
        return DatasetDefinition.define(QbExposures.DEFAULT_DATASET, d -> d
            .targetEntityType(QbExposures.EXPOSURE)
            .asDefault()
            .permissions(QbPermissions.SPONSOR_VIEW_WRITE, QbPermissions.SPONSOR_VIEW_WRITE)
            .scope(OperatorScopes.of(QbExposures.EXPOSURE))
            .policy(p -> p.processOnlyWrites())
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    /** The caller's own rows (read only), for the feed's pushes: the ones they opened are not pushed again. */
    @Bean
    DatasetDefinition qbMyExposureDataset() {
        return DatasetDefinition.define(QbExposures.MY_DATASET, d -> d
            .targetEntityType(QbExposures.EXPOSURE)
            .permissions(QbPermissions.PLAY, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.readOnly(true).allowTimeTravel(false))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef))
            .scope(s -> s.fromContext("userId", "actorId", RequestContext::actorId)));
    }

    @Bean
    ProcessDefinition<ExposureProcesses.ReportInput, ExposureProcesses.ReportOutput, ProcessContext> qbExposureReport() {
        return ExposureProcesses.report(zone);
    }

    /** The only writer of a publication's purchased exposures: a sub-process for F-M8 (decision A of 2026-11-09). */
    @Bean
    ProcessDefinition<PublicationExposure.PurchasedInput, PublicationOutput, ProcessContext>
        qbExposurePurchasedSet() {
        return PublicationExposure.purchasedSet();
    }

    /** F-M8: the sponsor buys more exposures for a publication that bought some. */
    @Bean
    ProcessDefinition<com.jabiz.quizbuks.publishing.PublicationExposureTopUp.TopUpInput,
        com.jabiz.quizbuks.publishing.PublicationExposureTopUp.TopUpOutput, ProcessContext> qbExposureTopUp() {
        return com.jabiz.quizbuks.publishing.PublicationExposureTopUp.topUp();
    }

    @Bean
    ProcessDefinition<PublicationExposure.EndInput, PublicationOutput, ProcessContext> qbPublicationExposureEnd() {
        return END;
    }

    /**
     * Every minute, each publication on whose successes reached its purchased exposures ends, in a transaction of its
     * own (platform decision D42): the reports take no publication's lock, and what they count meanwhile is recorded but
     * not charged.
     */
    @Bean
    JobDefinition<PublicationExposure.EndInput> qbExposureEndsJob() {
        return JobDefinition.forEach(ENDS_JOB, "0 * * * * *", ZoneOffset.UTC,
                JobItems.template(PublicationExposure.ENDS_TEMPLATE, scheduledTime -> Map.of(), "publicationId"),
                END, (scheduledTime, row) -> new PublicationExposure.EndInput(
                    UUID.fromString(String.valueOf(row.get("publicationId")))))
            .maxItems(PublishingLimits.TICK_BATCH);
    }

    /**
     * The end-to-end tests' purchase ({@value #E2E_PURCHASE}): only with their switch {@code quizbuks.e2e.enabled}
     * ({@code QB_E2E_ENABLED}, refused next to Stripe's live mode by {@code TransferScheduleCheck}), a root that runs
     * the purchase's sub-process as F-M8's purchase will, so that a test can use exposures up. Not there otherwise.
     */
    public static final String E2E_PURCHASE = "QB_E2E_EXPOSURE_PURCHASE";

    @Configuration
    @ConditionalOnProperty(name = "quizbuks.e2e.enabled", havingValue = "true")
    static class EndToEnd {

        @Bean
        ProcessDefinition<PublicationExposure.PurchasedInput, PublicationOutput, ProcessContext> qbE2eExposurePurchase() {
            return ProcessDefinition.define(E2E_PURCHASE, 1, PublicationExposure.PurchasedInput.class,
                PublicationOutput.class, ProcessContext.class, pb -> pb
                    .description("End-to-end tests only: sets the exposures bought for a publication.")
                    .permissions(QbPermissions.ADMIN_CONTENT_WRITE)
                    .contextFactory((start, input) -> {
                        ProcessContext ctx = new ProcessContext(start);
                        ctx.put("input", input);
                        return ctx;
                    })
                    .outputMapper(ctx -> ctx.get("output", PublicationOutput.class))
                    .step("Buy", CallProcess.<ProcessContext>of(PublicationExposure.PURCHASED_SET, 1,
                        ctx -> ctx.get("input"), "output")));
        }
    }

    @Bean
    ProcessDefinition<ExposureCheck.RunInput, ExposureCheck.RunOutput, ProcessContext> qbExposureCheckRun() {
        return ExposureCheck.run(zone);
    }

    @Bean
    JobDefinition<ExposureCheck.RunInput> qbExposureCheckJob(
        ProcessDefinition<ExposureCheck.RunInput, ExposureCheck.RunOutput, ProcessContext> qbExposureCheckRun) {
        return JobDefinition.cron(ExposureCheck.JOB, ExposureCheck.CRON, ZoneOffset.UTC, qbExposureCheckRun,
            ExposureCheck.RunInput::new);
    }
}
