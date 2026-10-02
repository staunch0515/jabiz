package com.jabiz.finance.bank;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.finance.FinancePermissions;
import com.jabiz.finance.gl.GlEntities;
import com.jabiz.numbering.NumberSequence;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the bank module: the company's bank accounts ({@link BankEntities}), the bank settings, transfers
 * ({@link TransferEntities}), statements and the cutover's outstanding items ({@link StatementEntities}), matches and
 * entries from statement lines ({@link MatchEntities}). Everything
 * but the bank accounts' master data is written by its processes only.
 */
@Configuration
class BankConfig {

    @Bean
    EntityDefinition finBankAccountEntity() {
        return BankEntities.BANK_ACCOUNT_ENTITY;
    }

    @Bean
    EntityDefinition finBankSettingsEntity() {
        return BankEntities.SETTINGS_ENTITY;
    }

    @Bean
    EntityDefinition finBankTransferEntity() {
        return TransferEntities.TRANSFER_ENTITY;
    }

    @Bean
    EntityDefinition finBankStatementEntity() {
        return StatementEntities.STATEMENT_ENTITY;
    }

    @Bean
    EntityDefinition finStatementLineEntity() {
        return StatementEntities.LINE_ENTITY;
    }

    @Bean
    EntityDefinition finBankOpeningEntity() {
        return StatementEntities.OPENING_ENTITY;
    }

    @Bean
    EntityDefinition finBankOpeningItemEntity() {
        return StatementEntities.OPENING_ITEM_ENTITY;
    }

    /** Every finance user reads them, as other master data; the number in plain only with {@code fin.bank.read}. */
    @Bean
    DatasetDefinition finBankAccountDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return GlEntities.dataset(BankEntities.BANK_ACCOUNT_DATASET, BankEntities.BANK_ACCOUNT,
            FinancePermissions.MASTER_READ, FinancePermissions.BANK_MAINTAIN, true, poolRef);
    }

    @Bean
    DatasetDefinition finBankSettingsDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return GlEntities.dataset(BankEntities.SETTINGS_DATASET, BankEntities.SETTINGS, FinancePermissions.MASTER_READ,
            FinancePermissions.BANK_SETTINGS, true, poolRef);
    }

    @Bean
    DatasetDefinition finBankTransferDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return GlEntities.dataset(TransferEntities.TRANSFER_DATASET, TransferEntities.TRANSFER,
            FinancePermissions.BANK_ACTIVITY_READ, FinancePermissions.BANK_TRANSFER, true, poolRef);
    }

    @Bean
    DatasetDefinition finBankStatementDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return GlEntities.dataset(StatementEntities.STATEMENT_DATASET, StatementEntities.STATEMENT,
            FinancePermissions.BANK_ACTIVITY_READ, FinancePermissions.BANK_STATEMENT_IMPORT, true, poolRef);
    }

    @Bean
    DatasetDefinition finStatementLineDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        // A statement of up to 5,000 lines looks all of them up at once among those stored before.
        return DatasetDefinition.define(StatementEntities.LINE_DATASET, d -> d
            .targetEntityType(StatementEntities.LINE)
            .asDefault()
            .permissions(FinancePermissions.BANK_ACTIVITY_READ, FinancePermissions.BANK_STATEMENT_IMPORT)
            .policy(p -> p.maxQueryBatchSize(StatementProcesses.MAX_LINES + 1).processOnlyWrites())
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    @Bean
    DatasetDefinition finBankOpeningDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return GlEntities.dataset(StatementEntities.OPENING_DATASET, StatementEntities.OPENING,
            FinancePermissions.BANK_ACTIVITY_READ, FinancePermissions.MIGRATION, true, poolRef);
    }

    @Bean
    DatasetDefinition finBankOpeningItemDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return GlEntities.dataset(StatementEntities.OPENING_ITEM_DATASET, StatementEntities.OPENING_ITEM,
            FinancePermissions.BANK_ACTIVITY_READ, FinancePermissions.MIGRATION, true, poolRef);
    }

    @Bean
    EntityDefinition finBankMatchEntity() {
        return MatchEntities.MATCH_ENTITY;
    }

    @Bean
    EntityDefinition finBankMatchItemEntity() {
        return MatchEntities.ITEM_ENTITY;
    }

    @Bean
    EntityDefinition finBankEntryRuleEntity() {
        return MatchEntities.RULE_ENTITY;
    }

    @Bean
    EntityDefinition finBankEntryEntity() {
        return MatchEntities.ENTRY_ENTITY;
    }

    @Bean
    DatasetDefinition finBankMatchDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return GlEntities.dataset(MatchEntities.MATCH_DATASET, MatchEntities.MATCH,
            FinancePermissions.BANK_ACTIVITY_READ, FinancePermissions.BANK_RECONCILE, true, poolRef);
    }

    @Bean
    DatasetDefinition finBankMatchItemDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        // A reference's next round is read from all its items before: never cut short.
        return DatasetDefinition.define(MatchEntities.ITEM_DATASET, d -> d
            .targetEntityType(MatchEntities.ITEM)
            .asDefault()
            .permissions(FinancePermissions.BANK_ACTIVITY_READ, FinancePermissions.BANK_RECONCILE)
            .policy(p -> p.maxQueryBatchSize(MatchProcesses.MAX_ITEMS).processOnlyWrites())
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    @Bean
    DatasetDefinition finBankEntryRuleDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return GlEntities.dataset(MatchEntities.RULE_DATASET, MatchEntities.RULE, FinancePermissions.MASTER_READ,
            FinancePermissions.BANK_SETTINGS, true, poolRef);
    }

    @Bean
    DatasetDefinition finBankEntryDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return GlEntities.dataset(MatchEntities.ENTRY_DATASET, MatchEntities.ENTRY,
            FinancePermissions.BANK_ACTIVITY_READ, FinancePermissions.BANK_RECONCILE, true, poolRef);
    }

    @Bean
    ProcessDefinition<MatchProcesses.ProposeInput, MatchProcesses.ProposeOutput, ProcessContext>
        finBankMatchProposeProcess() {
        return MatchProcesses.PROPOSE_PROCESS;
    }

    @Bean
    ProcessDefinition<MatchProcesses.MatchInput, MatchProcesses.MatchOutput, ProcessContext> finBankMatchProcess() {
        return MatchProcesses.MATCH_PROCESS;
    }

    @Bean
    ProcessDefinition<MatchProcesses.AcceptInput, MatchProcesses.AcceptOutput, ProcessContext>
        finBankMatchAcceptProcess() {
        return MatchProcesses.ACCEPT_PROCESS;
    }

    @Bean
    ProcessDefinition<MatchProcesses.UnmatchInput, MatchProcesses.UnmatchOutput, ProcessContext>
        finBankUnmatchProcess() {
        return MatchProcesses.UNMATCH_PROCESS;
    }

    @Bean
    ProcessDefinition<BankEntryProcesses.RuleInput, BankEntryProcesses.RuleOutput, ProcessContext>
        finBankEntryRuleSaveProcess() {
        return BankEntryProcesses.RULE_PROCESS;
    }

    @Bean
    ProcessDefinition<BankEntryProcesses.EntryInput, BankEntryProcesses.EntryOutput, ProcessContext>
        finBankEntryFromLineProcess() {
        return BankEntryProcesses.ENTRY_PROCESS;
    }

    @Bean
    NumberSequence bankEntryNumbers() {
        return BankEntryProcesses.numbers();
    }

    @Bean
    NumberSequence bankTransferNumbers() {
        return TransferProcesses.numbers();
    }

    @Bean
    ProcessDefinition<BankAccountProcesses.BankInput, BankAccountProcesses.BankOutput, ProcessContext>
        finBankAccountSaveProcess() {
        return BankAccountProcesses.SAVE_PROCESS;
    }

    @Bean
    ProcessDefinition<BankSettingsProcesses.SettingsInput, BankSettingsProcesses.SettingsOutput, ProcessContext>
        finBankSettingsSetProcess() {
        return BankSettingsProcesses.SET_PROCESS;
    }

    @Bean
    ProcessDefinition<TransferProcesses.TransferInput, TransferProcesses.TransferOutput, ProcessContext>
        finBankTransferPostProcess() {
        return TransferProcesses.POST_PROCESS;
    }

    @Bean
    ProcessDefinition<TransferProcesses.ReceiveInput, TransferProcesses.TransferOutput, ProcessContext>
        finBankTransferReceiveProcess() {
        return TransferProcesses.RECEIVE_PROCESS;
    }

    @Bean
    ProcessDefinition<TransferProcesses.VoidInput, TransferProcesses.TransferOutput, ProcessContext>
        finBankTransferVoidProcess() {
        return TransferProcesses.VOID_PROCESS;
    }

    @Bean
    ProcessDefinition<StatementProcesses.StatementInput, StatementProcesses.StatementOutput, ProcessContext>
        finBankStatementRecordProcess() {
        return StatementProcesses.RECORD_PROCESS;
    }

    @Bean
    ProcessDefinition<StatementProcesses.OpeningInput, StatementProcesses.OpeningOutput, ProcessContext>
        finBankOpeningItemsProcess() {
        return StatementProcesses.OPENING_PROCESS;
    }
}
