package com.jabiz.finance.bank;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.finance.FinancePermissions;
import com.jabiz.finance.gl.GlEntities;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the company's bank accounts ({@link BankEntities}). */
@Configuration
class BankConfig {

    @Bean
    EntityDefinition finBankAccountEntity() {
        return BankEntities.BANK_ACCOUNT_ENTITY;
    }

    /** Every finance user reads them, as other master data; the number in plain only with {@code fin.bank.read}. */
    @Bean
    DatasetDefinition finBankAccountDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return GlEntities.dataset(BankEntities.BANK_ACCOUNT_DATASET, BankEntities.BANK_ACCOUNT,
            FinancePermissions.MASTER_READ, FinancePermissions.BANK_MAINTAIN, true, poolRef);
    }

    @Bean
    ProcessDefinition<BankAccountProcesses.BankInput, BankAccountProcesses.BankOutput, ProcessContext>
        finBankAccountSaveProcess() {
        return BankAccountProcesses.SAVE_PROCESS;
    }
}
