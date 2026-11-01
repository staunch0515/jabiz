package com.jabiz.quizbuks.certification;

import com.jabiz.context.RequestContext;
import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.dictionary.StaticDictionary;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.QbPermissions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The certifications (Q69, docs/quizbuks/plans/Q69-certifications.md): their three tables, dictionaries and processes.
 * The default datasets are headquarters' to read ({@code qb.certification.admin}); an operator reads its own through
 * {@code QB_CERTIFICATION_OVERVIEW}, a taker through {@code QB_CERTIFICATION_CLAIM}. Nobody writes through a dataset.
 */
@Configuration
public class CertificationConfig {

    private final String poolRef;

    CertificationConfig(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.poolRef = poolRef;
    }

    private DatasetDefinition defaultDataset(String entity) {
        return DatasetDefinition.define(QbCertifications.defaultDataset(entity), d -> d
            .targetEntityType(entity)
            .asDefault()
            .permissions(QbPermissions.CERTIFICATION_ADMIN, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.processOnlyWrites().maxWriteBatchSize(CertificationProcesses.MAX_ROWS))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    @Bean
    EntityDefinition qbCertificationRoundEntity() {
        return QbCertifications.ROUND_ENTITY;
    }

    @Bean
    DatasetDefinition qbCertificationRoundDataset() {
        return defaultDataset(QbCertifications.ROUND);
    }

    @Bean
    EntityDefinition qbCertificationRecordEntity() {
        return QbCertifications.RECORD_ENTITY;
    }

    @Bean
    DatasetDefinition qbCertificationRecordDataset() {
        return defaultDataset(QbCertifications.RECORD);
    }

    @Bean
    DatasetDefinition qbMyCertificationRecordDataset() {
        return DatasetDefinition.define(QbCertifications.MY_RECORD_DATASET, d -> d
            .targetEntityType(QbCertifications.RECORD)
            .permissions(QbPermissions.CERTIFICATION_ADMIN, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.readOnly(true).allowTimeTravel(false))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef))
            .scope(s -> s.fromContext("userId", "actorId", RequestContext::actorId)));
    }

    @Bean
    EntityDefinition qbOfficialPublisherEntity() {
        return QbCertifications.OFFICIAL_ENTITY;
    }

    @Bean
    DatasetDefinition qbOfficialPublisherDataset() {
        return defaultDataset(QbCertifications.OFFICIAL);
    }

    @Bean
    StaticDictionary qbCertificationDictionary() {
        return StaticDictionary.define(Certifications.DICTIONARY, d -> d
            .item(Certifications.SPONSOR_APPLY.key(), "en", "May apply to become a sponsor", "zh", "可申请成为商户",
                "ja", "スポンサー申請の資格")
            .item(Certifications.REFERRER.key(), "en", "May refer friends", "zh", "可推荐朋友", "ja", "友だち紹介の資格"));
    }

    @Bean
    StaticDictionary qbCertificationRecordKindDictionary() {
        return StaticDictionary.define(QbCertifications.RECORD_KIND_DICTIONARY, d -> d
            .item(CertificationHolding.PASSED, "en", "Passed", "zh", "答题合格", "ja", "合格")
            .item(CertificationHolding.GRANTED, "en", "Granted", "zh", "人工授予", "ja", "付与")
            .item(CertificationHolding.REVOKED, "en", "Revoked", "zh", "收回", "ja", "取り消し"));
    }

    @Bean
    ProcessDefinition<CertificationProcesses.OfficialInput, CertificationProcesses.OfficialOutput, ProcessContext>
        qbOfficialPublisherSet() {
        return CertificationProcesses.officialSet();
    }

    @Bean
    ProcessDefinition<CertificationProcesses.RoundInput, CertificationProcesses.RoundOutput, ProcessContext>
        qbCertificationRoundAdd() {
        return CertificationProcesses.roundAdd();
    }

    @Bean
    ProcessDefinition<CertificationProcesses.RoundRef, CertificationProcesses.RoundOutput, ProcessContext>
        qbCertificationRoundClose() {
        return CertificationProcesses.roundClose();
    }

    @Bean
    ProcessDefinition<CertificationProcesses.GrantInput, CertificationProcesses.GrantOutput, ProcessContext>
        qbCertificationGrant() {
        return CertificationProcesses.grant();
    }

    @Bean
    ProcessDefinition<CertificationProcesses.GrantInput, CertificationProcesses.GrantOutput, ProcessContext>
        qbCertificationRevoke() {
        return CertificationProcesses.revoke();
    }

    @Bean
    ProcessDefinition<CertificationProcesses.ClaimInput, CertificationProcesses.ClaimOutput, ProcessContext>
        qbCertificationClaim() {
        return CertificationProcesses.claim();
    }

    @Bean
    ProcessDefinition<CertificationProcesses.OverviewInput, CertificationProcesses.Overview, ProcessContext>
        qbCertificationOverview() {
        return CertificationProcesses.overview();
    }
}
