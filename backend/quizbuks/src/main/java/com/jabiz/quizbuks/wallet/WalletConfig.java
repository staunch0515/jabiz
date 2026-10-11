package com.jabiz.quizbuks.wallet;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.dictionary.StaticDictionary;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.ledger.LedgerDimension;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.publishing.QbPublications;
import com.jabiz.runtime.security.SecurityEntities;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The ledger declarations of the wallets (docs/quizbuks/02-design.md section 4.1) and the wallets' balance cache
 * (Q5): the entity, its administrators' read-only dataset, the dictionary of wallet kinds and, in development only,
 * the credit that stands in for top-ups.
 */
@Configuration
class WalletConfig {

    private final String poolRef;

    WalletConfig(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.poolRef = poolRef;
    }

    /** The party of a wallet line: the user (sponsor or taker) whose wallet it moves, by user id (phase 16h). */
    @Bean
    LedgerDimension partyDimension() {
        return LedgerDimension.define(1, QbLedger.PARTY, d -> d.entity(SecurityEntities.USER, "userId"));
    }

    /**
     * The publication a reward line belongs to (Q5, D-Q5-4): a sponsor's spending and the summary vouchers add up by
     * publication from the ledger alone. Its key never changes.
     */
    @Bean
    LedgerDimension publicationDimension() {
        return LedgerDimension.define(2, QbWallets.PUBLICATION,
            d -> d.entity(QbPublications.PUBLICATION, "publicationId"));
    }

    @Bean
    EntityDefinition qbWalletEntity() {
        return QbWallets.WALLET_ENTITY;
    }

    /** Finance administrators read the wallets; only the processes write them (under the wallet's lock). */
    @Bean
    DatasetDefinition qbWalletDataset() {
        return DatasetDefinition.define(QbWallets.defaultDataset(QbWallets.WALLET), d -> d
            .targetEntityType(QbWallets.WALLET)
            .asDefault()
            .permissions(QbPermissions.ADMIN_FINANCE_READ, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.processOnlyWrites().maxWriteBatchSize(100))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    @Bean
    StaticDictionary qbWalletKindDictionary() {
        return StaticDictionary.define(QbWallets.KIND_DICTIONARY, d -> d
            .item(QbWallets.SPONSOR, "en", "Sponsor", "zh", "商家", "ja", "スポンサー")
            .item(QbWallets.TAKER, "en", "Quiz taker", "zh", "答题人", "ja", "回答者"));
    }

    @Bean
    @ConditionalOnProperty(name = DevCredit.ENABLED, havingValue = "true")
    ProcessDefinition<DevCredit.Input, DevCredit.Output, ProcessContext> qbWalletDevCredit() {
        return DevCredit.process();
    }
}
