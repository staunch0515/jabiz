package com.jabiz.quizbuks.verification;

import com.jabiz.approval.ApprovalSubject;
import com.jabiz.context.RequestContext;
import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.dictionary.StaticDictionary;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.event.EventSubscription;
import com.jabiz.mail.MailTemplate;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.operator.OperatorScopes;
import com.jabiz.runtime.approval.ApprovalProcesses;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static com.jabiz.quizbuks.verification.VerificationProcesses.ApprovalResultInput;
import static com.jabiz.quizbuks.verification.VerificationProcesses.VerificationOutput;

/**
 * Registers the company verification (F-M3): the entity, the administrators' dataset (headquarters every Sponsor's, an
 * operator its own Sponsors'; written by the processes only, its write permission one no role holds), the Sponsor's
 * own and the public one (the public page's mark), the review's subject and events, the processes and the mail.
 */
@Configuration
public class VerificationConfig {

    /** One definition for both subscriptions (an approval and a rejection) and the process bean. */
    private static final ProcessDefinition<ApprovalResultInput, VerificationOutput, ProcessContext> APPROVAL_RESULT =
        VerificationProcesses.approvalResult();

    private final String poolRef;

    VerificationConfig(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.poolRef = poolRef;
    }

    @Bean
    EntityDefinition qbSponsorVerificationEntity() {
        return QbVerifications.ENTITY;
    }

    @Bean
    DatasetDefinition qbSponsorVerificationDataset() {
        return DatasetDefinition.define(QbVerifications.DEFAULT_DATASET, d -> d
            .targetEntityType(QbVerifications.VERIFICATION)
            .asDefault()
            .permissions(QbPermissions.ADMIN_VERIFICATION_READ, QbPermissions.SPONSOR_VIEW_WRITE)
            .scope(OperatorScopes.of(QbVerifications.VERIFICATION))
            .policy(p -> p.processOnlyWrites().maxWriteBatchSize(1))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    /** The Sponsor's own submissions; read only (the write permission is one no role holds). */
    @Bean
    DatasetDefinition qbMySponsorVerificationDataset() {
        return DatasetDefinition.define(QbVerifications.MY_DATASET, d -> d
            .targetEntityType(QbVerifications.VERIFICATION)
            .permissions(QbPermissions.SPONSOR_VERIFICATION, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.readOnly(true).allowTimeTravel(false))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef))
            .scope(s -> s.fromContext("sponsorId", "actorId", RequestContext::actorId)));
    }

    /**
     * The verified companies, anonymously through the public templates (the public page's mark): the Sponsor, the
     * domain and when. The dataset API is closed to everybody (no role holds the permission).
     */
    @Bean
    DatasetDefinition qbPublicSponsorVerificationDataset() {
        return DatasetDefinition.define(QbVerifications.PUBLIC_DATASET, d -> d
            .targetEntityType(QbVerifications.VERIFICATION)
            .scope(s -> s.fixed("status", QbVerifications.APPROVED))
            .publicRead(p -> p.fields("sponsorId", "domain", "verifiedAt"))
            .permissions(QbPermissions.SPONSOR_VIEW_WRITE, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.readOnly(true).allowTimeTravel(false))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    @Bean
    StaticDictionary qbSponsorVerificationStatusDictionary() {
        return StaticDictionary.define(QbVerifications.STATUS_DICTIONARY, d -> d
            .item(QbVerifications.LINK_SENT, "en", "Link sent", "zh", "已发确认链接", "ja", "確認リンク送信済み")
            .item(QbVerifications.REVIEW, "en", "In review", "zh", "待审核", "ja", "審査待ち")
            .item(QbVerifications.APPROVED, "en", "Verified", "zh", "已认证", "ja", "認証済み")
            .item(QbVerifications.REJECTED, "en", "Sent back", "zh", "已驳回", "ja", "差し戻し")
            .item(QbVerifications.WITHDRAWN, "en", "Replaced", "zh", "已重新提交", "ja", "再申請済み")
            .item(QbVerifications.SUPERSEDED, "en", "Changed", "zh", "已变更", "ja", "変更済み")
            .item(QbVerifications.REVOKED, "en", "Withdrawn", "zh", "已撤销", "ja", "取り消し"));
    }

    @Bean
    ApprovalSubject qbSponsorVerificationApprovalSubject() {
        return VerificationApprovals.subject();
    }

    @Bean
    EventSubscription<ApprovalResultInput> qbSponsorVerificationApprovedSubscription() {
        return EventSubscription.of("qb.sponsor-verification-approved", ApprovalProcesses.APPROVED_EVENT,
            APPROVAL_RESULT, VerificationProcesses::resultInput);
    }

    @Bean
    EventSubscription<ApprovalResultInput> qbSponsorVerificationRejectedSubscription() {
        return EventSubscription.of("qb.sponsor-verification-rejected", ApprovalProcesses.REJECTED_EVENT,
            APPROVAL_RESULT, VerificationProcesses::resultInput);
    }

    @Bean
    ProcessDefinition<ApprovalResultInput, VerificationOutput, ProcessContext> qbSponsorVerificationApprovalResult() {
        return APPROVAL_RESULT;
    }

    @Bean
    ProcessDefinition<VerificationProcesses.SubmitInput, VerificationOutput, ProcessContext>
        qbSponsorVerificationSubmit() {
        return VerificationProcesses.submit();
    }

    @Bean
    ProcessDefinition<VerificationProcesses.ConfirmInput, VerificationOutput, ProcessContext>
        qbSponsorVerificationConfirm() {
        return VerificationProcesses.confirm();
    }

    @Bean
    ProcessDefinition<VerificationProcesses.RevokeInput, VerificationOutput, ProcessContext>
        qbSponsorVerificationRevoke() {
        return VerificationProcesses.revoke();
    }

    @Bean
    MailTemplate qbSponsorVerificationConfirmMail() {
        return VerificationMails.CONFIRM;
    }
}
