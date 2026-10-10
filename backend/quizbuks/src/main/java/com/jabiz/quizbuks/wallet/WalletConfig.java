package com.jabiz.quizbuks.wallet;

import com.jabiz.ledger.LedgerDimension;
import com.jabiz.runtime.security.SecurityEntities;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The ledger declarations of the wallets (docs/quizbuks/02-design.md section 4.1). */
@Configuration
class WalletConfig {

    /** The party of a wallet line: the user (sponsor or taker) whose wallet it moves, by user id (phase 16h). */
    @Bean
    LedgerDimension partyDimension() {
        return LedgerDimension.define(1, QbLedger.PARTY, d -> d.entity(SecurityEntities.USER, "userId"));
    }
}
