package com.jabiz.quizbuks.fee;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.dictionary.StaticDictionary;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.event.EventSubscription;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.QbPermissions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** F-M15 b: the beans of an erased sponsor's money's wind-up (docs/quizbuks/plans/impl/F-M15.md §4.1, §5). */
@Configuration
public class MoneyCloseConfig {

    private final String poolRef;

    MoneyCloseConfig(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.poolRef = poolRef;
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
            .item(QbSponsorClosures.FEE_OWED, "en", "Fees still owed", "zh", "仍有欠费", "ja", "未払いの料金あり"));
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
}
