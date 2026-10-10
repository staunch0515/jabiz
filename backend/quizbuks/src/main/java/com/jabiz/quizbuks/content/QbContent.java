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

    public static final String STATUS_DICTIONARY = "urn:jabiz:dict:quizbuks:quiz-status";
    public static final String KIND_DICTIONARY = "urn:jabiz:dict:quizbuks:material-kind";
    public static final String DRAFT = "DRAFT";
    public static final String VERSIONED = "VERSIONED";

    /** File policies of content: uploaded with {@code qb.content.write}, read with {@code qb.content.file.read}. */
    public static final String IMAGE_POLICY = "qb.content.image";
    public static final String PDF_POLICY = "qb.content.pdf";
    public static final String AUDIO_POLICY = "qb.content.audio";

    /** Only {@code http} and {@code https} links, without white space: no {@code javascript:} or data links. */
    public static final String URL_PATTERN = "https?://[^\\p{Z}\\p{Cc}]+";

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
        eb.field("title", f -> f.physicalColumn("title").required(true).asText(ContentLimits.TITLE_LENGTH)
            .apply(Rules.notBlank(ContentCodes.QUIZ_TITLE_BLANK)));
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
            .columns("cover", "title", "ownerId", "status", "versionLabel", "questionCount", "materialCount",
                "removed", "editedAt")
            .filters("title", "ownerId", "status", "removed", "aiGenerated", "editedAt")
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

    private QbContent() {}
}
