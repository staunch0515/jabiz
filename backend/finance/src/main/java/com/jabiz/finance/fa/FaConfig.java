package com.jabiz.finance.fa;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.dictionary.StaticDictionary;
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
 * Registers the fixed asset module ({@link AssetEntities}, {@link DepreciationEntities}): classes, settings, the
 * register, the depreciation runs, changes in estimate, disposals and units used, and their processes.
 * Everything is written by its processes only.
 */
@Configuration
class FaConfig {

    @Bean
    EntityDefinition finAssetEntity() {
        return AssetEntities.ASSET_ENTITY;
    }

    @Bean
    EntityDefinition finAssetClassEntity() {
        return AssetEntities.CLASS_ENTITY;
    }

    @Bean
    EntityDefinition finFaSettingsEntity() {
        return AssetEntities.SETTINGS_ENTITY;
    }

    /**
     * A register brought over is checked against all assets of its numbers and accounts at once, and a run reads and
     * updates every asset in service at once: never cut short.
     */
    @Bean
    DatasetDefinition finAssetDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return DatasetDefinition.define(AssetEntities.ASSET_DATASET, d -> d
            .targetEntityType(AssetEntities.ASSET)
            .asDefault()
            .permissions(FinancePermissions.FA_READ, FinancePermissions.FA_MAINTAIN)
            .policy(p -> p.maxQueryBatchSize(DepreciationProcesses.BATCH).maxWriteBatchSize(DepreciationProcesses.BATCH)
                .processOnlyWrites())
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    @Bean
    DatasetDefinition finAssetClassDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return GlEntities.dataset(AssetEntities.ASSET_CLASS_DATASET, AssetEntities.ASSET_CLASS,
            FinancePermissions.FA_READ, FinancePermissions.FA_MAINTAIN, true, poolRef);
    }

    @Bean
    DatasetDefinition finFaSettingsDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return GlEntities.dataset(AssetEntities.SETTINGS_DATASET, AssetEntities.SETTINGS, FinancePermissions.FA_READ,
            FinancePermissions.FA_MAINTAIN, true, poolRef);
    }

    @Bean
    NumberSequence assetNumbers(@Value("${finance.fa.asset-numbers-start:1}") long first) {
        return AssetProcesses.assetNumbers(first);
    }

    @Bean
    ProcessDefinition<AssetProcesses.AssetInput, AssetProcesses.AssetOutput, ProcessContext> finAssetCreateProcess() {
        return AssetProcesses.CREATE_PROCESS;
    }

    @Bean
    ProcessDefinition<AssetProcesses.AcquireInput, AssetProcesses.AcquireOutput, ProcessContext>
        finFaAcquireProcess() {
        return AssetProcesses.ACQUIRE_PROCESS;
    }

    @Bean
    ProcessDefinition<AssetProcesses.SaveInput, AssetProcesses.SaveOutput, ProcessContext> finFaAssetSaveProcess() {
        return AssetProcesses.SAVE_PROCESS;
    }

    @Bean
    ProcessDefinition<AssetClassProcesses.ClassInput, AssetClassProcesses.ClassOutput, ProcessContext>
        finFaClassSaveProcess() {
        return AssetClassProcesses.CLASS_PROCESS;
    }

    @Bean
    ProcessDefinition<AssetClassProcesses.SettingsInput, AssetClassProcesses.SettingsOutput, ProcessContext>
        finFaSettingsSetProcess() {
        return AssetClassProcesses.SETTINGS_PROCESS;
    }

    @Bean
    ProcessDefinition<AssetOpeningProcesses.OpeningInput, AssetOpeningProcesses.OpeningOutput, ProcessContext>
        finFaOpeningProcess() {
        return AssetOpeningProcesses.OPENING_PROCESS;
    }

    @Bean
    EntityDefinition finDepreciationRunEntity() {
        return DepreciationEntities.RUN_ENTITY;
    }

    @Bean
    EntityDefinition finDepreciationLineEntity() {
        return DepreciationEntities.LINE_ENTITY;
    }

    @Bean
    EntityDefinition finAssetChangeEntity() {
        return DepreciationEntities.CHANGE_ENTITY;
    }

    @Bean
    EntityDefinition finAssetDisposalEntity() {
        return DepreciationEntities.DISPOSAL_ENTITY;
    }

    @Bean
    EntityDefinition finAssetUsageEntity() {
        return DepreciationEntities.USAGE_ENTITY;
    }

    @Bean
    DatasetDefinition finDepreciationRunDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return GlEntities.dataset(DepreciationEntities.RUN_DATASET, DepreciationEntities.RUN,
            FinancePermissions.FA_READ, FinancePermissions.FA_RUN, true, poolRef);
    }

    /** A run reads and writes a line for every asset at once. */
    @Bean
    DatasetDefinition finDepreciationLineDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return wide(DepreciationEntities.LINE_DATASET, DepreciationEntities.LINE, FinancePermissions.FA_RUN, poolRef);
    }

    @Bean
    DatasetDefinition finAssetChangeDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return wide(DepreciationEntities.CHANGE_DATASET, DepreciationEntities.CHANGE, FinancePermissions.FA_MAINTAIN,
            poolRef);
    }

    @Bean
    DatasetDefinition finAssetDisposalDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return GlEntities.dataset(DepreciationEntities.DISPOSAL_DATASET, DepreciationEntities.DISPOSAL,
            FinancePermissions.FA_READ, FinancePermissions.FA_MAINTAIN, true, poolRef);
    }

    @Bean
    DatasetDefinition finAssetUsageDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return wide(DepreciationEntities.USAGE_DATASET, DepreciationEntities.USAGE, FinancePermissions.FA_RUN,
            poolRef);
    }

    @Bean
    ProcessDefinition<DepreciationProcesses.RunInput, DepreciationProcesses.RunOutput, ProcessContext>
        finFaDepreciationRunProcess() {
        return DepreciationProcesses.RUN_PROCESS;
    }

    @Bean
    ProcessDefinition<DepreciationProcesses.ReverseInput, DepreciationProcesses.ReverseOutput, ProcessContext>
        finFaDepreciationReverseProcess() {
        return DepreciationProcesses.REVERSE_PROCESS;
    }

    @Bean
    ProcessDefinition<AssetEventProcesses.ChangeInput, AssetEventProcesses.ChangeOutput, ProcessContext>
        finFaChangeEstimateProcess() {
        return AssetEventProcesses.CHANGE_PROCESS;
    }

    @Bean
    ProcessDefinition<AssetEventProcesses.DisposeInput, AssetEventProcesses.DisposeOutput, ProcessContext>
        finFaDisposeProcess() {
        return AssetEventProcesses.DISPOSE_PROCESS;
    }

    @Bean
    ProcessDefinition<AssetEventProcesses.UsageInput, AssetEventProcesses.UsageOutput, ProcessContext>
        finFaUsageRecordProcess() {
        return AssetEventProcesses.USAGE_PROCESS;
    }

    @Bean
    StaticDictionary depreciationRunStatusDictionary() {
        return StaticDictionary.define(DepreciationEntities.RUN_STATUSES, d -> d
            .item(DepreciationEntities.POSTED, "en", "Posted")
            .item(DepreciationEntities.REVERSED, "en", "Reversed"));
    }

    @Bean
    StaticDictionary disposalKindDictionary() {
        return StaticDictionary.define(DepreciationEntities.DISPOSAL_KINDS, d -> d
            .item(DepreciationEntities.SALE, "en", "Sale")
            .item(DepreciationEntities.SCRAP, "en", "Scrapped")
            .item(DepreciationEntities.WRITE_OFF, "en", "Write-off"));
    }

    @Bean
    StaticDictionary depreciationMethodDictionary() {
        return StaticDictionary.define(AssetEntities.METHODS, d -> d
            .item(AssetEntities.SL, "en", "Straight-line")
            .item(AssetEntities.DDB, "en", "Double-declining balance")
            .item(AssetEntities.DB150, "en", "150% declining balance")
            .item(AssetEntities.UOP, "en", "Units of production"));
    }

    @Bean
    StaticDictionary depreciationConventionDictionary() {
        return StaticDictionary.define(AssetEntities.CONVENTIONS, d -> d
            .item(AssetEntities.FULL_MONTH, "en", "Full month")
            .item(AssetEntities.MID_MONTH, "en", "Mid-month")
            .item(AssetEntities.NEXT_MONTH, "en", "Next month"));
    }

    @Bean
    StaticDictionary assetStatusDictionary() {
        return StaticDictionary.define(AssetEntities.STATUSES, d -> d
            .item(AssetEntities.IN_SERVICE, "en", "In service")
            .item(AssetEntities.FULLY_DEPRECIATED, "en", "Fully depreciated")
            .item(AssetEntities.DISPOSED, "en", "Disposed"));
    }

    @Bean
    StaticDictionary assetSourceDictionary() {
        return StaticDictionary.define(AssetEntities.SOURCES, d -> d
            .item(AssetEntities.BILL, "en", "Bill")
            .item(AssetEntities.ACQUISITION, "en", "Acquisition")
            .item(AssetEntities.OPENING, "en", "Brought over at the cutover"));
    }

    /** Read and written for all assets of a run at once: never cut short. */
    private static DatasetDefinition wide(String id, String entity, String writePermission, String poolRef) {
        return DatasetDefinition.define(id, d -> d
            .targetEntityType(entity)
            .asDefault()
            .permissions(FinancePermissions.FA_READ, writePermission)
            .policy(p -> p.maxQueryBatchSize(DepreciationProcesses.BATCH).maxWriteBatchSize(DepreciationProcesses.BATCH)
                .processOnlyWrites())
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }
}
