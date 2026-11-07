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

    // ---- Q88a: the sponsors' own certifications --------------------------------------------------------------------

    @Bean
    EntityDefinition qbSponsorCertificationEntity() {
        return QbSponsorCertifications.ENTITY;
    }

    /** Headquarters reads every sponsor's; an operator its sponsors' (the scope); nobody writes through it. */
    @Bean
    DatasetDefinition qbSponsorCertificationDataset() {
        return DatasetDefinition.define(QbSponsorCertifications.DEFAULT_DATASET, d -> d
            .targetEntityType(QbSponsorCertifications.CERTIFICATION)
            .asDefault()
            .scope(com.jabiz.quizbuks.operator.OperatorScopes.of(QbSponsorCertifications.CERTIFICATION))
            .permissions(QbPermissions.CERTIFICATION_ADMIN, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.processOnlyWrites().maxWriteBatchSize(SponsorCertifications.MAX_PER_SPONSOR))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    /** The sponsor's own, read only (its write permission is no role's). */
    @Bean
    DatasetDefinition qbMySponsorCertificationDataset() {
        return DatasetDefinition.define(QbSponsorCertifications.SPONSOR_DATASET, d -> d
            .targetEntityType(QbSponsorCertifications.CERTIFICATION)
            .permissions(QbPermissions.PUBLICATION_WRITE, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.readOnly(true).allowTimeTravel(false))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef))
            .scope(s -> s.fromContext("sponsorId", "actorId", RequestContext::actorId)));
    }

    /**
     * What the public pages show of a sponsor's certification (the verify page, the public certification page, the
     * sponsor's page: its name and whose it is), through the public templates only.
     */
    @Bean
    DatasetDefinition qbPublicSponsorCertificationDataset() {
        return DatasetDefinition.define(QbSponsorCertifications.PUBLIC_DATASET, d -> d
            .targetEntityType(QbSponsorCertifications.CERTIFICATION)
            .publicRead(p -> p.allRows().fields("certificationId", "sponsorId", "name", "status"))
            .permissions(QbPermissions.SPONSOR_VIEW_WRITE, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.readOnly(true).allowTimeTravel(false))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    @Bean
    StaticDictionary qbSponsorCertificationStatusDictionary() {
        return StaticDictionary.define(QbSponsorCertifications.STATUS_DICTIONARY, d -> d
            .item(QbSponsorCertifications.ACTIVE, "en", "Active", "zh", "启用", "ja", "有効")
            .item(QbSponsorCertifications.RETIRED, "en", "Retired", "zh", "已停用", "ja", "終了"));
    }

    @Bean
    ProcessDefinition<SponsorCertificationProcesses.CreateInput, SponsorCertificationProcesses.Output, ProcessContext>
        qbSponsorCertificationCreate() {
        return SponsorCertificationProcesses.create();
    }

    @Bean
    ProcessDefinition<SponsorCertificationProcesses.UpdateInput, SponsorCertificationProcesses.Output, ProcessContext>
        qbSponsorCertificationUpdate() {
        return SponsorCertificationProcesses.update();
    }

    @Bean
    ProcessDefinition<SponsorCertificationProcesses.Ref, SponsorCertificationProcesses.Output, ProcessContext>
        qbSponsorCertificationRetire() {
        return SponsorCertificationProcesses.retire();
    }

    @Bean
    StaticDictionary qbCertificationDictionary() {
        return StaticDictionary.define(Certifications.DICTIONARY, d -> d
            .item(Certifications.SPONSOR_APPLY.key(), "en", "May apply to become a sponsor", "zh", "可申请成为发起方",
                "ja", "主催者申請の資格")
            .item(Certifications.REFERRER.key(), "en", "May refer friends", "zh", "可推荐朋友", "ja", "友だち紹介の資格")
            .item(Certifications.BENEFIT_PROVIDER.key(), "en", "May provide sponsored benefits", "zh", "可提供赞助权益",
                "ja", "協賛特典の提供の資格")
            .item(Certifications.BENEFIT_SPONSOR.key(), "en", "May sponsor others' benefits", "zh", "可赞助他人权益",
                "ja", "他社特典の協賛の資格"));
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
