package com.jabiz.quizbuks.wallet;

import com.jabiz.context.RequestContext;
import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.runtime.ledger.LedgerEntities;
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

    /**
     * The business parameters as in effect now, read only, for the pages' templates that apply the processes' rules
     * ({@code qb.app.wallet}, {@code qb.sponsor.topup-limit}; Q10b, review L9): without time travel a request cannot
     * ask for another point in time, so they read the parameters at the server's clock, as the processes do
     * ({@code LoadParams} at the operation time). Its permissions are the platform's own dataset's.
     */
    @Bean
    DatasetDefinition qbParamsNowDataset() {
        return DatasetDefinition.define(QbWallets.PARAMS_NOW, d -> d
            .targetEntityType(com.jabiz.runtime.param.ParamEntities.ENTITY)
            .permissions(com.jabiz.runtime.param.ParamPermissions.READ, com.jabiz.runtime.param.ParamPermissions.WRITE)
            .policy(p -> p.readOnly(true).allowTimeTravel(false))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    /** A sponsor's own wallet, read only, for the wallet's template (Q6a). */
    @Bean
    DatasetDefinition qbSponsorWalletDataset() {
        return DatasetDefinition.define(QbWallets.SPONSOR_WALLETS, d -> d
            .targetEntityType(QbWallets.WALLET)
            .permissions(QbPermissions.SPONSOR_FINANCE_READ, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.readOnly(true).allowTimeTravel(false))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef))
            .scope(s -> s.fromContext("ownerId", "actorId", RequestContext::actorId)));
    }

    /**
     * The caller's own ledger lines (the party is the caller: {@code dimension1}, indexed), read only: a taker's
     * rewards and transfers (Q6a's wallet template) and a sponsor's top-ups and spending. The ledger's own dataset is
     * for whoever reads the whole ledger ({@code ledger.read}).
     */
    @Bean
    DatasetDefinition qbTakerLedgerDataset() {
        return partyLedger(QbWallets.TAKER_LEDGER, QbPermissions.ME);
    }

    @Bean
    DatasetDefinition qbSponsorLedgerDataset() {
        return partyLedger(QbWallets.SPONSOR_LEDGER, QbPermissions.SPONSOR_FINANCE_READ);
    }

    private DatasetDefinition partyLedger(String id, String readPermission) {
        return DatasetDefinition.define(id, d -> d
            .targetEntityType(LedgerEntities.ENTRY)
            .permissions(readPermission, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.readOnly(true).allowTimeTravel(false))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef))
            .scope(s -> s.fromContext("dimension1", "actorId", RequestContext::actorId)));
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
