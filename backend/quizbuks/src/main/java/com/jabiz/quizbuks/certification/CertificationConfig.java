package com.jabiz.quizbuks.certification;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.dictionary.StaticDictionary;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.quizbuks.QbPermissions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * What is left of the certifications (Q69, Q88a) after F-S2c2b (D1): the records and the Sponsors' certifications,
 * read only, for the certificates issued before (D8: those stay valid, verifiable and downloadable). Nothing writes
 * them; the knowledge map's nodes replace the certifications and their rounds.
 */
@Configuration
public class CertificationConfig {

    private final String poolRef;

    CertificationConfig(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.poolRef = poolRef;
    }

    @Bean
    EntityDefinition qbCertificationRecordEntity() {
        return QbCertifications.RECORD_ENTITY;
    }

    /** Read by the certificates' processes (a revocation ends a certificate); headquarters reads it; nobody writes. */
    @Bean
    DatasetDefinition qbCertificationRecordDataset() {
        return DatasetDefinition.define(QbCertifications.defaultDataset(QbCertifications.RECORD), d -> d
            .targetEntityType(QbCertifications.RECORD)
            .asDefault()
            .permissions(QbPermissions.TREES_READ, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.readOnly(true).allowTimeTravel(false))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    @Bean
    StaticDictionary qbCertificationRecordKindDictionary() {
        return StaticDictionary.define(QbCertifications.RECORD_KIND_DICTIONARY, d -> d
            .item(QbCertifications.PASSED, "en", "Passed", "zh", "答题合格", "ja", "合格")
            .item(QbCertifications.GRANTED, "en", "Granted", "zh", "人工授予", "ja", "付与")
            .item(QbCertifications.REVOKED, "en", "Revoked", "zh", "收回", "ja", "取り消し"));
    }

    // ---- Q88a: the sponsors' own certifications, read only since F-S2c2 (D6) ---------------------------------------
    // Nobody defines one any more; the rows defined before stay for the certificates issued of them (D8: those stay
    // valid) and their verification and public pages.

    @Bean
    EntityDefinition qbSponsorCertificationEntity() {
        return QbSponsorCertifications.ENTITY;
    }

    /** Headquarters (the content administrators) reads every sponsor's; an operator its sponsors' (the scope); nobody writes. */
    @Bean
    DatasetDefinition qbSponsorCertificationDataset() {
        return DatasetDefinition.define(QbSponsorCertifications.DEFAULT_DATASET, d -> d
            .targetEntityType(QbSponsorCertifications.CERTIFICATION)
            .asDefault()
            .scope(com.jabiz.quizbuks.operator.OperatorScopes.of(QbSponsorCertifications.CERTIFICATION))
            .permissions(QbPermissions.TREES_READ, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.readOnly(true).allowTimeTravel(false))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
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
}
