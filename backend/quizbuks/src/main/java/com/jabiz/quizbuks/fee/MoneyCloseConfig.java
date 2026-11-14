package com.jabiz.quizbuks.fee;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.dictionary.StaticDictionary;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.event.EventSubscription;
import com.jabiz.job.JobDefinition;
import com.jabiz.job.JobItems;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.QbPermissions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.ZoneId;

/** F-M15 b: the beans of an erased sponsor's money's wind-up (docs/quizbuks/plans/impl/F-M15.md §4.1, §5). */
@Configuration
public class MoneyCloseConfig {

    private final String poolRef;
    private final ZoneId zone;

    MoneyCloseConfig(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef,
        @Value("${jabiz.fiscal-year-zone:UTC}") String zone) {
        this.poolRef = poolRef;
        // A zone that is not one stops the start (review L9): the jobs' days would silently be UTC's.
        try {
            this.zone = ZoneId.of(zone);
        } catch (RuntimeException e) {
            throw new IllegalStateException("jabiz.fiscal-year-zone is not a time zone: " + zone, e);
        }
    }

    @Bean
    EntityDefinition qbSponsorClosureEntity() {
        return QbSponsorClosures.CLOSURE_ENTITY;
    }

    /** Finance reads it; only the wind-up's processes write it. */
    @Bean
    DatasetDefinition qbSponsorClosureDataset() {
        return DatasetDefinition.define(QbFees.defaultDataset(QbSponsorClosures.CLOSURE), d -> d
            .targetEntityType(QbSponsorClosures.CLOSURE)
            .asDefault()
            .permissions(QbPermissions.ADMIN_FINANCE_READ, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.processOnlyWrites().maxWriteBatchSize(10))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    @Bean
    StaticDictionary qbSponsorClosureStatusDictionary() {
        return StaticDictionary.define(QbSponsorClosures.STATUS_DICTIONARY, d -> d
            .item(QbSponsorClosures.WAITING, "en", "Not wound up yet", "zh", "未完成", "ja", "未完了")
            .item(QbSponsorClosures.CLOSED, "en", "Wound up", "zh", "已完成", "ja", "完了"));
    }

    @Bean
    StaticDictionary qbSponsorClosureReasonDictionary() {
        return StaticDictionary.define(QbSponsorClosures.REASON_DICTIONARY, d -> d
            .item(QbSponsorClosures.SWITCH_OFF, "en", "Switched off", "zh", "开关关闭", "ja", "スイッチがオフ")
            .item(QbSponsorClosures.DEPOSIT_OPEN, "en", "Prepayments not given back yet", "zh", "预付款尚未退回",
                "ja", "前払金が未返却")
            .item(QbSponsorClosures.FEE_OWED, "en", "Fees still owed", "zh", "仍有欠费", "ja", "未払いの料金あり")
            .item(QbSponsorClosures.AI_JOBS_OPEN, "en", "AI jobs still being cancelled", "zh", "AI 作业尚在取消",
                "ja", "AI の処理を取り消し中"));
    }

    @Bean
    ProcessDefinition<MoneyClose.RegisterInput, MoneyClose.RegisterOutput, ProcessContext> qbSponsorClosureRegister() {
        return MoneyClose.register();
    }

    @Bean
    ProcessDefinition<MoneyClose.VoidLotInput, MoneyClose.VoidLotOutput, ProcessContext> qbFeeCreditVoidLot() {
        return MoneyClose.voidLot();
    }

    /** A subscription of its own to the platform's event: retried apart from QB_SPONSOR_ERASED's (plan M1). */
    @Bean
    EventSubscription<MoneyClose.RegisterInput> qbSponsorClosureRegisterSubscription(
        ProcessDefinition<MoneyClose.RegisterInput, MoneyClose.RegisterOutput, ProcessContext> qbSponsorClosureRegister) {
        return EventSubscription.of(MoneyClose.REGISTER_SUBSCRIPTION, MoneyClose.ACCOUNT_ERASED_EVENT,
            qbSponsorClosureRegister, MoneyClose::registerInput);
    }

    @Bean
    ProcessDefinition<MoneyClose.CloseInput, MoneyClose.CloseOutput, ProcessContext> qbSponsorMoneyClose() {
        return MoneyClose.close();
    }

    @Bean
    ProcessDefinition<MoneyClose.WithdrawInput, MoneyClose.WithdrawOutput, ProcessContext> qbSponsorCloseWithdraw() {
        return MoneyClose.withdraw();
    }

    /** A closure just registered is wound up at once (it records DEPOSIT_OPEN while its publications settle). */
    @Bean
    EventSubscription<MoneyClose.CloseInput> qbSponsorMoneyCloseSubscription(
        ProcessDefinition<MoneyClose.CloseInput, MoneyClose.CloseOutput, ProcessContext> qbSponsorMoneyClose) {
        return EventSubscription.of(MoneyClose.DUE_SUBSCRIPTION, MoneyClose.DUE_EVENT, qbSponsorMoneyClose,
            MoneyClose::closeInput);
    }

    /**
     * Every day ({@code quizbuks.erasure-close.register-cron}, {@code QB_ERASURE_CLOSE_REGISTER_CRON}; 03:47 in the fiscal
     * year's zone, before the wind-up's own job): registers the wind-up of every erased user who had the platform fee
     * account and has none (the erasure's event lost, review M1); a registration asks for its wind-up by its event.
     * The reconciliation's {@code ERASED_NO_CLOSURE} finds those this job did not.
     */
    /** The erasure's time as the template gives it: an instant (or its text); none: the registration's own time. */
    static java.time.Instant erasedTime(Object value) {
        return value == null ? null
            : value instanceof java.time.Instant at ? at : java.time.Instant.parse(String.valueOf(value));
    }

    @Bean
    JobDefinition<MoneyClose.RegisterInput> qbSponsorClosureRegisterJob(
        ProcessDefinition<MoneyClose.RegisterInput, MoneyClose.RegisterOutput, ProcessContext> qbSponsorClosureRegister,
        @Value("${quizbuks.erasure-close.register-cron:0 47 3 * * *}") String cron,
        @Value("${quizbuks.erasure-close.max-items:10000}") int maxItems) {
        return JobDefinition.forEach(MoneyClose.REGISTER_JOB, cron, zone,
                JobItems.template(MoneyClose.UNREGISTERED_TEMPLATE,
                    scheduledTime -> java.util.Map.of("at", scheduledTime.toString()), "userId"),
                qbSponsorClosureRegister,
                (scheduledTime, row) -> new MoneyClose.RegisterInput(String.valueOf(row.get("userId")),
                    erasedTime(row.get("erasedTime"))))
            .maxItems(maxItems);
    }

    /**
     * Every day ({@code quizbuks.erasure-close.cron}, {@code QB_ERASURE_CLOSE_CRON}; 03:53 in the fiscal year's zone,
     * after the distribution fee's final settlements and the prepayments' release): every closure not done, and those
     * done with fees owed and cash that arrived since or a postpaid fee not invoiced (§5.6), at most {@code quizbuks.erasure-close.max-items} a run.
     */
    @Bean
    JobDefinition<MoneyClose.CloseInput> qbSponsorMoneyCloseJob(
        ProcessDefinition<MoneyClose.CloseInput, MoneyClose.CloseOutput, ProcessContext> qbSponsorMoneyClose,
        @Value("${quizbuks.erasure-close.cron:0 53 3 * * *}") String cron,
        @Value("${quizbuks.erasure-close.max-items:10000}") int maxItems) {
        return JobDefinition.forEach(MoneyClose.JOB, cron, zone,
                JobItems.template(MoneyClose.DUE_TEMPLATE, scheduledTime -> java.util.Map.of(), "sponsorId"),
                qbSponsorMoneyClose,
                (scheduledTime, row) -> new MoneyClose.CloseInput(String.valueOf(row.get("sponsorId"))))
            .maxItems(maxItems);
    }
}
