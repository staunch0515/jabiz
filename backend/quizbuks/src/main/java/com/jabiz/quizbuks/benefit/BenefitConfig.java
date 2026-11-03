package com.jabiz.quizbuks.benefit;

import com.jabiz.context.RequestContext;
import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.dictionary.StaticDictionary;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.file.FilePolicy;
import com.jabiz.file.MediaTypes;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.operator.OperatorScopes;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.jabiz.job.JobDefinition;
import com.jabiz.job.JobItems;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * Registers sponsored benefits (Q89a): the four entities, their default datasets (written by the processes only; read
 * by headquarters' content administrators), each side's own rows for its templates (no role reads those datasets
 * themselves), the dictionaries, the two file policies and the processes.
 */
@Configuration
public class BenefitConfig {

    private final String poolRef;
    private final ZoneId zone;

    BenefitConfig(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef,
        @Value("${jabiz.fiscal-year-zone:UTC}") String zone) {
        this.poolRef = poolRef;
        this.zone = ZoneId.of(zone);
    }

    @Bean
    EntityDefinition qbBenefitOfferEntity() {
        return QbBenefits.OFFER_ENTITY;
    }

    @Bean
    EntityDefinition qbBenefitConversationEntity() {
        return QbBenefits.CONVERSATION_ENTITY;
    }

    @Bean
    EntityDefinition qbBenefitMessageEntity() {
        return QbBenefits.MESSAGE_ENTITY;
    }

    @Bean
    EntityDefinition qbBenefitPurchaseEntity() {
        return QbBenefits.PURCHASE_ENTITY;
    }

    @Bean
    EntityDefinition qbBenefitAllocationEntity() {
        return QbBenefits.ALLOCATION_ENTITY;
    }

    @Bean
    DatasetDefinition qbBenefitAllocationDataset() {
        return defaultDataset(QbBenefits.ALLOCATION);
    }

    @Bean
    StaticDictionary qbBenefitAllocationStatusDictionary() {
        return StaticDictionary.define(QbBenefits.ALLOCATION_STATUS_DICTIONARY, d -> d
            .item(QbBenefits.OPEN, "en", "Held", "zh", "占用中", "ja", "割り当て中")
            .item(QbBenefits.RELEASED, "en", "Released", "zh", "已退回", "ja", "返却済み"));
    }

    @Bean
    ProcessDefinition<AllocationProcesses.AllocationRef, AllocationProcesses.ReleaseOutput, ProcessContext>
        qbBenefitAllocationRelease() {
        return AllocationProcesses.release();
    }

    /** Every ten minutes, at an odd second: each due allocation in its own transaction (decision D42). */
    @Bean
    JobDefinition<AllocationProcesses.AllocationRef> qbBenefitAllocationReleaseJob(
        @Value("${quizbuks.benefits.release-cron:17 */10 * * * *}") String cron,
        ProcessDefinition<AllocationProcesses.AllocationRef, AllocationProcesses.ReleaseOutput, ProcessContext>
            qbBenefitAllocationRelease) {
        return JobDefinition.forEach(QbBenefits.RELEASE_JOB, cron, ZoneOffset.UTC,
                JobItems.template(QbBenefits.RELEASE_DUE_TEMPLATE, scheduledTime -> Map.of(), "allocationId"),
                qbBenefitAllocationRelease, (scheduledTime, row) -> new AllocationProcesses.AllocationRef(
                    String.valueOf(row.get("allocationId"))))
            .maxItems(5_000);
    }

    // ---- Q89d-1: suspension and incidents ---------------------------------------------------------------------------

    @Bean
    EntityDefinition qbBenefitIncidentEntity() {
        return QbBenefits.INCIDENT_ENTITY;
    }

    @Bean
    DatasetDefinition qbBenefitIncidentDataset() {
        return defaultDataset(QbBenefits.INCIDENT);
    }

    @Bean
    StaticDictionary qbBenefitIncidentKindDictionary() {
        return StaticDictionary.define(QbBenefits.INCIDENT_KIND_DICTIONARY, d -> d
            .item("NOT_FULFILLED", "en", "Not provided", "zh", "未提供", "ja", "提供されない")
            .item("CLOSED_BUSINESS", "en", "Closed or out of business", "zh", "停业或歇业", "ja", "休業・廃業")
            .item("QUANTITY_MISMATCH", "en", "Quantity does not match", "zh", "数量不符", "ja", "数量の不一致")
            .item("QUALITY", "en", "Quality", "zh", "质量问题", "ja", "品質")
            .item("COMPLAINT", "en", "Complaint", "zh", "投诉", "ja", "苦情")
            .item("OTHER", "en", "Other", "zh", "其他", "ja", "その他"));
    }

    @Bean
    ProcessDefinition<IncidentProcesses.SuspendInput, IncidentProcesses.PurchaseState, ProcessContext>
        qbBenefitPurchaseSuspend() {
        return IncidentProcesses.suspend();
    }

    @Bean
    ProcessDefinition<IncidentProcesses.ResumeInput, IncidentProcesses.PurchaseState, ProcessContext>
        qbBenefitPurchaseResume() {
        return IncidentProcesses.resume();
    }

    @Bean
    ProcessDefinition<IncidentProcesses.RecordInput, IncidentProcesses.IncidentOutput, ProcessContext>
        qbBenefitIncidentRecord() {
        return IncidentProcesses.record();
    }

    @Bean
    ProcessDefinition<IncidentProcesses.ResolveInput, IncidentProcesses.IncidentOutput, ProcessContext>
        qbBenefitIncidentResolve() {
        return IncidentProcesses.resolve();
    }

    @Bean
    ProcessDefinition<com.jabiz.quizbuks.publishing.PublicationLifecycle.BenefitPauseInput,
        com.jabiz.quizbuks.publishing.PublicationOutput, ProcessContext> qbPublicationBenefitPause() {
        return com.jabiz.quizbuks.publishing.PublicationLifecycle.benefitPause();
    }

    @Bean
    ProcessDefinition<com.jabiz.quizbuks.reward.RedemptionProcesses.SuspendVoidInput,
        com.jabiz.quizbuks.reward.RedemptionProcesses.RedemptionOutput, ProcessContext> qbRedemptionSuspendVoid() {
        return com.jabiz.quizbuks.reward.RedemptionProcesses.suspendVoid();
    }

    /** Every ten minutes, at another odd second: each prize to void in its own transaction (decision D42). */
    @Bean
    JobDefinition<com.jabiz.quizbuks.reward.RedemptionProcesses.SuspendVoidInput> qbBenefitVoidJob(
        @Value("${quizbuks.benefits.void-cron:37 */10 * * * *}") String cron,
        ProcessDefinition<com.jabiz.quizbuks.reward.RedemptionProcesses.SuspendVoidInput,
            com.jabiz.quizbuks.reward.RedemptionProcesses.RedemptionOutput, ProcessContext> qbRedemptionSuspendVoid) {
        return JobDefinition.forEach(QbBenefits.VOID_JOB, cron, ZoneOffset.UTC,
                JobItems.template(QbBenefits.VOID_DUE_TEMPLATE, scheduledTime -> Map.of(), "redemptionId"),
                qbRedemptionSuspendVoid, (scheduledTime, row) -> new com.jabiz.quizbuks.reward.RedemptionProcesses
                    .SuspendVoidInput(String.valueOf(row.get("redemptionId")), String.valueOf(row.get("purchaseId"))))
            .maxItems(5_000);
    }

    private DatasetDefinition defaultDataset(String entity) {
        return DatasetDefinition.define(QbBenefits.defaultDataset(entity), d -> d
            .targetEntityType(entity)
            .asDefault()
            .permissions(QbPermissions.BENEFIT_READ, QbPermissions.SPONSOR_VIEW_WRITE)
            .scope(OperatorScopes.of(entity))
            .policy(p -> p.processOnlyWrites())
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    /** One side's own rows ({@code field} is the caller), for its templates only. */
    private DatasetDefinition own(String side, String entity, String field) {
        return DatasetDefinition.define(QbBenefits.sideDataset(side, entity), d -> d
            .targetEntityType(entity)
            .permissions(QbPermissions.BENEFIT_SYSTEM, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.readOnly(true).allowTimeTravel(false))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef))
            .scope(s -> s.fromContext(field, "actorId", RequestContext::actorId)));
    }

    @Bean
    DatasetDefinition qbBenefitOfferDataset() {
        return defaultDataset(QbBenefits.OFFER);
    }

    @Bean
    DatasetDefinition qbBenefitConversationDataset() {
        return defaultDataset(QbBenefits.CONVERSATION);
    }

    @Bean
    DatasetDefinition qbBenefitMessageDataset() {
        return defaultDataset(QbBenefits.MESSAGE);
    }

    @Bean
    DatasetDefinition qbBenefitPurchaseDataset() {
        return defaultDataset(QbBenefits.PURCHASE);
    }

    /** Q89b-1: the prizes the caller hands over as the provider of other sponsors' benefits (its templates only). */
    @Bean
    DatasetDefinition qbFulfillerRedemptionDataset() {
        return DatasetDefinition.define(FULFILLER_REDEMPTIONS, d -> d
            .targetEntityType(com.jabiz.quizbuks.reward.QbRewards.REDEMPTION)
            .permissions(QbPermissions.BENEFIT_SYSTEM, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.readOnly(true).allowTimeTravel(false))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef))
            .scope(s -> s.fromContext("fulfillerId", "actorId", RequestContext::actorId)));
    }

    static final String FULFILLER_REDEMPTIONS = "urn:jabiz:dataset:fulfiller:QbRedemption";

    /** Q89b-2: the publications whose prizes the caller hands over (its templates only). */
    @Bean
    DatasetDefinition qbFulfillerPublicationDataset() {
        return DatasetDefinition.define("urn:jabiz:dataset:fulfiller:QbPublication", d -> d
            .targetEntityType(com.jabiz.quizbuks.publishing.QbPublications.PUBLICATION)
            .permissions(QbPermissions.BENEFIT_SYSTEM, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.readOnly(true).allowTimeTravel(false))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef))
            .scope(s -> s.fromContext("fulfillerId", "actorId", RequestContext::actorId)));
    }

    @Bean
    DatasetDefinition qbProviderBenefitOfferDataset() {
        return own(QbBenefits.PROVIDER, QbBenefits.OFFER, "providerId");
    }

    @Bean
    DatasetDefinition qbSponsorBenefitConversationDataset() {
        return own(QbBenefits.SPONSOR, QbBenefits.CONVERSATION, "sponsorId");
    }

    @Bean
    DatasetDefinition qbProviderBenefitConversationDataset() {
        return own(QbBenefits.PROVIDER, QbBenefits.CONVERSATION, "providerId");
    }

    @Bean
    DatasetDefinition qbSponsorBenefitMessageDataset() {
        return own(QbBenefits.SPONSOR, QbBenefits.MESSAGE, "sponsorId");
    }

    @Bean
    DatasetDefinition qbProviderBenefitMessageDataset() {
        return own(QbBenefits.PROVIDER, QbBenefits.MESSAGE, "providerId");
    }

    @Bean
    DatasetDefinition qbSponsorBenefitPurchaseDataset() {
        return own(QbBenefits.SPONSOR, QbBenefits.PURCHASE, "sponsorId");
    }

    @Bean
    DatasetDefinition qbProviderBenefitPurchaseDataset() {
        return own(QbBenefits.PROVIDER, QbBenefits.PURCHASE, "providerId");
    }

    /** A benefit's images: re-encoded without metadata; shown to sponsors, the name and the upload stay private. */
    @Bean
    FilePolicy qbBenefitImagePolicy() {
        return FilePolicy.define(QbBenefits.IMAGE_POLICY)
            .allow(MediaTypes.JPEG, MediaTypes.PNG)
            .maxBytes(5 * FilePolicy.MB)
            .image(i -> i.maxPixels(40_000_000).variants(256, 640, 1280))
            .permissions(QbPermissions.BENEFIT_USE, QbPermissions.BENEFIT_USE)
            .privateMetadata()
            .build();
    }

    /**
     * A purchase's proofs (receipts, contracts): images re-encoded, or a PDF; read by the two sides, whose pages are
     * the only places their random keys appear. The name and the upload stay private.
     */
    @Bean
    FilePolicy qbBenefitProofPolicy() {
        return FilePolicy.define(QbBenefits.PROOF_POLICY)
            .allow(MediaTypes.JPEG, MediaTypes.PNG, MediaTypes.PDF)
            .maxBytes(10 * FilePolicy.MB)
            .image(i -> i.maxPixels(40_000_000))
            .permissions(QbPermissions.BENEFIT_USE, QbPermissions.BENEFIT_USE)
            .privateMetadata()
            .build();
    }

    @Bean
    StaticDictionary qbBenefitKindDictionary() {
        return StaticDictionary.define(QbBenefits.KIND_DICTIONARY, d -> d
            .item(QbBenefits.GOODS, "en", "Goods", "zh", "实物", "ja", "商品")
            .item(QbBenefits.SERVICE, "en", "Service", "zh", "服务", "ja", "サービス")
            .item(QbBenefits.DIGITAL, "en", "Digital product", "zh", "数字产品", "ja", "デジタル商品"));
    }

    @Bean
    StaticDictionary qbBenefitOfferStatusDictionary() {
        return StaticDictionary.define(QbBenefits.OFFER_STATUS_DICTIONARY, d -> d
            .item(QbBenefits.LISTED, "en", "Shown", "zh", "上架", "ja", "掲載中")
            .item(QbBenefits.UNLISTED, "en", "Hidden", "zh", "未上架", "ja", "非掲載")
            .item(QbBenefits.TAKEN_DOWN, "en", "Taken down", "zh", "已下架", "ja", "掲載停止"));
    }

    @Bean
    StaticDictionary qbBenefitSideDictionary() {
        return StaticDictionary.define(QbBenefits.SIDE_DICTIONARY, d -> d
            .item(QbBenefits.SPONSOR, "en", "Sponsor", "zh", "赞助方", "ja", "協賛者")
            .item(QbBenefits.PROVIDER, "en", "Provider", "zh", "提供方", "ja", "提供者"));
    }

    @Bean
    StaticDictionary qbBenefitPurchaseStatusDictionary() {
        return StaticDictionary.define(QbBenefits.PURCHASE_STATUS_DICTIONARY, d -> d
            .item(PurchaseStatus.DRAFT, "en", "Draft", "zh", "草稿", "ja", "下書き")
            .item(PurchaseStatus.PENDING_PROVIDER, "en", "Waiting for the provider", "zh", "等待提供方确认",
                "ja", "提供者の確認待ち")
            .item(PurchaseStatus.CONFIRMED, "en", "Confirmed", "zh", "已确认", "ja", "確認済み")
            .item(PurchaseStatus.SUSPENDED, "en", "Suspended", "zh", "已暂停", "ja", "停止中")
            .item(PurchaseStatus.CLOSED, "en", "Closed", "zh", "已完成", "ja", "完了")
            .item(PurchaseStatus.CANCELLED, "en", "Withdrawn", "zh", "已撤回", "ja", "取り下げ"));
    }

    @Bean
    ProcessDefinition<OfferProcesses.SaveInput, OfferProcesses.OfferOutput, ProcessContext> qbBenefitOfferSave() {
        return OfferProcesses.save();
    }

    @Bean
    ProcessDefinition<BenefitConversationProcesses.OpenInput, BenefitConversationProcesses.MessageOutput,
        ProcessContext> qbBenefitConversationOpen() {
        return BenefitConversationProcesses.open();
    }

    @Bean
    ProcessDefinition<BenefitConversationProcesses.SendInput, BenefitConversationProcesses.MessageOutput,
        ProcessContext> qbBenefitConversationSend() {
        return BenefitConversationProcesses.send();
    }

    @Bean
    ProcessDefinition<BenefitConversationProcesses.ConversationRef, BenefitConversationProcesses.ReadOutput,
        ProcessContext> qbBenefitConversationRead() {
        return BenefitConversationProcesses.read();
    }

    @Bean
    ProcessDefinition<PurchaseProcesses.SaveInput, PurchaseProcesses.PurchaseOutput, ProcessContext>
        qbBenefitPurchaseSave() {
        return PurchaseProcesses.save(zone);
    }

    @Bean
    ProcessDefinition<PurchaseProcesses.PurchaseRef, PurchaseProcesses.PurchaseOutput, ProcessContext>
        qbBenefitPurchaseSubmit() {
        return PurchaseProcesses.submit();
    }

    @Bean
    ProcessDefinition<PurchaseProcesses.PurchaseRef, PurchaseProcesses.PurchaseOutput, ProcessContext>
        qbBenefitPurchaseConfirm() {
        return PurchaseProcesses.confirm();
    }

    @Bean
    ProcessDefinition<PurchaseProcesses.DeclineInput, PurchaseProcesses.PurchaseOutput, ProcessContext>
        qbBenefitPurchaseDecline() {
        return PurchaseProcesses.decline();
    }

    @Bean
    ProcessDefinition<PurchaseProcesses.PurchaseRef, PurchaseProcesses.PurchaseOutput, ProcessContext>
        qbBenefitPurchaseCancel() {
        return PurchaseProcesses.cancel();
    }
}
