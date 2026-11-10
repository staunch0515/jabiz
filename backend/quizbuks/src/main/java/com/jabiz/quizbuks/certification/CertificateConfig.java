package com.jabiz.quizbuks.certification;

import com.jabiz.context.RequestContext;
import com.jabiz.i18n.MessageCatalog;
import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.document.DocumentLayout;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.tree.QbTrees;
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

    /**
     * The caller's own certificates as a Taker, read only: the App and the certificate's layout read them (a person who
     * is also a Sponsor does not see their Sponsor's certificates here, F-S5a).
     */
    @Bean
    DatasetDefinition qbMyCertificateDataset() {
        return DatasetDefinition.define(QbCertificates.MY_DATASET, d -> d
            .targetEntityType(QbCertificates.CERTIFICATE)
            .permissions(QbPermissions.ME, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.readOnly(true).allowTimeTravel(false))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef))
            .scope(s -> s.fromContext("userId", "actorId", RequestContext::actorId)
                .fixed("subjectKind", QbCertificates.USER)));
    }

    /** F-S5a: the caller's own certificates as a Sponsor (the nodes they lit), read only. */
    @Bean
    DatasetDefinition qbMySponsorCertificateDataset() {
        return DatasetDefinition.define(QbCertificates.MY_SPONSOR_DATASET, d -> d
            .targetEntityType(QbCertificates.CERTIFICATE)
            .permissions(QbPermissions.SPONSOR_ME, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.readOnly(true).allowTimeTravel(false))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef))
            .scope(s -> s.fromContext("userId", "actorId", RequestContext::actorId)
                .fixed("subjectKind", QbCertificates.SPONSOR)));
    }

    /**
     * What the verify page and the public certification page show of a certificate, through the public templates only
     * (which project the whitelist; the dataset API is closed to everybody). The key is random: without it, nothing.
     */
    @Bean
    DatasetDefinition qbPublicCertificateDataset() {
        return DatasetDefinition.define(QbCertificates.PUBLIC_DATASET, d -> d
            .targetEntityType(QbCertificates.CERTIFICATE)
            // F-S5a: the node's certificate adds its source, names, issuer, the lit record (the revocation's key; not a
            // result of any template), the kind of holder (a Sponsor's is on no public page) and whether it is shared.
            .publicRead(p -> p.allRows().fields("certificateId", "userId", "certification", "certificationVersion",
                "heldFrom", "validUntil", "holderName", "verifyKey", "issuedAt", "source", "subjectKind", "litId",
                "certificateNames", "issuerName", "shared"))
            .permissions(QbPermissions.SPONSOR_VIEW_WRITE, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.readOnly(true).allowTimeTravel(false))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    /** Revocations only, for a certificate's state: who, which certification and when (no reason, no actor). */
    @Bean
    DatasetDefinition qbPublicCertificationRevocationDataset() {
        return DatasetDefinition.define(QbCertificates.PUBLIC_RECORD_DATASET, d -> d
            .targetEntityType(QbCertifications.RECORD)
            .scope(s -> s.fixed("kind", QbCertifications.REVOKED))
            .publicRead(p -> p.fields("userId", "certification", "kind", "at"))
            .permissions(QbPermissions.SPONSOR_VIEW_WRITE, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.readOnly(true).allowTimeTravel(false))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    /**
     * F-S5a: which lit record was revoked when, for a node's certificate's state (no reason, no requester, no subject:
     * the record's id is the key the certificate holds).
     */
    @Bean
    DatasetDefinition qbPublicTreeLitRevocationDataset() {
        return DatasetDefinition.define(QbCertificates.PUBLIC_REVOCATION_DATASET, d -> d
            .targetEntityType(QbTrees.REVOCATION)
            .publicRead(p -> p.allRows().fields("litId", "revokedAt"))
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

    /**
     * F-S5a: a node's certificate, the holder's alone to read (the platform shows a reader only the documents they
     * issued). Its data is the row frozen as lit: the issuer, the holder, the name in the document's language (given by
     * the issue) and when the node was lit; there is no end to show (A2-12).
     */
    @Bean
    DocumentLayout qbNodeCertificateLayout() {
        return nodeLayout(QbCertificates.NODE_LAYOUT_ID, QbPermissions.ME, QbCertificates.NODE_TEMPLATE);
    }

    @Bean
    DocumentLayout qbNodeSponsorCertificateLayout() {
        return nodeLayout(QbCertificates.NODE_SPONSOR_LAYOUT_ID, QbPermissions.SPONSOR_ME,
            QbCertificates.NODE_SPONSOR_TEMPLATE);
    }

    private static DocumentLayout nodeLayout(String id, String permission, String template) {
        return DocumentLayout.define(id, d -> d
            .permissions(permission)
            .subject(QbCertificates.CERTIFICATE, "certificateId")
            .number(template, "certificateNo")
            .party("issuer", template, "issuerName")
            .party("holder", template, "holderName")
            .facts(template, "certificateName", "litAt", "verifyAddress")
            .note("note"));
    }

    @Bean
    ProcessDefinition<CertificateProcesses.MineInput, CertificateProcesses.MineOutput, ProcessContext>
        qbMyCertifications() {
        return CertificateProcesses.mine();
    }

    @Bean
    ProcessDefinition<CertificateProcesses.MineInput, CertificateProcesses.MineOutput, ProcessContext>
        qbSponsorMyCertificates() {
        return CertificateProcesses.sponsorMine();
    }

    @Bean
    ProcessDefinition<CertificateProcesses.IssueInput, CertificateProcesses.IssueOutput, ProcessContext>
        qbCertificateIssue(@Value("${jabiz.mail.base-url:}") String siteUrl) {
        return CertificateProcesses.issue(siteUrl);
    }

    @Bean
    ProcessDefinition<CertificateProcesses.IssueInput, CertificateProcesses.IssueOutput, ProcessContext>
        qbSponsorCertificateIssue(@Value("${jabiz.mail.base-url:}") String siteUrl) {
        return CertificateProcesses.sponsorIssue(siteUrl);
    }

    @Bean
    ProcessDefinition<CertificateProcesses.ShareInput, CertificateProcesses.ShareOutput, ProcessContext>
        qbCertificateShareSet() {
        return CertificateProcesses.share();
    }

    @Bean
    ProcessDefinition<CertificateProcesses.ShareInput, CertificateProcesses.ShareOutput, ProcessContext>
        qbSponsorCertificateShareSet() {
        return CertificateProcesses.sponsorShare();
    }

    // ---- F-S5a: the certificates the lighting issues ---------------------------------------------------------------

    @Bean
    ProcessDefinition<NodeCertificates.SubjectRef, NodeCertificates.SettleOutput, ProcessContext>
        qbTreeLitCertificate(MessageCatalog messages) {
        return NodeCertificates.settle(messages);
    }

    @Bean
    com.jabiz.event.EventSubscription<NodeCertificates.SubjectRef> qbTreeCertificateDueSubscription(
        ProcessDefinition<NodeCertificates.SubjectRef, NodeCertificates.SettleOutput, ProcessContext>
            qbTreeLitCertificate) {
        return com.jabiz.event.EventSubscription.of(NodeCertificates.DUE_CONSUMER, NodeCertificates.DUE_EVENT,
            qbTreeLitCertificate, event -> new NodeCertificates.SubjectRef(
                String.valueOf(event.payload().get("subjectId")),
                event.payload().get("subjectKind") == null ? null : String.valueOf(event.payload().get("subjectKind"))));
    }

    /** Whatever the events missed (a failure, an event lost), each person in a transaction of their own. */
    @Bean
    com.jabiz.job.JobDefinition<NodeCertificates.SubjectRef> qbTreeCertificateRetryJob(
        ProcessDefinition<NodeCertificates.SubjectRef, NodeCertificates.SettleOutput, ProcessContext>
            qbTreeLitCertificate, @Value("${jabiz.fiscal-year-zone:UTC}") String zone) {
        java.time.ZoneId zoneId;
        try {
            zoneId = java.time.ZoneId.of(zone);
        } catch (java.time.DateTimeException e) {
            zoneId = java.time.ZoneOffset.UTC;
        }
        return com.jabiz.job.JobDefinition.forEach(NodeCertificates.RETRY_JOB, NodeCertificates.RETRY_CRON, zoneId,
                com.jabiz.job.JobItems.template(NodeCertificates.DUE_TEMPLATE, "subjectId"),
                qbTreeLitCertificate,
                (scheduledTime, row) -> new NodeCertificates.SubjectRef(String.valueOf(row.get("subjectId")), null))
            .maxItems(10_000);
    }
}
