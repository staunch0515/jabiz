package com.jabiz.quizbuks.content;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The whole content of a quiz at one moment (docs/quizbuks/plans/Q3-content.md, D-Q3-2): what a version keeps,
 * and the draft as a version would keep it. Questions, options, materials and images are numbered from 1 in their
 * order ({@code no}), never by their keys: answers refer to them by these numbers, and the numbers of a version never
 * change. Files are their {@code fileId}s.
 */
public record QuizSnapshot(String title, String intro, UUID cover, Integer timeLimitSec, boolean aiGenerated,
    List<Material> materials, List<Question> questions, boolean survey, Map<String, Language> translations) {

    /**
     * The version of this format, written into every snapshot. A survey's snapshot adds its kind and its questions'
     * selection (Q12); a scored quiz's has neither key, so its text and hash are what they were before surveys.
     */
    public static final int SCHEMA = 1;

    /**
     * The version of the format of a snapshot with translations (Q73a): the same keys, and {@code translations}.
     * A snapshot without translations stays of {@link #SCHEMA}, its text and hash what they were.
     */
    public static final int SCHEMA_LANGUAGES = 2;

    public QuizSnapshot {
        materials = List.copyOf(Objects.requireNonNull(materials, "materials must not be null"));
        questions = List.copyOf(Objects.requireNonNull(questions, "questions must not be null"));
        translations = translations == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(translations));
    }

    /** A quiz or survey of its base language only. */
    public QuizSnapshot(String title, String intro, UUID cover, Integer timeLimitSec, boolean aiGenerated,
        List<Material> materials, List<Question> questions, boolean survey) {
        this(title, intro, cover, timeLimitSec, aiGenerated, materials, questions, survey, Map.of());
    }

    /**
     * The quiz in one of its other languages (Q73a, docs/quizbuks/plans/Q73-content-languages.md §2.3): the text
     * of every element by its key in the snapshot ({@code title}, {@code intro}, {@code q3.stem},
     * {@code q3.o2.text}), the images the language has of its own ({@code q3.image}, {@code q3.o2.image}), and the
     * materials it has of its own, each numbered as the base material it stands for. Whatever it lacks is the base's.
     */
    public record Language(Map<String, String> texts, Map<String, UUID> images, List<Material> materials) {
        public Language {
            texts = Collections.unmodifiableMap(new TreeMap<>(Objects.requireNonNull(texts, "texts")));
            images = Collections.unmodifiableMap(new TreeMap<>(Objects.requireNonNull(images, "images")));
            materials = List.copyOf(Objects.requireNonNull(materials, "materials"));
        }
    }

    /** The key of a question's text in a {@link Language}. */
    public static String stemKey(int question) {
        return "q" + question + ".stem";
    }

    public static String questionImageKey(int question) {
        return "q" + question + ".image";
    }

    public static String optionTextKey(int question, int option) {
        return "q" + question + ".o" + option + ".text";
    }

    public static String optionImageKey(int question, int option) {
        return "q" + question + ".o" + option + ".image";
    }

    public static final String TITLE_KEY = "title";
    public static final String INTRO_KEY = "intro";

    /** A scored quiz. */
    public QuizSnapshot(String title, String intro, UUID cover, Integer timeLimitSec, boolean aiGenerated,
        List<Material> materials, List<Question> questions) {
        this(title, intro, cover, timeLimitSec, aiGenerated, materials, questions, false);
    }

    /**
     * A reference material; which content fields it uses depends on its {@code kind}
     * ({@link MaterialKind}).
     */
    public record Material(int no, String kind, String title, String description, String body, String url, UUID pdf,
        UUID audio, List<Image> images, boolean watchToAnswer, String language) {
        public Material {
            Objects.requireNonNull(kind, "kind must not be null");
            images = List.copyOf(Objects.requireNonNull(images, "images must not be null"));
        }

        /**
         * A material as a version keeps it; {@code language} is only set on a material read in one of the quiz's
         * other languages (Q73a: the language it is in there, not written into a snapshot).
         */
        public Material(int no, String kind, String title, String description, String body, String url, UUID pdf,
            UUID audio, List<Image> images, boolean watchToAnswer) {
            this(no, kind, title, description, body, url, pdf, audio, images, watchToAnswer, null);
        }

        /** A material that is not the video to watch (Q19). */
        public Material(int no, String kind, String title, String description, String body, String url, UUID pdf,
            UUID audio, List<Image> images) {
            this(no, kind, title, description, body, url, pdf, audio, images, false);
        }
    }

    /** One image of an image-group material, with its caption (the text alternative). */
    public record Image(int no, UUID image, String caption) {}

    /**
     * @param selection a survey's question: {@code SINGLE} or {@code MULTIPLE} ({@link QbContent}); null for a scored
     *                  question (and for a survey's question not yet set, which a version does not accept)
     */
    public record Question(int no, String stem, UUID image, int points, List<Option> options, String selection) {
        public Question {
            options = List.copyOf(Objects.requireNonNull(options, "options must not be null"));
        }

        /** A scored question. */
        public Question(int no, String stem, UUID image, int points, List<Option> options) {
            this(no, stem, image, points, options, null);
        }

        /**
         * How the question is answered, as the taker is told: a survey's question as set ({@code SINGLE} or
         * {@code MULTIPLE}); a scored one by its correct options (Q5, D-Q5-2): exactly one makes it a single choice,
         * several a multiple choice. Null only for a survey's question not yet set, which a version does not accept.
         */
        public String choice() {
            if (selection != null || options.stream().noneMatch(Option::correct)) {
                return selection;
            }
            return options.stream().filter(Option::correct).count() == 1 ? QbContent.SINGLE : QbContent.MULTIPLE;
        }

        /** Whether these chosen options answer the question (Q12: a single choice takes exactly one). */
        public boolean answeredBy(java.util.Collection<Integer> chosen) {
            return QbContent.SINGLE.equals(selection) ? chosen.size() == 1 : !chosen.isEmpty();
        }
    }

    public record Option(int no, String text, UUID image, boolean correct) {}

    /** {@code SURVEY} or {@code QUIZ} ({@link QbContent}). */
    public String kind() {
        return survey ? QbContent.KIND_SURVEY : QbContent.KIND_QUIZ;
    }

    /** The sum of the questions' points. */
    public int fullScore() {
        return questions.stream().mapToInt(Question::points).sum();
    }
}
