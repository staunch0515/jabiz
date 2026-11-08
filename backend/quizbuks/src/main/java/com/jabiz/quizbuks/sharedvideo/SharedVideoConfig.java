package com.jabiz.quizbuks.sharedvideo;

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

import static com.jabiz.quizbuks.sharedvideo.SharedVideoProcesses.ApprovalResultInput;
import static com.jabiz.quizbuks.sharedvideo.SharedVideoProcesses.VideoOutput;

/**
 * Registers the shared videos (F-S12): the entity, the administrators' dataset (headquarters every provider's, an
 * operator its own sponsors'; written by the processes only, its write permission one no role holds), the review's
 * subject and events, the processes and the mail. The sponsors read the catalog and their own shares through
 * templates only.
 */
@Configuration
public class SharedVideoConfig {

    /** One definition for both subscriptions (an approval and a rejection) and the process bean. */
    private static final ProcessDefinition<ApprovalResultInput, VideoOutput, ProcessContext> APPROVAL_RESULT =
        SharedVideoProcesses.approvalResult();

    private final String poolRef;

    SharedVideoConfig(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.poolRef = poolRef;
    }

    @Bean
    EntityDefinition qbSharedVideoEntity() {
        return QbSharedVideos.ENTITY;
    }

    @Bean
    DatasetDefinition qbSharedVideoDataset() {
        return DatasetDefinition.define(QbSharedVideos.DEFAULT_DATASET, d -> d
            .targetEntityType(QbSharedVideos.VIDEO)
            .asDefault()
            .permissions(QbPermissions.ADMIN_SHARED_VIDEO_READ, QbPermissions.SPONSOR_VIEW_WRITE)
            .scope(OperatorScopes.of(QbSharedVideos.VIDEO))
            .policy(p -> p.processOnlyWrites().maxWriteBatchSize(1))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    /** The provider's own shares; read only (the write permission is one no role holds). */
    @Bean
    DatasetDefinition qbMySharedVideoDataset() {
        return DatasetDefinition.define(QbSharedVideos.MY_DATASET, d -> d
            .targetEntityType(QbSharedVideos.VIDEO)
            .permissions(QbPermissions.SPONSOR_ME, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.readOnly(true).allowTimeTravel(false))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef))
            .scope(s -> s.fromContext("providerId", "actorId", RequestContext::actorId)));
    }

    @Bean
    StaticDictionary qbSharedVideoStatusDictionary() {
        return StaticDictionary.define(QbSharedVideos.STATUS_DICTIONARY, d -> d
            .item(QbSharedVideos.PENDING, "en", "In review", "zh", "待审核", "ja", "審査待ち")
            .item(QbSharedVideos.APPROVED, "en", "Shared", "zh", "共享中", "ja", "共有中")
            .item(QbSharedVideos.REJECTED, "en", "Sent back", "zh", "已驳回", "ja", "差し戻し")
            .item(QbSharedVideos.WITHDRAWN, "en", "Withdrawn", "zh", "已撤回", "ja", "取り下げ済み")
            .item(QbSharedVideos.TAKEN_DOWN, "en", "Taken down", "zh", "已下架", "ja", "公開停止"));
    }

    @Bean
    ApprovalSubject qbSharedVideoApprovalSubject() {
        return SharedVideoApprovals.subject();
    }

    @Bean
    EventSubscription<ApprovalResultInput> qbSharedVideoApprovedSubscription() {
        return EventSubscription.of("qb.shared-video-approved", ApprovalProcesses.APPROVED_EVENT, APPROVAL_RESULT,
            SharedVideoProcesses::resultInput);
    }

    @Bean
    EventSubscription<ApprovalResultInput> qbSharedVideoRejectedSubscription() {
        return EventSubscription.of("qb.shared-video-rejected", ApprovalProcesses.REJECTED_EVENT, APPROVAL_RESULT,
            SharedVideoProcesses::resultInput);
    }

    @Bean
    ProcessDefinition<ApprovalResultInput, VideoOutput, ProcessContext> qbSharedVideoApprovalResult() {
        return APPROVAL_RESULT;
    }

    @Bean
    ProcessDefinition<SharedVideoProcesses.ShareInput, VideoOutput, ProcessContext> qbSharedVideoShare() {
        return SharedVideoProcesses.share();
    }

    @Bean
    ProcessDefinition<SharedVideoProcesses.VideoRef, VideoOutput, ProcessContext> qbSharedVideoWithdraw() {
        return SharedVideoProcesses.withdraw();
    }

    @Bean
    ProcessDefinition<SharedVideoProcesses.ErasedWithdrawInput, VideoOutput, ProcessContext>
        qbSharedVideoErasedWithdraw() {
        return SharedVideoProcesses.erasedWithdraw();
    }

    @Bean
    ProcessDefinition<SharedVideoProcesses.TakedownInput, VideoOutput, ProcessContext> qbSharedVideoTakedown() {
        return SharedVideoProcesses.takedown();
    }

    @Bean
    MailTemplate qbSharedVideoTakenDownMail() {
        return SharedVideoMails.TAKEN_DOWN;
    }

    @Bean
    MailTemplate qbPublicationSharedVideoEndedMail() {
        return SharedVideoMails.PUBLICATION_ENDED;
    }
}
