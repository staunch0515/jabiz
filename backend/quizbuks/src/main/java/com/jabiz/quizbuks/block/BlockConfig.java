package com.jabiz.quizbuks.block;

import com.jabiz.context.RequestContext;
import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.dictionary.StaticDictionary;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.QbPermissions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers blocking (F-S8): the entity, its default dataset (written by the processes only, read directly by no role:
 * blocks are private; templates count them for sponsors and headquarters, rounded or aggregated), the caller's own
 * dataset for the takers' templates, the dictionary of kinds and the two processes.
 */
@Configuration
public class BlockConfig {

    private final String poolRef;

    BlockConfig(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.poolRef = poolRef;
    }

    @Bean
    EntityDefinition qbBlockEntity() {
        return QbBlocks.ENTITY;
    }

    @Bean
    DatasetDefinition qbBlockDataset() {
        return DatasetDefinition.define(QbBlocks.DEFAULT_DATASET, d -> d
            .targetEntityType(QbBlocks.BLOCK)
            .asDefault()
            .permissions(QbPermissions.SPONSOR_VIEW_WRITE, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.processOnlyWrites())
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    /** The caller's own blocks (read only), for the takers' templates. */
    @Bean
    DatasetDefinition qbMyBlockDataset() {
        return DatasetDefinition.define(QbBlocks.MY_DATASET, d -> d
            .targetEntityType(QbBlocks.BLOCK)
            .permissions(QbPermissions.PLAY, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.readOnly(true).allowTimeTravel(false))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef))
            .scope(s -> s.fromContext("userId", "actorId", RequestContext::actorId)));
    }

    @Bean
    StaticDictionary qbBlockKindDictionary() {
        return StaticDictionary.define(QbBlocks.KIND_DICTIONARY, d -> d
            .item(BlockRules.PUBLICATION, "en", "Publication", "zh", "发布", "ja", "公開")
            .item(BlockRules.QUIZ, "en", "Quiz", "zh", "Quiz", "ja", "クイズ")
            .item(BlockRules.SPONSOR, "en", "Sponsor", "zh", "发起方", "ja", "主催者")
            .item(BlockRules.PROVIDER, "en", "Video provider", "zh", "视频制作方", "ja", "動画の提供者"));
    }

    @Bean
    ProcessDefinition<BlockProcesses.AddInput, BlockProcesses.BlockOutput, ProcessContext> qbBlockAdd() {
        return BlockProcesses.add();
    }

    @Bean
    ProcessDefinition<BlockProcesses.RemoveInput, BlockProcesses.BlockOutput, ProcessContext> qbBlockRemove() {
        return BlockProcesses.remove();
    }
}
