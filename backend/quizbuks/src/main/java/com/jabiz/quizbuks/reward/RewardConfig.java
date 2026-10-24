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
 * entities, their datasets (the administrators' default one, the sponsor's own, the taker's own; all read only and
 * written by the processes only), their dictionaries, the cash reward processes and the two jobs.
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
            .item(RedemptionStatus.REDEEMED, "en", "Collected", "zh", "已领取", "ja", "引き換え済み")
            .item(RedemptionStatus.EXPIRED, "en", "Expired", "zh", "已过期", "ja", "期限切れ")
            .item(RedemptionStatus.VOID, "en", "Void", "zh", "已作废", "ja", "無効"));
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
