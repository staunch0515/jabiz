package com.jabiz.finance.setup;

import com.jabiz.finance.ap.PaymentEntities;
import com.jabiz.finance.bank.ReconciliationEntities;
import com.jabiz.finance.close.CloseEntities;
import com.jabiz.finance.gl.JournalEntities;
import com.jabiz.finance.gl.PeriodBalances;
import com.jabiz.retention.RetentionPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Period;

/**
 * The retention of the books (FIN-CT-020; ROADMAP F10 decision D6): seven years after the end of the fiscal year a
 * record belongs to, the platform refusing its deletion before (422 {@code RETENTION_ACTIVE}) and while a legal hold
 * names it (docs/design/21-audit-retention.md section 3). Only records kept once written: a policy keeps a record
 * without its date too, so documents that start as drafts (journal entries, invoices, bills) have none; posted, they
 * are never deleted, and their postings, ledger transactions and issued copies are kept here and by the platform.
 */
@Configuration
class RetentionConfig {

    static final Period KEEP = Period.ofYears(7);

    @Bean
    RetentionPolicy finPostingRetention() {
        return RetentionPolicy.of(JournalEntities.POSTING).keep(KEEP).from("postingDate").afterFiscalYearEnd();
    }

    @Bean
    RetentionPolicy finPaymentRetention() {
        return RetentionPolicy.of(PaymentEntities.PAYMENT).keep(KEEP).from("paymentDate").afterFiscalYearEnd();
    }

    @Bean
    RetentionPolicy finBankReconciliationRetention() {
        return RetentionPolicy.of(ReconciliationEntities.RECONCILIATION).keep(KEEP).from("statementDate")
            .afterFiscalYearEnd();
    }

    @Bean
    RetentionPolicy finCloseArtifactRetention() {
        return RetentionPolicy.of(CloseEntities.ARTIFACT).keep(KEEP).from("periodEnd").afterFiscalYearEnd();
    }

    @Bean
    RetentionPolicy finYearCloseRetention() {
        return RetentionPolicy.of(CloseEntities.YEAR_CLOSE).keep(KEEP).from("closedAt").afterFiscalYearEnd();
    }

    @Bean
    RetentionPolicy finPeriodBalanceRetention() {
        return RetentionPolicy.of(PeriodBalances.BALANCE).keep(KEEP).from("countedTo").afterFiscalYearEnd();
    }
}
