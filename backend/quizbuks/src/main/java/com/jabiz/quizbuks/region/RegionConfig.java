package com.jabiz.quizbuks.region;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.dataset.DatasetScope;
import com.jabiz.dictionary.StaticDictionary;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.job.JobDefinition;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.operator.OperatorScopes;
import com.jabiz.runtime.geo.ClientCountries;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.ZoneOffset;
import java.util.function.Consumer;

/** The rule on foreign videos and mainland China (F-S11): its entities, datasets, processes and jobs. */
@Configuration
public class RegionConfig {

    private final String poolRef;

    RegionConfig(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.poolRef = poolRef;
    }

    /** Read with {@code permission}; nobody writes but the processes. */
    private DatasetDefinition defaultDataset(String entity, String permission,
        Consumer<DatasetScope.Builder> scope) {
        return DatasetDefinition.define(QbRegion.defaultDataset(entity), d -> {
            d.targetEntityType(entity)
                .asDefault()
                .permissions(permission, QbPermissions.SPONSOR_VIEW_WRITE)
                .policy(p -> p.processOnlyWrites())
                .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef));
            if (scope != null) {
                d.scope(scope);
            }
        });
    }

    @Bean
    EntityDefinition qbRegionExceptionEntity() {
        return QbRegion.EXCEPTION_ENTITY;
    }

    @Bean
    EntityDefinition qbRegionRefusalDayEntity() {
        return QbRegion.REFUSAL_DAY_ENTITY;
    }

    @Bean
    DatasetDefinition qbRegionExceptionDataset() {
        // The requester, 2026-11-07: headquarters reads every exception, an operator its own sponsors' (by the sponsor
        // whose publications it covers, through the sponsor's operator now).
        return defaultDataset(QbRegion.EXCEPTION, QbPermissions.ADMIN_REGION_READ,
            OperatorScopes.of(QbRegion.EXCEPTION));
    }

    @Bean
    DatasetDefinition qbRegionRefusalDayDataset() {
        // Platform-wide counts with no sponsor: headquarters' only, so no operator's scope to declare.
        return defaultDataset(QbRegion.REFUSAL_DAY, QbPermissions.ADMIN_REGION_MONITOR, null);
    }

    /**
     * The exceptions not revoked, for the public template of a sponsor's mini homepage (Q60a): which publication or
     * sponsor, and the last day, never the reason or who decided. Read anonymously through that template only.
     */
    @Bean
    DatasetDefinition qbPublicRegionExceptionDataset() {
        return DatasetDefinition.define(QbRegion.PUBLIC_EXCEPTIONS, d -> d
            .targetEntityType(QbRegion.EXCEPTION)
            .scope(s -> s.fixed("revoked", false))
            .publicRead(p -> p.fields("exceptionId", "kind", "publicationId", "sponsorId", "expiresOn"))
            .permissions(QbPermissions.SPONSOR_VIEW_WRITE, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.readOnly(true).allowTimeTravel(false))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    @Bean
    StaticDictionary qbRegionExceptionKindDictionary() {
        return StaticDictionary.define(QbRegion.KIND_DICTIONARY, d -> d
            .item(QbRegion.PUBLICATION, "en", "Publication", "zh", "发布", "ja", "公開")
            .item(QbRegion.SPONSOR, "en", "Sponsor", "zh", "发起方", "ja", "主催者"));
    }

    @Bean
    ProcessDefinition<RegionExceptions.AddInput, RegionExceptions.ExceptionOutput, ProcessContext>
        qbRegionExceptionAdd() {
        return RegionExceptions.add();
    }

    @Bean
    ProcessDefinition<RegionExceptions.ExpiryInput, RegionExceptions.ExceptionOutput, ProcessContext>
        qbRegionExceptionExpirySet() {
        return RegionExceptions.expirySet();
    }

    @Bean
    ProcessDefinition<RegionExceptions.RevokeInput, RegionExceptions.ExceptionOutput, ProcessContext>
        qbRegionExceptionRevoke() {
        return RegionExceptions.revoke();
    }

    @Bean
    ProcessDefinition<RegionRefusals.FlushInput, RegionRefusals.FlushOutput, ProcessContext>
        qbRegionRefusalsFlush() {
        return RegionRefusals.flush();
    }

    @Bean
    JobDefinition<RegionRefusals.FlushInput> qbRegionRefusalsJob(RegionRefusals refusals,
        ProcessDefinition<RegionRefusals.FlushInput, RegionRefusals.FlushOutput, ProcessContext> qbRegionRefusalsFlush) {
        return JobDefinition.cron(RegionRefusals.JOB, RegionRefusals.CRON, ZoneOffset.UTC, qbRegionRefusalsFlush,
            scheduledTime -> new RegionRefusals.FlushInput(scheduledTime, refusals.drain()));
    }

    @Bean
    ProcessDefinition<RegionCheck.RunInput, RegionCheck.RunOutput, ProcessContext> qbRegionCheckRun(
        ClientCountries countries) {
        return RegionCheck.run(countries);
    }

    @Bean
    JobDefinition<RegionCheck.RunInput> qbRegionCheckJob(
        ProcessDefinition<RegionCheck.RunInput, RegionCheck.RunOutput, ProcessContext> qbRegionCheckRun) {
        return JobDefinition.cron(RegionCheck.JOB, RegionCheck.CRON, ZoneOffset.UTC, qbRegionCheckRun,
            RegionCheck.RunInput::new);
    }
}
