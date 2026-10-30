package com.jabiz.quizbuks.publishing;

import com.jabiz.approval.ApprovalSubject;
import com.jabiz.context.RequestContext;
import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.dictionary.StaticDictionary;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.event.EventSubscription;
import com.jabiz.i18n.MessageCatalog;
import com.jabiz.job.JobDefinition;
import com.jabiz.job.JobItems;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.wallet.QbWallets;
import com.jabiz.quizbuks.operator.OperatorScopes;
import com.jabiz.runtime.approval.ApprovalProcesses;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

/**
 * Registers publishing (docs/quizbuks/plans/Q4-publishing.md): the four entities, their datasets, the two
 * dictionaries, the approval subject, the ten processes, the minute's job and the two subscriptions to the approval
 * events.
 *
 * <p>Each entity has a default dataset, read only by administrators ({@code qb.admin.publication.read}) and written
 * by the processes only ({@code processOnlyWrites}; its write permission is one no role holds). A sponsor reads their own publications and tiers through the
 * sponsor datasets (published as a sponsor, {@code publisherId} = the caller, S0), read only and without history;
 * counters through the templates.
 */
@Configuration
public class PublishingConfig {

    private final String poolRef;

    PublishingConfig(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.poolRef = poolRef;
    }

    private DatasetDefinition defaultDataset(String entity, int maxWriteBatch) {
        return DatasetDefinition.define(QbPublications.defaultDataset(entity), d -> d
            .targetEntityType(entity)
            .asDefault()
            // Q59c-2: an operator's session sees its sponsors' rows only (platform decision D64).
            .scope(OperatorScopes.of(entity))
            .permissions(QbPermissions.ADMIN_PUBLICATION_READ, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.processOnlyWrites().maxWriteBatchSize(maxWriteBatch))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    private DatasetDefinition sponsorDataset(String entity) {
        // Read only: the write permission is one no role holds, so making it writable later grants nothing.
        return DatasetDefinition.define(QbPublications.sponsorDataset(entity), d -> d
            .targetEntityType(entity)
            .permissions(QbPermissions.PUBLICATION_WRITE, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.readOnly(true).allowTimeTravel(false))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef))
            .scope(s -> s.fromContext("publisherId", "actorId", RequestContext::actorId)
                .fixed("publisherKind", PublisherKind.SPONSOR)));
    }

    /** Who may publish (S0, D-S0-1): the merchant systems a publication's owner can belong to. */
    @Bean
    StaticDictionary qbPublisherKindDictionary() {
        return StaticDictionary.define(PublisherKind.DICTIONARY, d -> d
            .item(PublisherKind.SPONSOR, "en", "Sponsor", "zh", "商家", "ja", "スポンサー")
            .item(PublisherKind.SHOPIFY_SHOP, "en", "Shopify shop", "zh", "Shopify 店铺", "ja", "Shopify ストア"));
    }

    @Bean
    EntityDefinition qbPublicationEntity() {
        return QbPublications.PUBLICATION_ENTITY;
    }

    @Bean
    EntityDefinition qbRewardTierEntity() {
        return QbPublications.TIER_ENTITY;
    }

    @Bean
    EntityDefinition qbPublicationCounterEntity() {
        return QbPublications.COUNTER_ENTITY;
    }

    @Bean
    DatasetDefinition qbPublicationDataset() {
        return defaultDataset(QbPublications.PUBLICATION, 100);
    }

    @Bean
    DatasetDefinition qbRewardTierDataset() {
        // Saving replaces at most every tier: as many deletes and inserts.
        return defaultDataset(QbPublications.TIER, 2 * PublishingLimits.MAX_TIERS);
    }

    @Bean
    DatasetDefinition qbPublicationCounterDataset() {
        return defaultDataset(QbPublications.COUNTER, 100);
    }

    @Bean
    EntityDefinition qbPublicationStartCounterEntity() {
        return QbPublications.START_COUNTER_ENTITY;
    }

    @Bean
    DatasetDefinition qbPublicationStartCounterDataset() {
        return defaultDataset(QbPublications.START_COUNTER, 100);
    }

    @Bean
    EntityDefinition qbPublicationScheduleEntity() {
        return QbPublications.SCHEDULE_ENTITY;
    }

    @Bean
    DatasetDefinition qbPublicationScheduleDataset() {
        return defaultDataset(QbPublications.SCHEDULE, 100);
    }

    @Bean
    EntityDefinition qbPublicationPastVersionEntity() {
        return QbPublications.PAST_VERSION_ENTITY;
    }

    @Bean
    DatasetDefinition qbPublicationPastVersionDataset() {
        return defaultDataset(QbPublications.PAST_VERSION, 1000);
    }

    @Bean
    EntityDefinition qbPublicationCardEntity() {
        return QbPublications.CARD_ENTITY;
    }

    /** Read by administrators; the takers read cards through the templates only (Q5). */
    @Bean
    DatasetDefinition qbPublicationCardDataset() {
        return defaultDataset(QbPublications.CARD, PublicationFunds.BATCH);
    }

    /**
     * The cards of live publications, for a share link's page (Q15, template {@code qb.public.publication}): what the
     * app's page shows anybody before signing in, never the regions, the budget or the tiers (the sponsor's id only to
     * join the sponsor's company; the template does not return it). A
     * paused, ended or not yet started publication is outside the fixed scope.
     */
    @Bean
    DatasetDefinition qbPublicPublicationCardDataset() {
        return DatasetDefinition.define(QbPublications.PUBLIC_CARDS, d -> d
            .targetEntityType(QbPublications.CARD)
            // Q25: an invited publication has no public page; its link carries the invitee's sign-in.
            // S0 (D-S0-4): the share page is the sponsors' system's; a shop's publication (S2) has its own storefront.
            .scope(s -> s.fixed("status", PublicationStatus.LIVE).fixed("audience", Audience.PUBLIC)
                .fixed("publisherKind", PublisherKind.SPONSOR))
            .publicRead(p -> p.fields("publicationId", "publisherId", "name", "quizTitle", "kind", "startAt", "endAt",
                "timeLimitSec", "questionCount", "fullScore", "passScore", "maxReward", "rewardMethod",
                "redeemDescription", "digitalDelivery", "digitalDescription"))
            .permissions(QbPermissions.ADMIN_PUBLICATION_READ, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.readOnly(true).allowTimeTravel(false))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    @Bean
    EntityDefinition qbPublicationOwnerEntity() {
        return QbPublications.OWNER_ENTITY;
    }

    /** The caller's own publications, read only (a view over the cards); its write permission is no role's. */
    @Bean
    DatasetDefinition qbMyPublicationOwnerDataset() {
        return DatasetDefinition.define(QbPublications.MY_OWNER_DATASET, d -> d
            .targetEntityType(QbPublications.OWNER)
            .asDefault()
            .permissions(QbPermissions.PLAY, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.readOnly(true).allowTimeTravel(false))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef))
            .scope(s -> s.fromContext("publisherId", "actorId", RequestContext::actorId)
                .fixed("publisherKind", PublisherKind.SPONSOR)));
    }

    @Bean
    ProcessDefinition<QbWallets.SponsorRef, PublicationFunds.RestoredOutput, ProcessContext>
        qbPublicationFundsRestored() {
        return FUNDS_RESTORED;
    }

    @Bean
    ProcessDefinition<PublicationMoves.MoveInput, PublicationMoves.MoveOutput, ProcessContext>
        qbPublicationCompletionMove() {
        return PublicationMoves.move();
    }

    @Bean
    ProcessDefinition<PublicationLifecycle.PublicationRef, PublicationOutput, ProcessContext> qbPublicationFunded() {
        return PublicationFunds.funded();
    }

    private static final ProcessDefinition<QbWallets.SponsorRef, PublicationFunds.RestoredOutput, ProcessContext>
        FUNDS_RESTORED = PublicationFunds.fundsRestored();

    /** A sponsor's pending rewards are all paid: their underfunded publications go back on (Q5, D-Q5-5). */
    @Bean
    EventSubscription<QbWallets.SponsorRef> qbFundsRestoredSubscription() {
        return EventSubscription.of(PublicationFunds.SUBSCRIPTION, QbWallets.FUNDS_RESTORED_EVENT, FUNDS_RESTORED,
            PublicationFunds::input);
    }

    @Bean
    DatasetDefinition qbSponsorPublicationDataset() {
        return sponsorDataset(QbPublications.PUBLICATION);
    }

    @Bean
    DatasetDefinition qbSponsorRewardTierDataset() {
        return sponsorDataset(QbPublications.TIER);
    }

    @Bean
    StaticDictionary qbPublicationStatusDictionary() {
        return StaticDictionary.define(QbPublications.STATUS_DICTIONARY, d -> d
            .item(PublicationStatus.DRAFT, "en", "Draft", "zh", "草稿", "ja", "下書き")
            .item(PublicationStatus.PENDING_REVIEW, "en", "In review", "zh", "待审核", "ja", "審査待ち")
            .item(PublicationStatus.SCHEDULED, "en", "Scheduled", "zh", "待开始", "ja", "開始待ち")
            .item(PublicationStatus.LIVE, "en", "Live", "zh", "进行中", "ja", "公開中")
            .item(PublicationStatus.PAUSED, "en", "Paused", "zh", "已暂停", "ja", "一時停止中")
            .item(PublicationStatus.FUNDS_SHORT, "en", "Funds short", "zh", "预算不足", "ja", "残高不足")
            .item(PublicationStatus.ENDED, "en", "Ended", "zh", "已结束", "ja", "終了"));
    }

    @Bean
    StaticDictionary qbPublicationEndReasonDictionary() {
        return StaticDictionary.define(QbPublications.END_REASON_DICTIONARY, d -> d
            .item(PublicationStatus.EXPIRED, "en", "End time reached", "zh", "到达结束时间", "ja", "終了時刻に到達")
            .item(PublicationStatus.QUOTA, "en", "All winners rewarded", "zh", "名额已满", "ja", "当選枠に到達")
            .item(PublicationStatus.BUDGET, "en", "Budget spent", "zh", "预算已用完", "ja", "予算を使い切り")
            .item(PublicationStatus.ADMIN, "en", "Ended by an administrator", "zh", "已被管理员结束",
                "ja", "管理者が終了")
            .item(PublicationStatus.PUBLISHER, "en", "Ended by its publisher", "zh", "已被发布方结束",
                "ja", "公開者が終了"));
    }

    /** Q22: how a reward reaches the taker. */
    @Bean
    StaticDictionary qbPublicationAudienceDictionary() {
        return StaticDictionary.define(Audience.DICTIONARY, d -> d
            .item(Audience.PUBLIC, "en", "Everyone in the regions", "zh", "地区内的所有人", "ja", "地域内の全員")
            .item(Audience.INVITED, "en", "Invited e-mail addresses only", "zh", "只许指定的邮箱",
                "ja", "招待したメールアドレスのみ"));
    }

    @Bean
    StaticDictionary qbRewardMethodDictionary() {
        return StaticDictionary.define(RewardMethod.DICTIONARY, d -> d
            .item(RewardMethod.PLATFORM, "en", "Kudos paid by QuizBuks", "zh", "平台支付 Kudos",
                "ja", "QuizBuks が Kudos を支払う")
            .item(RewardMethod.SPONSOR_CASH, "en", "Cash from the sponsor", "zh", "商家直接付现金",
                "ja", "スポンサーが現金で支払う")
            .item(RewardMethod.OFFLINE_REDEEM, "en", "Prize at the sponsor's place", "zh", "商家线下兑付",
                "ja", "スポンサーの店舗で引き換え")
            .item(RewardMethod.DIGITAL_PRIZE, "en", "Digital prize (a code or link)", "zh", "电子奖品（兑换码或链接）",
                "ja", "デジタル景品（コードまたはリンク）")
            .item(RewardMethod.CHANNEL, "en", "Given by the channel (a shop's discount code)", "zh",
                "由渠道发放（店铺的折扣码）", "ja", "チャネルが付与（ストアの割引コード）"));
    }

    @Bean
    EntityDefinition qbSponsorRewardMethodsEntity() {
        return QbPublications.SPONSOR_METHODS_ENTITY;
    }

    /** Read by the administrators and the processes; written by {@code QB_SPONSOR_REWARD_METHODS_SET} only. */
    @Bean
    DatasetDefinition qbSponsorRewardMethodsDataset() {
        return defaultDataset(QbPublications.SPONSOR_METHODS, 10);
    }

    /** The sponsor's own setting (the wizard offers only the methods it allows); none means the platform's only. */
    @Bean
    DatasetDefinition qbSponsorOwnRewardMethodsDataset() {
        return DatasetDefinition.define(QbPublications.sponsorDataset(QbPublications.SPONSOR_METHODS), d -> d
            .targetEntityType(QbPublications.SPONSOR_METHODS)
            .permissions(QbPermissions.PUBLICATION_WRITE, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.readOnly(true).allowTimeTravel(false))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef))
            .scope(s -> s.fromContext("userId", "actorId", RequestContext::actorId)));
    }

    @Bean
    ProcessDefinition<RewardMethodProcesses.SetInput, RewardMethodProcesses.SetOutput, ProcessContext>
        qbSponsorRewardMethodsSet() {
        return RewardMethodProcesses.set();
    }

    @Bean
    ApprovalSubject qbPublicationApprovalSubject() {
        return QbPublications.approvalSubject();
    }

    @Bean
    ProcessDefinition<PublicationProcesses.SaveInput, SaveOutput, ProcessContext> qbPublicationSave(
        MessageCatalog messages) {
        return PublicationProcesses.save(messages);
    }

    @Bean
    ProcessDefinition<PublicationProcesses.PublicationRef, PublicationOutput, ProcessContext> qbPublicationDelete() {
        return PublicationProcesses.delete();
    }

    /** S2: a channel's publisher publishes, pauses, resumes and ends (sub-processes of the channel's module). */
    @Bean
    ProcessDefinition<com.jabiz.quizbuks.channel.Channel.PublishInput,
        com.jabiz.quizbuks.channel.Channel.PublishOutput, ProcessContext> qbChannelPublish() {
        return ChannelPublishing.publish();
    }

    @Bean
    ProcessDefinition<com.jabiz.quizbuks.channel.Channel.MoveInput, com.jabiz.quizbuks.channel.Channel.MoveOutput,
        ProcessContext> qbChannelPublicationMove() {
        return ChannelPublishing.move();
    }

    @Bean
    ProcessDefinition<PublicationProcesses.CopyInput, SaveOutput, ProcessContext> qbPublicationCopy(
        MessageCatalog messages) {
        return PublicationProcesses.copy(messages);
    }

    @Bean
    ProcessDefinition<PublicationProcesses.PublicationRef, PublicationOutput, ProcessContext> qbPublicationSubmit() {
        return PublicationLifecycle.submit();
    }

    @Bean
    ProcessDefinition<PublicationLifecycle.PublicationRef, PublicationOutput, ProcessContext> qbPublicationWithdraw() {
        return PublicationLifecycle.withdraw();
    }

    @Bean
    ProcessDefinition<PublicationVersions.SwitchInput, PublicationOutput, ProcessContext> qbPublicationSwitchVersion() {
        return PublicationVersions.switchVersion();
    }

    @Bean
    ProcessDefinition<PublicationVersions.WithdrawInput, PublicationOutput, ProcessContext>
        qbPublicationSwitchWithdraw() {
        return PublicationVersions.withdraw();
    }

    @Bean
    ProcessDefinition<PublicationLifecycle.PublicationRef, PublicationOutput, ProcessContext> qbPublicationPause() {
        return PublicationLifecycle.pause();
    }

    @Bean
    ProcessDefinition<PublicationLifecycle.PublicationRef, PublicationOutput, ProcessContext> qbPublicationResume() {
        return PublicationLifecycle.resume();
    }

    @Bean
    ProcessDefinition<PublicationAdmin.AdminInput, PublicationOutput, ProcessContext> qbPublicationAdminPause() {
        return PublicationAdmin.pause();
    }

    @Bean
    ProcessDefinition<PublicationAdmin.AdminInput, PublicationOutput, ProcessContext> qbPublicationAdminResume() {
        return PublicationAdmin.resume();
    }

    @Bean
    ProcessDefinition<PublicationAdmin.AdminInput, PublicationOutput, ProcessContext> qbPublicationAdminEnd() {
        return PublicationAdmin.end();
    }

    @Bean
    ProcessDefinition<PublicationAdmin.BanPauseInput, PublicationOutput, ProcessContext> qbPublicationBanPause() {
        return PublicationAdmin.banPause();
    }

    @Bean
    ProcessDefinition<PublicationLifecycle.ApprovalResultInput, PublicationOutput, ProcessContext>
        qbPublicationApprovalResult() {
        return APPROVAL_RESULT;
    }

    @Bean
    ProcessDefinition<PublicationLifecycle.PublicationRef, PublicationOutput, ProcessContext> qbPublicationAdvance() {
        return ADVANCE;
    }

    private static final ProcessDefinition<PublicationLifecycle.ApprovalResultInput, PublicationOutput,
        ProcessContext> APPROVAL_RESULT = PublicationLifecycle.approvalResult();

    private static final ProcessDefinition<PublicationLifecycle.PublicationRef, PublicationOutput, ProcessContext>
        ADVANCE = PublicationLifecycle.advance();

    /**
     * Every minute (D-Q4-7): every publication due by the scheduled time ({@code qb.system.publications-due}, from
     * the schedule) is moved on by {@code QB_PUBLICATION_ADVANCE}, each in a transaction of its own (Q5b; platform
     * decision D42), at most {@code maxItems} by due time per run. A run finds everything due, so the minutes missed
     * while down are made up; an item that fails is left alone and tried again with back-off, one whose lock is held
     * waits for the next minute (busy). Neither holds up the others.
     *
     * @param maxItems publications one run moves on ({@code quizbuks.publication-tick.max-items}, default
     *                 {@link PublishingLimits#TICK_BATCH})
     */
    @Bean
    JobDefinition<PublicationLifecycle.PublicationRef> qbPublicationTickJob(
        @Value("${quizbuks.publication-tick.max-items:" + PublishingLimits.TICK_BATCH + "}") int maxItems) {
        return JobDefinition.forEach(PublicationLifecycle.TICK_JOB, "0 * * * * *", ZoneOffset.UTC,
                JobItems.template(PublicationLifecycle.DUE_TEMPLATE, scheduledTime -> Map.of("at", scheduledTime),
                    "publicationId"),
                ADVANCE, (scheduledTime, row) -> new PublicationLifecycle.PublicationRef(
                    UUID.fromString(String.valueOf(row.get("publicationId")))))
            .maxItems(maxItems);
    }

    @Bean
    EventSubscription<PublicationLifecycle.ApprovalResultInput> qbPublicationApprovedSubscription() {
        return EventSubscription.of("qb.publication-approved", ApprovalProcesses.APPROVED_EVENT, APPROVAL_RESULT,
            PublicationLifecycle::resultInput);
    }

    @Bean
    EventSubscription<PublicationLifecycle.ApprovalResultInput> qbPublicationRejectedSubscription() {
        return EventSubscription.of("qb.publication-rejected", ApprovalProcesses.REJECTED_EVENT, APPROVAL_RESULT,
            PublicationLifecycle::resultInput);
    }
}
