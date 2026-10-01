package com.jabiz.finance.fa;

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

/** Registers the fixed asset register ({@link AssetEntities}). */
@Configuration
class FaConfig {

    @Bean
    EntityDefinition finAssetEntity() {
        return AssetEntities.ASSET_ENTITY;
    }

    /** Read as payables are; written only by the bills that capitalize (F4b) and, from F6, the asset processes. */
    @Bean
    DatasetDefinition finAssetDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return GlEntities.dataset(AssetEntities.ASSET_DATASET, AssetEntities.ASSET, FinancePermissions.AP_READ,
            FinancePermissions.AP_INTERNAL, true, poolRef);
    }

    @Bean
    NumberSequence assetNumbers(@Value("${finance.fa.asset-numbers-start:1}") long first) {
        return AssetProcesses.assetNumbers(first);
    }

    @Bean
    ProcessDefinition<AssetProcesses.AssetInput, AssetProcesses.AssetOutput, ProcessContext> finAssetCreateProcess() {
        return AssetProcesses.CREATE_PROCESS;
    }
}
