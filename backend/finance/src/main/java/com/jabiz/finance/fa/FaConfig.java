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
 * Registers the fixed asset module ({@link AssetEntities}): classes, settings and the register, and their processes.
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

    /** A register brought over is checked against all assets of its numbers and accounts at once: never cut short. */
    @Bean
    DatasetDefinition finAssetDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return DatasetDefinition.define(AssetEntities.ASSET_DATASET, d -> d
            .targetEntityType(AssetEntities.ASSET)
            .asDefault()
            .permissions(FinancePermissions.FA_READ, FinancePermissions.FA_MAINTAIN)
            .policy(p -> p.maxQueryBatchSize(AssetOpeningProcesses.MAX_ITEMS * 2 + 1).processOnlyWrites())
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
}
