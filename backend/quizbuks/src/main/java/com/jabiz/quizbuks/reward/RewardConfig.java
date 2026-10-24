package com.jabiz.quizbuks.reward;

import com.jabiz.context.RequestContext;
import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.dictionary.StaticDictionary;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.job.JobDefinition;
import com.jabiz.job.JobItems;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.QbPermissions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

/**
 * Registers the rewards settled outside the wallet (Q22, docs/quizbuks/plans/Q22-reward-methods.md): the two
 * entities and the sponsors' codes (Q22b), their datasets (the administrators' default one, the sponsor's own, the
 * taker's own; all read only and written by the processes only), their dictionaries, the cash reward and redemption
 * processes and the jobs.
 */
@Configuration
public class RewardConfig {

    private final String poolRef;

    RewardConfig(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.poolRef = poolRef;
    }

    private DatasetDefinition defaultDataset(String entity) {
        return DatasetDefinition.define(QbRewards.defaultDataset(entity), d -> d
            .targetEntityType(entity)
            .asDefault()
            .permissions(QbPermissions.ADMIN_PUBLICATION_READ, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.processOnlyWrites().maxWriteBatchSize(100))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    /** One side's own records: read only, without history, limited to the caller by {@code field}. */
    private DatasetDefinition ownDataset(String id, String entity, String field, String readPermission) {
        return DatasetDefinition.define(id, d -> d
            .targetEntityType(entity)
            .permissions(readPermission, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.readOnly(true).allowTimeTravel(false))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef))
            .scope(s -> s.fromContext(field, "actorId", RequestContext::actorId)));
    }

    @Bean
    EntityDefinition qbCashRewardEntity() {
        return QbRewards.CASH_REWARD_ENTITY;
    }

    @Bean
    EntityDefinition qbRedemptionEntity() {
        return QbRewards.REDEMPTION_ENTITY;
    }

    @Bean
    EntityDefinition qbRedeemTokenEntity() {
        return QbRewards.REDEEM_TOKEN_ENTITY;
    }

    /** The codes: written by the processes only; the sponsor sees their own (never the token's value). */
    @Bean
    DatasetDefinition qbRedeemTokenDataset() {
        return defaultDataset(QbRewards.REDEEM_TOKEN);
    }

    @Bean
    DatasetDefinition qbSponsorRedeemTokenDataset() {
        return ownDataset(QbRewards.sponsorDataset(QbRewards.REDEEM_TOKEN), QbRewards.REDEEM_TOKEN, "sponsorId",
            QbPermissions.PUBLICATION_WRITE);
    }

    @Bean
    DatasetDefinition qbCashRewardDataset() {
        return defaultDataset(QbRewards.CASH_REWARD);
    }

    @Bean
    DatasetDefinition qbSponsorCashRewardDataset() {
        return ownDataset(QbRewards.sponsorDataset(QbRewards.CASH_REWARD), QbRewards.CASH_REWARD, "sponsorId",
            QbPermissions.PUBLICATION_WRITE);
    }

    @Bean
    DatasetDefinition qbTakerCashRewardDataset() {
        return ownDataset(QbRewards.takerDataset(QbRewards.CASH_REWARD), QbRewards.CASH_REWARD, "userId",
            QbPermissions.ME);
    }

    @Bean
    DatasetDefinition qbRedemptionDataset() {
        return defaultDataset(QbRewards.REDEMPTION);
    }

    @Bean
    DatasetDefinition qbSponsorRedemptionDataset() {
        return ownDataset(QbRewards.sponsorDataset(QbRewards.REDEMPTION), QbRewards.REDEMPTION, "sponsorId",
            QbPermissions.PUBLICATION_WRITE);
    }

    @Bean
    DatasetDefinition qbTakerRedemptionDataset() {
        return ownDataset(QbRewards.takerDataset(QbRewards.REDEMPTION), QbRewards.REDEMPTION, "userId",
            QbPermissions.ME);
    }

    @Bean
    StaticDictionary qbCashRewardStatusDictionary() {
        return StaticDictionary.define(QbRewards.CASH_STATUS_DICTIONARY, d -> d
            .item(CashRewardStatus.DUE, "en", "To be paid by the sponsor", "zh", "待商家支付", "ja", "スポンサーの支払い待ち")
            .item(CashRewardStatus.MARKED_PAID, "en", "Paid, awaiting your confirmation", "zh", "商家已支付，待确认",
                "ja", "支払い済み、確認待ち")
            .item(CashRewardStatus.CONFIRMED, "en", "Confirmed", "zh", "已确认", "ja", "確認済み")
            .item(CashRewardStatus.DISPUTED, "en", "Disputed", "zh", "有争议", "ja", "異議あり")
            .item(CashRewardStatus.OVERDUE, "en", "Confirmation overdue", "zh", "逾期未确认", "ja", "確認期限超過")
            .item(CashRewardStatus.VOID, "en", "Void", "zh", "已作废", "ja", "無効"));
    }

    @Bean
    StaticDictionary qbCashRewardResolutionDictionary() {
        return StaticDictionary.define(QbRewards.CASH_RESOLUTION_DICTIONARY, d -> d
            .item(CashRewardStatus.RESOLUTION_PAID, "en", "Paid", "zh", "已支付", "ja", "支払い済み")
            .item(CashRewardStatus.RESOLUTION_UNPAID, "en", "Not paid: owed again", "zh", "未支付：重新待付",
                "ja", "未払い：再び支払い待ち")
            .item(CashRewardStatus.RESOLUTION_VOID, "en", "Void", "zh", "作废", "ja", "無効"));
    }

    @Bean
    StaticDictionary qbRedemptionStatusDictionary() {
        return StaticDictionary.define(QbRewards.REDEMPTION_STATUS_DICTIONARY, d -> d
            .item(RedemptionStatus.DUE, "en", "To be collected", "zh", "待领取", "ja", "引き換え待ち")
            .item(RedemptionStatus.CLAIMED, "en", "Claimed, awaiting the sponsor", "zh", "已扫码，待商家确认",
                "ja", "スキャン済み、スポンサーの確認待ち")
            .item(RedemptionStatus.REDEEMED, "en", "Collected", "zh", "已领取", "ja", "引き換え済み")
            .item(RedemptionStatus.EXPIRED, "en", "Expired", "zh", "已过期", "ja", "期限切れ")
            .item(RedemptionStatus.VOID, "en", "Void", "zh", "已作废", "ja", "無効"));
    }

    @Bean
    StaticDictionary qbRedeemKindDictionary() {
        return StaticDictionary.define(QbRewards.REDEEM_KIND_DICTIONARY, d -> d
            .item(RedemptionStatus.KIND_ROTATING, "en", "Code on the console's screen", "zh", "商家后台屏幕上的码",
                "ja", "コンソール画面のコード")
            .item(RedemptionStatus.KIND_PRINTED, "en", "Printed code", "zh", "打印的固定码", "ja", "印刷したコード"));
    }

    @Bean
    ProcessDefinition<RedemptionProcesses.IssueInput, RedemptionProcesses.IssueOutput, ProcessContext>
        qbRedeemCodeIssue() {
        return RedemptionProcesses.issue();
    }

    @Bean
    ProcessDefinition<RedemptionProcesses.TokenInput, RedemptionProcesses.CheckOutput, ProcessContext>
        qbRedeemTokenCheck() {
        return RedemptionProcesses.check();
    }

    @Bean
    ProcessDefinition<RedemptionProcesses.ClaimInput, RedemptionProcesses.RedemptionOutput, ProcessContext>
        qbRedemptionClaim() {
        return RedemptionProcesses.claim();
    }

    @Bean
    ProcessDefinition<RedemptionProcesses.RedemptionRef, RedemptionProcesses.RedemptionOutput, ProcessContext>
        qbRedemptionConfirm() {
        return RedemptionProcesses.confirm();
    }

    @Bean
    ProcessDefinition<RedemptionProcesses.RedemptionRef, RedemptionProcesses.RedemptionOutput, ProcessContext>
        qbRedemptionDecline() {
        return RedemptionProcesses.decline();
    }

    @Bean
    ProcessDefinition<RedemptionProcesses.VoidInput, RedemptionProcesses.RedemptionOutput, ProcessContext>
        qbRedemptionVoid() {
        return RedemptionProcesses.voidPrize();
    }

    @Bean
    ProcessDefinition<RedemptionProcesses.RedemptionRef, RedemptionProcesses.RedemptionOutput, ProcessContext>
        qbRedemptionExpire() {
        return RedemptionProcesses.expire();
    }

    @Bean
    ProcessDefinition<RedemptionProcesses.RedemptionRef, RedemptionProcesses.RedemptionOutput, ProcessContext>
        qbRedemptionLapse() {
        return RedemptionProcesses.lapse();
    }

    /** Every day: the prizes not collected in time, each in a transaction of its own (D42). */
    @Bean
    JobDefinition<RedemptionProcesses.RedemptionRef> qbRedemptionExpireJob(
        ProcessDefinition<RedemptionProcesses.RedemptionRef, RedemptionProcesses.RedemptionOutput, ProcessContext>
            qbRedemptionExpire) {
        return JobDefinition.forEach(RedemptionProcesses.EXPIRE_JOB, "0 10 2 * * *", ZoneOffset.UTC,
                JobItems.template(RedemptionProcesses.EXPIRED_TEMPLATE,
                    scheduledTime -> Map.of("cutoff", scheduledTime), "redemptionId"),
                qbRedemptionExpire,
                (scheduledTime, row) -> new RedemptionProcesses.RedemptionRef(
                    UUID.fromString(String.valueOf(row.get("redemptionId")))))
            .maxItems(1_000);
    }

    /** Every ten minutes: the claims from a printed code the sponsor did not confirm in time are due again. */
    @Bean
    JobDefinition<RedemptionProcesses.RedemptionRef> qbRedemptionLapseJob(
        ProcessDefinition<RedemptionProcesses.RedemptionRef, RedemptionProcesses.RedemptionOutput, ProcessContext>
            qbRedemptionLapse) {
        return JobDefinition.forEach(RedemptionProcesses.LAPSE_JOB, "0 */10 * * * *", ZoneOffset.UTC,
                JobItems.template(RedemptionProcesses.LAPSED_TEMPLATE,
                    scheduledTime -> Map.of("cutoff", scheduledTime), "redemptionId"),
                qbRedemptionLapse,
                (scheduledTime, row) -> new RedemptionProcesses.RedemptionRef(
                    UUID.fromString(String.valueOf(row.get("redemptionId")))))
            .maxItems(1_000);
    }

    @Bean
    ProcessDefinition<CashRewardProcesses.MarkPaidInput, CashRewardProcesses.CashRewardOutput, ProcessContext>
        qbCashRewardMarkPaid() {
        return CashRewardProcesses.markPaid();
    }

    @Bean
    ProcessDefinition<CashRewardProcesses.CashRewardRef, CashRewardProcesses.CashRewardOutput, ProcessContext>
        qbCashRewardConfirm() {
        return CashRewardProcesses.confirm();
    }

    @Bean
    ProcessDefinition<CashRewardProcesses.DisputeInput, CashRewardProcesses.CashRewardOutput, ProcessContext>
        qbCashRewardDispute() {
        return CashRewardProcesses.dispute();
    }

    @Bean
    ProcessDefinition<CashRewardProcesses.ResolveInput, CashRewardProcesses.CashRewardOutput, ProcessContext>
        qbCashRewardResolve() {
        return CashRewardProcesses.resolve();
    }

    @Bean
    ProcessDefinition<CashRewardProcesses.CashRewardRef, CashRewardProcesses.CashRewardOutput, ProcessContext>
        qbCashRewardOverdue() {
        return CashRewardProcesses.overdue();
    }

    @Bean
    ProcessDefinition<CashRewardProcesses.CashRewardRef, CashRewardProcesses.CashRewardOutput, ProcessContext>
        qbCashRewardRemind() {
        return CashRewardProcesses.remind();
    }

    /** Every hour: the rewards marked paid whose time to confirm ran out, each in a transaction of its own (D42). */
    @Bean
    JobDefinition<CashRewardProcesses.CashRewardRef> qbCashRewardOverdueJob(
        ProcessDefinition<CashRewardProcesses.CashRewardRef, CashRewardProcesses.CashRewardOutput, ProcessContext>
            qbCashRewardOverdue) {
        return JobDefinition.forEach(CashRewardProcesses.OVERDUE_JOB, "0 5 * * * *", ZoneOffset.UTC,
                JobItems.template(CashRewardProcesses.OVERDUE_TEMPLATE,
                    scheduledTime -> Map.of("cutoff", scheduledTime), "cashRewardId"),
                qbCashRewardOverdue,
                (scheduledTime, row) -> new CashRewardProcesses.CashRewardRef(
                    UUID.fromString(String.valueOf(row.get("cashRewardId")))))
            .maxItems(1_000);
    }

    /** Every day: the rewards still owed past their time to pay, whose sponsor was not reminded yet. */
    @Bean
    JobDefinition<CashRewardProcesses.CashRewardRef> qbCashRewardRemindJob(
        ProcessDefinition<CashRewardProcesses.CashRewardRef, CashRewardProcesses.CashRewardOutput, ProcessContext>
            qbCashRewardRemind) {
        return JobDefinition.forEach(CashRewardProcesses.REMIND_JOB, "0 20 1 * * *", ZoneOffset.UTC,
                JobItems.template(CashRewardProcesses.UNPAID_TEMPLATE,
                    scheduledTime -> Map.of("cutoff", scheduledTime), "cashRewardId"),
                qbCashRewardRemind,
                (scheduledTime, row) -> new CashRewardProcesses.CashRewardRef(
                    UUID.fromString(String.valueOf(row.get("cashRewardId")))))
            .maxItems(1_000);
    }
}
