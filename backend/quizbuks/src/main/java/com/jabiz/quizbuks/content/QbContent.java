package com.jabiz.quizbuks.content;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.Rules;
import com.jabiz.entity.TemporalRole;
import com.jabiz.file.FileKind;

import java.math.BigDecimal;
import java.util.Arrays;

/**
 * The seven entities of quiz content (docs/quizbuks/02-design.md section 3.2, docs/quizbuks/plans/Q3-content.md).
 * All are temporal, without scheduled versions; {@link #VERSION} is written once. Each row carries its owner
 * ({@code ownerId}, the sponsor who made the quiz), so a sponsor's dataset is limited by owner without a join; the
 * references to parents never change. Only the processes of {@link QuizProcesses}, {@link QuestionProcesses} and
 * {@link MaterialProcesses} write them.
 */
public final class QbContent {

    public static final String QUIZ = "QbQuiz";
    public static final String MATERIAL = "QbMaterial";
    public static final String MATERIAL_IMAGE = "QbMaterialImage";
    public static final String QUESTION = "QbQuestion";
    public static final String OPTION = "QbOption";
    public static final String VERSION = "QbQuizVersion";
    public static final String VERSION_FILE = "QbVersionFile";
    /** Q73a: the translations of a quiz's text into its other languages ({@link ContentLanguages}). */
    public static final String TRANSLATION = "QbContentTranslation";

    public static final String STATUS_DICTIONARY = "urn:jabiz:dict:quizbuks:quiz-status";
    public static final String KIND_DICTIONARY = "urn:jabiz:dict:quizbuks:material-kind";
    public static final String DRAFT = "DRAFT";
    public static final String VERSIONED = "VERSIONED";

    /** What a quiz is (Q12, D-Q12-1): a scored quiz or a survey, chosen when it is made and never changed. */
    public static final String QUIZ_KIND_DICTIONARY = "urn:jabiz:dict:quizbuks:quiz-kind";
    public static final String KIND_QUIZ = "QUIZ";
    public static final String KIND_SURVEY = "SURVEY";

    /**
     * The language a quiz is written in (Q59a, docs/quizbuks/plans/Q59-languages.md): one of the platform's. A taker
     * sees the publications of the languages they switched on (the interface language by default).
     */
    public static final String LANGUAGE_DICTIONARY = "urn:jabiz:dict:quizbuks:content-language";
    public static final java.util.List<String> LANGUAGES = java.util.List.of("ja", "zh", "en");
    /** The language of a quiz made without one (and of every quiz made before Q59a). */
    public static final String DEFAULT_LANGUAGE = "ja";

    /** How a survey's question is answered (Q12, D-Q12-2): one option, or at least one. */
    public static final String SELECTION_DICTIONARY = "urn:jabiz:dict:quizbuks:question-selection";
    public static final String SINGLE = "SINGLE";
    public static final String MULTIPLE = "MULTIPLE";

    /** File policies of content: uploaded with {@code qb.content.write}, read with {@code qb.content.file.read}. */
    public static final String IMAGE_POLICY = "qb.content.image";
    public static final String PDF_POLICY = "qb.content.pdf";
    public static final String AUDIO_POLICY = "qb.content.audio";

    /** Only {@code http} and {@code https} links, without white space: no {@code javascript:} or data links. */
    public static final String URL_PATTERN = "https?://[^\\p{Z}\\p{Cc}]+";

    /**
     * The name of a quiz's lock ({@code HoldLock}): every process that writes the quiz takes it exclusively before
     * reading anything of the quiz; submitting a publication takes it shared (Q4, D-Q4-5). Null without a quiz.
     */
    public static String lockName(Object quizId) {
        return quizId == null ? null : "qb.quiz:" + quizId;
    }

    /** The default (administrators' read-only) dataset of an entity. */
    public static String defaultDataset(String entity) {
        return "urn:jabiz:dataset:default:" + entity;
    }

    /** The sponsor's dataset of an entity: their own rows only (and of quizzes, not removed ones). */
    public static String sponsorDataset(String entity) {
        return "urn:jabiz:dataset:sponsor:" + entity;
    }

    private static String[] kinds() {
        return Arrays.stream(MaterialKind.values()).map(Enum::name).toArray(String[]::new);
    }

    public static final EntityDefinition QUIZ_ENTITY = EntityDefinition.define(QUIZ, eb -> {
        eb.physicalTable("qb_quiz_version");
        eb.primaryKey("quizId");
        eb.field("quizId", f -> f.physicalColumn("quiz_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:quiz"));
        eb.field("ownerId", f -> f.physicalColumn("owner_id").immutable(true).required(true).asText(128));
        eb.field("kind", f -> f.physicalColumn("kind").immutable(true).required(true)
            .asCode(QUIZ_KIND_DICTIONARY, KIND_QUIZ, KIND_SURVEY));
        eb.field("title", f -> f.physicalColumn("title").required(true).asText(ContentLimits.TITLE_LENGTH)
            .apply(Rules.notBlank(ContentCodes.QUIZ_TITLE_BLANK)));
        eb.field("language", f -> f.physicalColumn("language").required(true)
            .asCode(LANGUAGE_DICTIONARY, LANGUAGES.toArray(String[]::new)));
        // Q73a: the quiz's other languages, comma-separated (ContentLanguages); none for its base language only.
        eb.field("translationLanguages", f -> f.physicalColumn("translation_languages").processOnly()
            .asText(ContentLanguages.LIST_LENGTH));
        // Markdown, rendered by the frontends only, without raw HTML.
        eb.field("intro", f -> f.physicalColumn("intro").asText(ContentLimits.INTRO_LENGTH, true));
        eb.field("cover", f -> f.physicalColumn("cover").kind(FileKind.of(IMAGE_POLICY)));
        eb.field("timeLimitSec", f -> f.physicalColumn("time_limit_sec").asNumeric(5, 0)
            .apply(Rules.range(ContentCodes.QUIZ_TIME_LIMIT_RANGE,
                BigDecimal.valueOf(ContentLimits.MIN_TIME_LIMIT_SEC),
                BigDecimal.valueOf(ContentLimits.MAX_TIME_LIMIT_SEC))));
        eb.field("status", f -> f.physicalColumn("status").required(true).processOnly()
            .asCode(STATUS_DICTIONARY, DRAFT, VERSIONED));
        eb.field("removed", f -> f.physicalColumn("removed").required(true).processOnly().asBool());
        eb.field("aiGenerated", f -> f.physicalColumn("ai_generated").required(true).processOnly().asBool());
        eb.field("latestVersionNo", f -> f.physicalColumn("latest_version_no").required(true).processOnly()
            .asNumeric(6, 0));
        eb.field("versionLabel", f -> f.physicalColumn("version_label").processOnly().asText(20));
        eb.field("revision", f -> f.physicalColumn("revision").required(true).processOnly().asNumeric(9, 0));
        eb.field("editedAt", f -> f.physicalColumn("edited_at").required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("changedSinceVersion", f -> f.physicalColumn("changed_since_version").required(true).processOnly()
            .asBool());
        eb.field("questionCount", f -> f.physicalColumn("question_count").required(true).processOnly()
            .asNumeric(4, 0));
        eb.field("materialCount", f -> f.physicalColumn("material_count").required(true).processOnly()
            .asNumeric(4, 0));
        eb.field("clonedFrom", f -> f.physicalColumn("cloned_from").immutable(true).asReference(QUIZ));
        eb.display("title");
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("cover", "title", "kind", "language", "ownerId", "status", "versionLabel", "questionCount",
                "materialCount", "removed", "editedAt")
            .filters("title", "kind", "language", "ownerId", "status", "removed", "aiGenerated", "editedAt")
            .sorts("title", "editedAt", "questionCount")
            .defaultSort("editedAt", false));
    });

    public static final EntityDefinition MATERIAL_ENTITY = EntityDefinition.define(MATERIAL, eb -> {
        eb.physicalTable("qb_material_version");
        eb.primaryKey("materialId");
        eb.field("materialId", f -> f.physicalColumn("material_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:material"));
        eb.field("quizId", f -> f.physicalColumn("quiz_id").immutable(true).required(true).asReference(QUIZ));
        eb.field("ownerId", f -> f.physicalColumn("owner_id").immutable(true).required(true).asText(128));
        eb.field("seq", f -> f.physicalColumn("seq").required(true).asNumeric(4, 0));
        eb.field("kind", f -> f.physicalColumn("kind").immutable(true).required(true)
            .asCode(KIND_DICTIONARY, kinds()));
        eb.field("title", f -> f.physicalColumn("title").asText(ContentLimits.MATERIAL_TITLE_LENGTH));
        eb.field("description", f -> f.physicalColumn("description")
            .asText(ContentLimits.MATERIAL_DESCRIPTION_LENGTH, true));
        // Markdown, as the quiz's introduction.
        eb.field("body", f -> f.physicalColumn("body").asText(ContentLimits.MATERIAL_BODY_LENGTH, true));
        eb.field("url", f -> f.physicalColumn("url").asText(ContentLimits.URL_LENGTH)
            .apply(Rules.pattern(ContentCodes.MATERIAL_URL_FORMAT, URL_PATTERN)));
        eb.field("pdf", f -> f.physicalColumn("pdf").kind(FileKind.of(PDF_POLICY)));
        eb.field("audio", f -> f.physicalColumn("audio").kind(FileKind.of(AUDIO_POLICY)));
        // Q19: a video watching which is enough to answer; the App shows it on the publication's card.
        eb.field("watchToAnswer", f -> f.physicalColumn("watch_to_answer").required(true).asBool());
        // Q75: how long the video is, as the sponsor gives it (D-Q75-5); the start checks the watching against it.
        eb.field("durationSec", f -> f.physicalColumn("duration_sec").asNumeric(5, 0));
        // Q73a (§2.2b): a material in another language of the quiz stands for the base material variantOf there;
        // a base material has neither.
        eb.field("variantOf", f -> f.physicalColumn("variant_of").immutable(true).asReference(MATERIAL));
        eb.field("language", f -> f.physicalColumn("language").immutable(true)
            .asCode(LANGUAGE_DICTIONARY, LANGUAGES.toArray(String[]::new)));
        eb.display("title");
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("quizId", "seq", "kind", "title")
            .filters("quizId", "kind", "title")
            .sorts("seq", "title")
            .defaultSort("seq", true));
    });

    public static final EntityDefinition MATERIAL_IMAGE_ENTITY = EntityDefinition.define(MATERIAL_IMAGE, eb -> {
        eb.physicalTable("qb_material_image_version");
        eb.primaryKey("imageId");
        eb.field("imageId", f -> f.physicalColumn("image_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:material-image"));
        eb.field("materialId", f -> f.physicalColumn("material_id").immutable(true).required(true)
            .asReference(MATERIAL));
        eb.field("quizId", f -> f.physicalColumn("quiz_id").immutable(true).required(true).asReference(QUIZ));
        eb.field("ownerId", f -> f.physicalColumn("owner_id").immutable(true).required(true).asText(128));
        eb.field("seq", f -> f.physicalColumn("seq").required(true).asNumeric(4, 0));
        eb.field("image", f -> f.physicalColumn("image").required(true).kind(FileKind.of(IMAGE_POLICY)));
        // The text alternative of the image.
        eb.field("caption", f -> f.physicalColumn("caption").asText(ContentLimits.CAPTION_LENGTH));
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("materialId", "seq", "image", "caption")
            .filters("materialId", "quizId")
            .sorts("seq")
            .defaultSort("seq", true));
    });

    public static final EntityDefinition QUESTION_ENTITY = EntityDefinition.define(QUESTION, eb -> {
        eb.physicalTable("qb_question_version");
        eb.primaryKey("questionId");
        eb.field("questionId", f -> f.physicalColumn("question_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:question"));
        eb.field("quizId", f -> f.physicalColumn("quiz_id").immutable(true).required(true).asReference(QUIZ));
        eb.field("ownerId", f -> f.physicalColumn("owner_id").immutable(true).required(true).asText(128));
        eb.field("seq", f -> f.physicalColumn("seq").required(true).asNumeric(4, 0));
        eb.field("stem", f -> f.physicalColumn("stem").asText(ContentLimits.STEM_LENGTH, true));
        eb.field("image", f -> f.physicalColumn("image").kind(FileKind.of(IMAGE_POLICY)));
        eb.field("points", f -> f.physicalColumn("points").required(true).asNumeric(4, 0)
            .apply(Rules.range(ContentCodes.QUESTION_POINTS_RANGE, BigDecimal.valueOf(ContentLimits.MIN_POINTS),
                BigDecimal.valueOf(ContentLimits.MAX_POINTS))));
        // A survey's questions only (Q12, D-Q12-2); a scored question has none.
        eb.field("selection", f -> f.physicalColumn("selection").asCode(SELECTION_DICTIONARY, SINGLE, MULTIPLE));
        // The AI job the question was reviewed and accepted from (Q8a, M-20): the review pages mark it.
        eb.field("aiJobId", f -> f.physicalColumn("ai_job_id").immutable(true).processOnly().asText(36));
        eb.display("stem");
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("quizId", "seq", "stem", "points")
            .filters("quizId")
            .sorts("seq")
            .defaultSort("seq", true));
    });

    public static final EntityDefinition OPTION_ENTITY = EntityDefinition.define(OPTION, eb -> {
        eb.physicalTable("qb_option_version");
        eb.primaryKey("optionId");
        eb.field("optionId", f -> f.physicalColumn("option_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:option"));
        eb.field("questionId", f -> f.physicalColumn("question_id").immutable(true).required(true)
            .asReference(QUESTION));
        // The quiz as well, so a process reads all the options of a quiz at once.
        eb.field("quizId", f -> f.physicalColumn("quiz_id").immutable(true).required(true).asReference(QUIZ));
        eb.field("ownerId", f -> f.physicalColumn("owner_id").immutable(true).required(true).asText(128));
        eb.field("seq", f -> f.physicalColumn("seq").required(true).asNumeric(4, 0));
        eb.field("text", f -> f.physicalColumn("text").asText(ContentLimits.OPTION_LENGTH));
        eb.field("image", f -> f.physicalColumn("image").kind(FileKind.of(IMAGE_POLICY)));
        eb.field("correct", f -> f.physicalColumn("correct").required(true).asBool());
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("questionId", "seq", "text", "correct")
            .filters("questionId", "quizId")
            .sorts("seq")
            .defaultSort("seq", true));
    });

    /**
     * A version: the whole content as canonical JSON ({@link QuizSnapshots}), written once. It has no file fields: a
     * version would hold its files forever. {@link #VERSION_FILE} keeps them while they are needed.
     */
    public static final EntityDefinition VERSION_ENTITY = EntityDefinition.define(VERSION, eb -> {
        eb.physicalTable("qb_quiz_version_version");
        eb.primaryKey("versionId");
        eb.field("versionId", f -> f.physicalColumn("version_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:quiz-version"));
        eb.field("quizId", f -> f.physicalColumn("quiz_id").immutable(true).required(true).asReference(QUIZ));
        eb.field("ownerId", f -> f.physicalColumn("owner_id").immutable(true).required(true).asText(128));
        // versionNo is the platform's own row version of every temporal entity.
        eb.field("versionNumber", f -> f.physicalColumn("quiz_version_no").immutable(true).required(true)
            .asNumeric(6, 0));
        eb.field("label", f -> f.physicalColumn("label").immutable(true).required(true).asText(20));
        eb.field("title", f -> f.physicalColumn("title").immutable(true).required(true)
            .asText(ContentLimits.TITLE_LENGTH));
        eb.field("timeLimitSec", f -> f.physicalColumn("time_limit_sec").immutable(true).asNumeric(5, 0));
        // The quiz's language when the version was made (Q59a): what the publications of this version are shown in.
        eb.field("language", f -> f.physicalColumn("language").immutable(true).required(true)
            .asCode(LANGUAGE_DICTIONARY, LANGUAGES.toArray(String[]::new)));
        // Q73a: every language of the version, the base first, comma-separated; none for its base language only.
        eb.field("languages", f -> f.physicalColumn("languages").immutable(true)
            .asText(ContentLanguages.LIST_LENGTH));
        eb.field("questionCount", f -> f.physicalColumn("question_count").immutable(true).required(true)
            .asNumeric(4, 0));
        eb.field("materialCount", f -> f.physicalColumn("material_count").immutable(true).required(true)
            .asNumeric(4, 0));
        eb.field("fullScore", f -> f.physicalColumn("full_score").immutable(true).required(true).asNumeric(7, 0));
        eb.field("content", f -> f.physicalColumn("content").immutable(true).required(true).asText(null, true));
        eb.field("contentHash", f -> f.physicalColumn("content_hash").immutable(true).required(true).asText(64));
        eb.field("versionedAt", f -> f.physicalColumn("versioned_at").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.unique("uk_qb_quiz_version_no", "quizId", "versionNumber");
        eb.display("label");
        eb.temporal(t -> t.allowScheduled(false).writeOnce());
        eb.listView("default", lv -> lv
            .columns("quizId", "label", "title", "questionCount", "fullScore", "versionedAt")
            .filters("quizId", "ownerId", "versionedAt")
            .sorts("versionNumber", "versionedAt")
            .defaultSort("versionedAt", false));
    });

    /**
     * A file a version of the quiz refers to, one row per quiz and file: the platform's sweep does not look into the
     * versions' JSON, but sees these rows. Exactly one of the three file fields holds the file (one field per policy).
     * Inserted when a version is made, tombstoned when the quiz is removed.
     */
    public static final EntityDefinition VERSION_FILE_ENTITY = EntityDefinition.define(VERSION_FILE, eb -> {
        eb.physicalTable("qb_version_file_version");
        eb.primaryKey("versionFileId");
        eb.field("versionFileId", f -> f.physicalColumn("version_file_id").immutable(true).required(true)
            .generated(true).asSemanticIdentity("urn:jabiz:entity:quizbuks:version-file"));
        eb.field("quizId", f -> f.physicalColumn("quiz_id").immutable(true).required(true).asReference(QUIZ));
        eb.field("ownerId", f -> f.physicalColumn("owner_id").immutable(true).required(true).asText(128));
        eb.field("firstVersionNo", f -> f.physicalColumn("first_version_no").immutable(true).required(true)
            .asNumeric(6, 0));
        eb.field("image", f -> f.physicalColumn("image").immutable(true).kind(FileKind.of(IMAGE_POLICY)));
        eb.field("pdf", f -> f.physicalColumn("pdf").immutable(true).kind(FileKind.of(PDF_POLICY)));
        eb.field("audio", f -> f.physicalColumn("audio").immutable(true).kind(FileKind.of(AUDIO_POLICY)));
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("quizId", "firstVersionNo", "image", "pdf", "audio")
            .filters("quizId")
            .sorts("firstVersionNo")
            .defaultSort("firstVersionNo", true));
    });

    /**
     * A translation of one element of a quiz's text into one of its other languages (Q73a,
     * docs/quizbuks/plans/Q73-content-languages.md §2.2): its text, or a language's own image of a question or an
     * option, and the hash of the base text it translates ({@link ContentLanguages#sourceHash}). One live row per
     * quiz, language and element, which the processes keep under the quiz's lock (a temporal table has no unique
     * constraint on them).
     */
    public static final EntityDefinition TRANSLATION_ENTITY = EntityDefinition.define(TRANSLATION, eb -> {
        eb.physicalTable("qb_content_translation_version");
        eb.primaryKey("translationId");
        eb.field("translationId", f -> f.physicalColumn("translation_id").immutable(true).required(true)
            .generated(true).asSemanticIdentity("urn:jabiz:entity:quizbuks:content-translation"));
        eb.field("quizId", f -> f.physicalColumn("quiz_id").immutable(true).required(true).asReference(QUIZ));
        eb.field("ownerId", f -> f.physicalColumn("owner_id").immutable(true).required(true).asText(128));
        eb.field("language", f -> f.physicalColumn("language").immutable(true).required(true)
            .asCode(LANGUAGE_DICTIONARY, LANGUAGES.toArray(String[]::new)));
        eb.field("elementKey", f -> f.physicalColumn("element_key").immutable(true).required(true)
            .asText(ContentLanguages.KEY_LENGTH));
        // Markdown where the base text is (the introduction, a question's text); lengths are the base's.
        eb.field("text", f -> f.physicalColumn("text").asText(ContentLimits.INTRO_LENGTH, true));
        eb.field("image", f -> f.physicalColumn("image").kind(FileKind.of(IMAGE_POLICY)));
        eb.field("sourceHash", f -> f.physicalColumn("source_hash").asText(16));
        eb.field("translatedBy", f -> f.physicalColumn("translated_by").required(true)
            .asText(8));
        eb.field("aiJobId", f -> f.physicalColumn("ai_job_id").asText(36));
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("quizId", "language", "elementKey", "text", "image")
            .filters("quizId", "language")
            .sorts("elementKey")
            .defaultSort("elementKey", true));
    });

    private QbContent() {}
}
