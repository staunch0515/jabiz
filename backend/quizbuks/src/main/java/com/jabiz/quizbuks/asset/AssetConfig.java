package com.jabiz.quizbuks.asset;

import com.jabiz.context.RequestContext;
import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.dictionary.StaticDictionary;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.operator.OperatorScopes;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the sponsors' asset library (F-C1): the two entities, the administrators' datasets (support reads them;
 * an operator its own sponsors', Q59c-2; written by the processes only), the sponsor's own read-only ones, the
 * dictionaries and the processes.
 */
@Configuration
public class AssetConfig {

    private final String poolRef;

    AssetConfig(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.poolRef = poolRef;
    }

    @Bean
    EntityDefinition qbAssetEntity() {
        return QbAssets.ASSET_ENTITY;
    }

    @Bean
    EntityDefinition qbAssetUseEntity() {
        return QbAssets.USE_ENTITY;
    }

    /** A collection writes up to {@link AssetRules#MAX_COLLECT} assets at once. */
    @Bean
    DatasetDefinition qbAssetDataset() {
        return defaultDataset(QbAssets.DEFAULT_DATASET, QbAssets.ASSET, AssetRules.MAX_COLLECT);
    }

    /** Deleting an asset deletes every record of where it was copied to. */
    @Bean
    DatasetDefinition qbAssetUseDataset() {
        return defaultDataset(QbAssets.USE_DATASET, QbAssets.USE, 10_000);
    }

    @Bean
    DatasetDefinition qbMyAssetDataset() {
        return myDataset(QbAssets.MY_DATASET, QbAssets.ASSET);
    }

    @Bean
    DatasetDefinition qbMyAssetUseDataset() {
        return myDataset(QbAssets.MY_USE_DATASET, QbAssets.USE);
    }

    private DatasetDefinition defaultDataset(String id, String entity, int maxWriteBatch) {
        return DatasetDefinition.define(id, d -> d
            .targetEntityType(entity)
            .asDefault()
            .permissions(QbPermissions.ADMIN_PUBLICATION_READ, QbPermissions.SPONSOR_VIEW_WRITE)
            .scope(OperatorScopes.of(entity))
            .policy(p -> p.processOnlyWrites().maxWriteBatchSize(maxWriteBatch))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    /** The sponsor's own rows; read only (the write permission is one no role holds). */
    private DatasetDefinition myDataset(String id, String entity) {
        return DatasetDefinition.define(id, d -> d
            .targetEntityType(entity)
            .permissions(QbPermissions.SPONSOR_ME, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.readOnly(true).allowTimeTravel(false))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef))
            .scope(s -> s.fromContext("sponsorId", "actorId", RequestContext::actorId)));
    }

    @Bean
    StaticDictionary qbAssetKindDictionary() {
        return StaticDictionary.define(QbAssets.KIND_DICTIONARY, d -> d
            .item(AssetKind.IMAGE.name(), "en", "Image", "zh", "图片", "ja", "画像")
            .item(AssetKind.PDF.name(), "en", "PDF", "zh", "PDF", "ja", "PDF")
            .item(AssetKind.AUDIO.name(), "en", "Audio", "zh", "音频", "ja", "音声")
            .item(AssetKind.VIDEO_LINK.name(), "en", "Video link", "zh", "视频链接", "ja", "動画リンク")
            .item(AssetKind.LINK.name(), "en", "Web link", "zh", "网页链接", "ja", "Webリンク")
            .item(AssetKind.ARTICLE.name(), "en", "Article", "zh", "文章", "ja", "記事")
            .item(AssetKind.TEXT.name(), "en", "Text", "zh", "文字", "ja", "テキスト")
            .item(AssetKind.PRIZE.name(), "en", "Prize card", "zh", "奖品卡片", "ja", "賞品カード"));
    }

    @Bean
    StaticDictionary qbAssetTextUsageDictionary() {
        return StaticDictionary.define(QbAssets.USAGE_DICTIONARY, d -> d
            .item(AssetRules.QUIZ_INTRO, "en", "Quiz introduction", "zh", "模版简介", "ja", "クイズの紹介")
            .item(AssetRules.REDEEM_DESCRIPTION, "en", "In-store prize description", "zh", "到店兑换说明",
                "ja", "店頭引き換えの説明")
            .item(AssetRules.DIGITAL_DESCRIPTION, "en", "Digital prize description", "zh", "电子奖品说明",
                "ja", "デジタル賞品の説明")
            .item(AssetRules.REFERRAL_DESCRIPTION, "en", "Referral prize description", "zh", "推荐奖励说明",
                "ja", "紹介特典の説明"));
    }

    @Bean
    StaticDictionary qbAssetUseTargetDictionary() {
        return StaticDictionary.define(QbAssets.TARGET_DICTIONARY, d -> d
            .item(QbAssets.MATERIAL, "en", "Material", "zh", "资料", "ja", "資料")
            .item(QbAssets.QUESTION_IMAGE, "en", "Question image", "zh", "题目图片", "ja", "問題の画像")
            .item(QbAssets.OPTION_IMAGE, "en", "Option image", "zh", "选项图片", "ja", "選択肢の画像")
            .item(QbAssets.QUIZ_COVER, "en", "Quiz cover", "zh", "模版封面", "ja", "クイズのカバー")
            .item(QbAssets.QUIZ_INTRO, "en", "Quiz introduction", "zh", "模版简介", "ja", "クイズの紹介")
            .item(QbAssets.PUBLICATION_COVER, "en", "Publication cover", "zh", "发布封面", "ja", "公開のカバー")
            .item(QbAssets.PUBLICATION_REDEEM, "en", "In-store prize description", "zh", "到店兑换说明",
                "ja", "店頭引き換えの説明")
            .item(QbAssets.PUBLICATION_DIGITAL, "en", "Digital prize description", "zh", "电子奖品说明",
                "ja", "デジタル賞品の説明")
            .item(QbAssets.PUBLICATION_REFERRAL, "en", "Referral prize description", "zh", "推荐奖励说明",
                "ja", "紹介特典の説明")
            .item(QbAssets.PUBLICATION_PRIZE, "en", "Publication prize", "zh", "发布奖品", "ja", "公開の賞品"));
    }

    @Bean
    ProcessDefinition<AssetProcesses.SaveInput, AssetProcesses.AssetOutput, ProcessContext> qbAssetSave() {
        return AssetProcesses.save();
    }

    @Bean
    ProcessDefinition<AssetProcesses.DeleteInput, AssetProcesses.DeleteOutput, ProcessContext> qbAssetDelete() {
        return AssetProcesses.delete();
    }

    @Bean
    ProcessDefinition<AssetProcesses.CollectInput, AssetProcesses.CollectOutput, ProcessContext> qbAssetCollect() {
        return AssetProcesses.collect();
    }
}
