package com.jabiz.quizbuks.certification;

import com.jabiz.context.RequestContext;
import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.document.DocumentLayout;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.QbPermissions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The beans of certificates (Q77): the entity, its datasets, the document layout and the two processes. */
@Configuration
public class CertificateConfig {

    private final String poolRef;

    CertificateConfig(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.poolRef = poolRef;
    }

    @Bean
    EntityDefinition qbCertificateEntity() {
        return QbCertificates.ENTITY;
    }

    /** Written by the issue only; support reads the certificates a user issued (the admin's user page). */
    @Bean
    DatasetDefinition qbCertificateDataset() {
        return DatasetDefinition.define(QbCertificates.DATASET, d -> d
            .targetEntityType(QbCertificates.CERTIFICATE)
            .asDefault()
            .permissions(QbPermissions.ADMIN_USERS_READ, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.processOnlyWrites())
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    /** The caller's own certificates, read only: the App and the certificate's layout read them. */
    @Bean
    DatasetDefinition qbMyCertificateDataset() {
        return DatasetDefinition.define(QbCertificates.MY_DATASET, d -> d
            .targetEntityType(QbCertificates.CERTIFICATE)
            .permissions(QbPermissions.ME, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.readOnly(true).allowTimeTravel(false))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef))
            .scope(s -> s.fromContext("userId", "actorId", RequestContext::actorId)));
    }

    /**
     * What the verify page and the public certification page show of a certificate, through the public templates only
     * (which project the whitelist; the dataset API is closed to everybody). The key is random: without it, nothing.
     */
    @Bean
    DatasetDefinition qbPublicCertificateDataset() {
        return DatasetDefinition.define(QbCertificates.PUBLIC_DATASET, d -> d
            .targetEntityType(QbCertificates.CERTIFICATE)
            .publicRead(p -> p.allRows().fields("certificateId", "userId", "certification", "certificationVersion",
                "heldFrom", "validUntil", "holderName", "verifyKey", "issuedAt"))
            .permissions(QbPermissions.SPONSOR_VIEW_WRITE, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.readOnly(true).allowTimeTravel(false))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    /** Revocations only, for a certificate's state: who, which certification and when (no reason, no actor). */
    @Bean
    DatasetDefinition qbPublicCertificationRevocationDataset() {
        return DatasetDefinition.define(QbCertificates.PUBLIC_RECORD_DATASET, d -> d
            .targetEntityType(QbCertifications.RECORD)
            .scope(s -> s.fixed("kind", CertificationHolding.REVOKED))
            .publicRead(p -> p.fields("userId", "certification", "kind", "at"))
            .permissions(QbPermissions.SPONSOR_VIEW_WRITE, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.readOnly(true).allowTimeTravel(false))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    /**
     * The certificate (D-Q77-2): issued by its holder (the permission every taker holds; the platform shows a reader
     * only the documents they issued), its subject the certificate row, its data the holder's own certificate.
     */
    @Bean
    DocumentLayout qbCertificateLayout() {
        return DocumentLayout.define(QbCertificates.LAYOUT_ID, d -> d
            .permissions(QbPermissions.ME)
            .subject(QbCertificates.CERTIFICATE, "certificateId")
            .number(QbCertificates.LAYOUT_TEMPLATE, "certificateNo")
            .party("issuer", QbCertificates.ISSUER_TEMPLATE, "name")
            .party("holder", QbCertificates.LAYOUT_TEMPLATE, "holderName")
            .facts(QbCertificates.LAYOUT_TEMPLATE, "certificationName", "heldFrom", "validUntil", "verifyAddress")
            .note("note"));
    }

    @Bean
    ProcessDefinition<CertificateProcesses.MineInput, CertificateProcesses.MineOutput, ProcessContext>
        qbMyCertifications() {
        return CertificateProcesses.mine();
    }

    @Bean
    ProcessDefinition<CertificateProcesses.IssueInput, CertificateProcesses.IssueOutput, ProcessContext>
        qbCertificateIssue() {
        return CertificateProcesses.issue();
    }
}
