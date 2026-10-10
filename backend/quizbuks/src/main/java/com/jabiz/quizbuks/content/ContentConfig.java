package com.jabiz.quizbuks.content;

import com.jabiz.context.RequestContext;
import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.dictionary.StaticDictionary;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.file.FilePolicy;
import com.jabiz.file.MediaTypes;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.QbPermissions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers quiz content (docs/quizbuks/plans/Q3-content.md): the seven entities, two datasets each, the two
 * dictionaries, the three file policies and the ten processes.
 *
 * <p>Each entity has a default dataset, read only by content administrators ({@code qb.admin.quiz.read}) and written
 * by the processes only ({@code processOnlyWrites}): the platform's reference checks and the processes' writes go
 * through it. A sponsor reads their own rows through the sponsor dataset ({@code ownerId} = the caller; of quizzes,
 * not removed ones), read only and without history. Nobody writes through a dataset: only the processes do.
 *
 * <p>Versions and version files have no sponsor dataset: a version outlives its quiz (publications refer to it) and
 * its content holds the correct answers, but a dataset's scope cannot say "of a quiz that is not removed". A sponsor
 * reads versions through the templates {@code qb.sponsor.quiz-versions} and {@code qb.sponsor.quiz-version}, which
 * join the sponsor's quizzes; the parts of a removed quiz are tombstoned, so its other datasets show nothing either.
 */
@Configuration
public class ContentConfig {

    private final String poolRef;

    ContentConfig(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.poolRef = poolRef;
    }

    private DatasetDefinition defaultDataset(String entity, int maxWriteBatch) {
        return DatasetDefinition.define(QbContent.defaultDataset(entity), d -> d
            .targetEntityType(entity)
            .asDefault()
            .permissions(QbPermissions.ADMIN_QUIZ_READ, QbPermissions.CONTENT_WRITE)
            // Cloning and removing a quiz write all its parts at once.
            .policy(p -> p.processOnlyWrites().maxWriteBatchSize(maxWriteBatch))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    private DatasetDefinition sponsorDataset(String entity) {
        return DatasetDefinition.define(QbContent.sponsorDataset(entity), d -> {
            // Read only: the write permission is one no role holds, so making it writable later grants nothing.
            d.targetEntityType(entity)
                .permissions(QbPermissions.CONTENT_WRITE, QbPermissions.SPONSOR_VIEW_WRITE)
                .policy(p -> p.readOnly(true).allowTimeTravel(false))
                .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef));
            d.scope(s -> {
                s.fromContext("ownerId", "actorId", RequestContext::actorId);
                if (QbContent.QUIZ.equals(entity)) {
                    s.fixed("removed", false);
                }
            });
        });
    }

    @Bean
    EntityDefinition qbQuizEntity() {
        return QbContent.QUIZ_ENTITY;
    }

    @Bean
    EntityDefinition qbMaterialEntity() {
        return QbContent.MATERIAL_ENTITY;
    }

    @Bean
    EntityDefinition qbMaterialImageEntity() {
        return QbContent.MATERIAL_IMAGE_ENTITY;
    }

    @Bean
    EntityDefinition qbQuestionEntity() {
        return QbContent.QUESTION_ENTITY;
    }

    @Bean
    EntityDefinition qbOptionEntity() {
        return QbContent.OPTION_ENTITY;
    }

    @Bean
    EntityDefinition qbQuizVersionEntity() {
        return QbContent.VERSION_ENTITY;
    }

    @Bean
    EntityDefinition qbVersionFileEntity() {
        return QbContent.VERSION_FILE_ENTITY;
    }

    @Bean
    DatasetDefinition qbQuizDataset() {
        return defaultDataset(QbContent.QUIZ, 100);
    }

    @Bean
    DatasetDefinition qbMaterialDataset() {
        return defaultDataset(QbContent.MATERIAL, 100);
    }

    @Bean
    DatasetDefinition qbMaterialImageDataset() {
        return defaultDataset(QbContent.MATERIAL_IMAGE, ContentLimits.MAX_MATERIALS * ContentLimits.MAX_IMAGES);
    }

    @Bean
    DatasetDefinition qbQuestionDataset() {
        return defaultDataset(QbContent.QUESTION, ContentLimits.MAX_QUESTIONS);
    }

    @Bean
    DatasetDefinition qbOptionDataset() {
        return defaultDataset(QbContent.OPTION, ContentLimits.MAX_QUESTIONS * ContentLimits.MAX_OPTIONS);
    }

    @Bean
    DatasetDefinition qbQuizVersionDataset() {
        return defaultDataset(QbContent.VERSION, 100);
    }

    @Bean
    DatasetDefinition qbVersionFileDataset() {
        // A version registers at most MAX_FILES_PER_VERSION files; a removal releases them in chunks of this size.
        return defaultDataset(QbContent.VERSION_FILE, ContentLimits.RELEASE_CHUNK);
    }

    @Bean
    DatasetDefinition qbSponsorQuizDataset() {
        return sponsorDataset(QbContent.QUIZ);
    }

    @Bean
    DatasetDefinition qbSponsorMaterialDataset() {
        return sponsorDataset(QbContent.MATERIAL);
    }

    @Bean
    DatasetDefinition qbSponsorMaterialImageDataset() {
        return sponsorDataset(QbContent.MATERIAL_IMAGE);
    }

    @Bean
    DatasetDefinition qbSponsorQuestionDataset() {
        return sponsorDataset(QbContent.QUESTION);
    }

    @Bean
    DatasetDefinition qbSponsorOptionDataset() {
        return sponsorDataset(QbContent.OPTION);
    }

    @Bean
    StaticDictionary qbQuizStatusDictionary() {
        return StaticDictionary.define(QbContent.STATUS_DICTIONARY, d -> d
            .item(QbContent.DRAFT, "en", "Draft", "zh", "草稿", "ja", "下書き")
            .item(QbContent.VERSIONED, "en", "Versioned", "zh", "已保存版本", "ja", "バージョン保存済み"));
    }

    @Bean
    StaticDictionary qbMaterialKindDictionary() {
        return StaticDictionary.define(QbContent.KIND_DICTIONARY, d -> d
            .item(MaterialKind.ARTICLE.name(), "en", "Article", "zh", "文章", "ja", "記事")
            .item(MaterialKind.LINK.name(), "en", "Link", "zh", "链接", "ja", "リンク")
            .item(MaterialKind.VIDEO_LINK.name(), "en", "Video link", "zh", "视频链接", "ja", "動画リンク")
            .item(MaterialKind.PDF.name(), "en", "PDF", "zh", "PDF", "ja", "PDF")
            .item(MaterialKind.IMAGES.name(), "en", "Images", "zh", "图片组", "ja", "画像セット")
            .item(MaterialKind.AUDIO.name(), "en", "Audio", "zh", "音频", "ja", "音声"));
    }

    /** Covers, question and option images, material images: re-encoded without metadata, in three widths. */
    @Bean
    FilePolicy qbContentImagePolicy() {
        return FilePolicy.define(QbContent.IMAGE_POLICY)
            .allow(MediaTypes.JPEG, MediaTypes.PNG)
            .maxBytes(10 * FilePolicy.MB)
            .image(i -> i.maxPixels(40_000_000).variants(320, 640, 1280))
            .permissions(QbPermissions.CONTENT_WRITE, QbPermissions.CONTENT_FILE_READ)
            .build();
    }

    @Bean
    FilePolicy qbContentPdfPolicy() {
        return FilePolicy.define(QbContent.PDF_POLICY)
            .allow(MediaTypes.PDF)
            .maxBytes(20 * FilePolicy.MB)
            .permissions(QbPermissions.CONTENT_WRITE, QbPermissions.CONTENT_FILE_READ)
            .build();
    }

    @Bean
    FilePolicy qbContentAudioPolicy() {
        return FilePolicy.define(QbContent.AUDIO_POLICY)
            .allow(MediaTypes.MP3, MediaTypes.M4A, MediaTypes.OGG)
            .maxBytes(50 * FilePolicy.MB)
            .permissions(QbPermissions.CONTENT_WRITE, QbPermissions.CONTENT_FILE_READ)
            .build();
    }

    @Bean
    ProcessDefinition<QuizProcesses.QuizInput, EditOutput, ProcessContext> qbQuizSave() {
        return QuizProcesses.save();
    }

    @Bean
    ProcessDefinition<QuizProcesses.QuizRef, EditOutput, ProcessContext> qbQuizDelete() {
        return QuizProcesses.delete();
    }

    @Bean
    ProcessDefinition<QuizProcesses.CloneInput, QuizProcesses.CloneOutput, ProcessContext> qbQuizClone() {
        return QuizProcesses.cloneQuiz();
    }

    @Bean
    ProcessDefinition<QuizProcesses.ReleaseInput, QuizProcesses.ReleaseOutput, ProcessContext> qbQuizReleaseFiles() {
        return QuizProcesses.releaseFiles();
    }

    @Bean
    ProcessDefinition<QuizProcesses.QuizRef, QuizProcesses.VersionOutput, ProcessContext> qbQuizPublishVersion() {
        return QuizProcesses.publishVersion();
    }

    @Bean
    ProcessDefinition<QuestionProcesses.QuestionInput, EditOutput, ProcessContext> qbQuestionSave() {
        return QuestionProcesses.save();
    }

    @Bean
    ProcessDefinition<QuestionProcesses.QuestionRef, EditOutput, ProcessContext> qbQuestionDelete() {
        return QuestionProcesses.delete();
    }

    @Bean
    ProcessDefinition<QuestionProcesses.ReorderInput, EditOutput, ProcessContext> qbQuestionReorder() {
        return QuestionProcesses.reorder();
    }

    @Bean
    ProcessDefinition<MaterialProcesses.MaterialInput, EditOutput, ProcessContext> qbMaterialSave() {
        return MaterialProcesses.save();
    }

    @Bean
    ProcessDefinition<MaterialProcesses.MaterialRef, EditOutput, ProcessContext> qbMaterialDelete() {
        return MaterialProcesses.delete();
    }

    @Bean
    ProcessDefinition<MaterialProcesses.ReorderInput, EditOutput, ProcessContext> qbMaterialReorder() {
        return MaterialProcesses.reorder();
    }
}
