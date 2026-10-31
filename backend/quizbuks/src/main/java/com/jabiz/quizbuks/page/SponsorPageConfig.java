package com.jabiz.quizbuks.page;

import com.jabiz.approval.ApprovalSubject;
import com.jabiz.context.RequestContext;
import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.dictionary.StaticDictionary;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.event.EventSubscription;
import com.jabiz.file.FilePolicy;
import com.jabiz.file.MediaTypes;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.operator.OperatorScopes;
import com.jabiz.runtime.approval.ApprovalProcesses;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static com.jabiz.quizbuks.page.SponsorPageProcesses.ApprovalResultInput;
import static com.jabiz.quizbuks.page.SponsorPageProcesses.PageOutput;

/**
 * Registers the sponsors' pages (Q60a): the entity, the administrators' dataset (operators: their own sponsors'
 * pages, Q59c-2; written by the processes only, its write permission one no role holds), the sponsor's own, the
 * public one (published pages, read through the public templates only), the images' policy, the review's subject and
 * its events, and the processes.
 */
@Configuration
public class SponsorPageConfig {

    /** One definition for both subscriptions (an approval and a rejection) and the process bean. */
    private static final ProcessDefinition<ApprovalResultInput, PageOutput, ProcessContext> APPROVAL_RESULT =
        SponsorPageProcesses.approvalResult();

    private final String poolRef;

    SponsorPageConfig(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.poolRef = poolRef;
    }

    @Bean
    EntityDefinition qbSponsorPageEntity() {
        return QbSponsorPages.ENTITY;
    }

    @Bean
    DatasetDefinition qbSponsorPageDataset() {
        return DatasetDefinition.define(QbSponsorPages.DEFAULT_DATASET, d -> d
            .targetEntityType(QbSponsorPages.PAGE)
            .asDefault()
            .permissions(QbPermissions.ADMIN_PUBLICATION_READ, QbPermissions.SPONSOR_VIEW_WRITE)
            .scope(OperatorScopes.of(QbSponsorPages.PAGE))
            .policy(p -> p.processOnlyWrites().maxWriteBatchSize(1))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    @Bean
    DatasetDefinition qbMySponsorPageDataset() {
        return DatasetDefinition.define(QbSponsorPages.MY_DATASET, d -> d
            .targetEntityType(QbSponsorPages.PAGE)
            .permissions(QbPermissions.SPONSOR_ME, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.readOnly(true).allowTimeTravel(false))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef))
            .scope(s -> s.fromContext("userId", "actorId", RequestContext::actorId)));
    }

    /**
     * The published pages, anonymously through the public templates, which project the whitelist; the cover of a
     * published page is a public file thereby (its logo and "About us" are the onboarding's, Q65b).
     * The dataset API is closed to everybody (no role holds the permission), as the public cards' (Q59c-2).
     */
    @Bean
    DatasetDefinition qbPublicSponsorPageDataset() {
        return DatasetDefinition.define(QbSponsorPages.PUBLIC_DATASET, d -> d
            .targetEntityType(QbSponsorPages.PAGE)
            .scope(s -> s.fixed("status", QbSponsorPages.PUBLISHED))
            .publicRead(p -> p.fields("userId", "handle", "language", "headline", "cover", "website"))
            .permissions(QbPermissions.SPONSOR_VIEW_WRITE, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.readOnly(true).allowTimeTravel(false))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    /** A page's cover and products' images: re-encoded without metadata; the file name and the upload stay private. */
    @Bean
    FilePolicy qbSponsorPageImagePolicy() {
        return FilePolicy.define(QbSponsorPages.IMAGE_POLICY)
            .allow(MediaTypes.JPEG, MediaTypes.PNG)
            .maxBytes(5 * FilePolicy.MB)
            .image(i -> i.maxPixels(40_000_000).variants(256, 640, 1280))
            // Read before publication too: by the sponsor and the reviewers (headquarters, the operators), who read
            // the avatars as well.
            .permissions(QbPermissions.SPONSOR_ME, QbPermissions.SPONSOR_AVATAR_READ)
            .privateMetadata()
            .build();
    }

    @Bean
    StaticDictionary qbSponsorPageStatusDictionary() {
        return StaticDictionary.define(QbSponsorPages.STATUS_DICTIONARY, d -> d
            .item(QbSponsorPages.DRAFT, "en", "Draft", "zh", "草稿", "ja", "下書き")
            .item(QbSponsorPages.PENDING, "en", "In review", "zh", "待审核", "ja", "審査待ち")
            .item(QbSponsorPages.PUBLISHED, "en", "Published", "zh", "已发布", "ja", "公開中")
            .item(QbSponsorPages.REJECTED, "en", "Sent back", "zh", "已驳回", "ja", "差し戻し")
            .item(QbSponsorPages.TAKEN_DOWN, "en", "Taken down", "zh", "已下架", "ja", "公開停止"));
    }

    @Bean
    ApprovalSubject qbSponsorPageApprovalSubject() {
        return SponsorPageApprovals.subject();
    }

    @Bean
    EventSubscription<ApprovalResultInput> qbSponsorPageApprovedSubscription() {
        return EventSubscription.of("qb.sponsor-page-approved", ApprovalProcesses.APPROVED_EVENT,
            APPROVAL_RESULT, SponsorPageProcesses::resultInput);
    }

    @Bean
    EventSubscription<ApprovalResultInput> qbSponsorPageRejectedSubscription() {
        return EventSubscription.of("qb.sponsor-page-rejected", ApprovalProcesses.REJECTED_EVENT,
            APPROVAL_RESULT, SponsorPageProcesses::resultInput);
    }

    @Bean
    ProcessDefinition<SponsorPageProcesses.SaveInput, PageOutput, ProcessContext> qbSponsorPageSave() {
        return SponsorPageProcesses.save();
    }

    @Bean
    ProcessDefinition<SponsorPageProcesses.SubmitInput, PageOutput, ProcessContext> qbSponsorPageSubmit() {
        return SponsorPageProcesses.submit();
    }

    @Bean
    ProcessDefinition<ApprovalResultInput, PageOutput, ProcessContext> qbSponsorPageApprovalResult() {
        return APPROVAL_RESULT;
    }

    @Bean
    ProcessDefinition<SponsorPageProcesses.UnpublishInput, PageOutput, ProcessContext> qbSponsorPageUnpublish() {
        return SponsorPageProcesses.unpublish();
    }

    @Bean
    ProcessDefinition<SponsorPageProcesses.TakedownInput, PageOutput, ProcessContext> qbSponsorPageTakedown() {
        return SponsorPageProcesses.takedown();
    }

    @Bean
    ProcessDefinition<SponsorPageProcesses.TakedownInput, PageOutput, ProcessContext> qbOperatorSponsorPageTakedown() {
        return SponsorPageProcesses.operatorTakedown();
    }
}
