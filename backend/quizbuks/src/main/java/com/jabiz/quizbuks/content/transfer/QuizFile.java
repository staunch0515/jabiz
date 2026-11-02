package com.jabiz.quizbuks.content.transfer;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A quiz as a file carries it (docs/quizbuks/plans/Q16-quiz-transfer.md), in the current format version: the draft's
 * content in its order, with files named by keys of {@link #files}. What a quiz is in this system (its ids, owner,
 * status, versions, revision) is not part of it: an import always makes a new draft. Every older version of the
 * format is read into this one ({@link QuizFileFormat}).
 *
 * @param kind         {@code QUIZ} or {@code SURVEY}
 * @param cover        a key of {@link #files}, or null
 * @param language     the base language (version 2, Q73a); null for the importer's interface language
 * @param translations the quiz's other languages (version 2, Q73a), by code
 */
public record QuizFile(String kind, String title, String intro, String cover, Integer timeLimitSec,
    List<Material> materials, List<Question> questions, Map<String, FileEntry> files, String language,
    Map<String, Language> translations) {

    public QuizFile {
        materials = List.copyOf(Objects.requireNonNull(materials, "materials must not be null"));
        questions = List.copyOf(Objects.requireNonNull(questions, "questions must not be null"));
        files = Map.copyOf(Objects.requireNonNull(files, "files must not be null"));
        translations = translations == null ? Map.of()
            : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(translations));
    }

    /** A quiz of its base language only (version 1). */
    public QuizFile(String kind, String title, String intro, String cover, Integer timeLimitSec,
        List<Material> materials, List<Question> questions, Map<String, FileEntry> files) {
        this(kind, title, intro, cover, timeLimitSec, materials, questions, files, null, Map.of());
    }

    /**
     * One other language of the quiz (Q73a), as a version's snapshot has it: the texts by their key in the snapshot
     * ({@code title}, {@code intro}, {@code q3.stem}, {@code q3.o2.text}), its own images by the same kind of key
     * ({@code q3.image}, {@code q3.o2.image}; values are keys of {@link #files}), its own materials, each numbered as
     * the base material it stands for, and the base hashes its texts were translated from (optional: a text without
     * one counts as made from the base as it is).
     */
    public record Language(Map<String, String> texts, Map<String, String> images, List<NumberedMaterial> materials,
        Map<String, String> hashes) {
        public Language {
            texts = Map.copyOf(Objects.requireNonNull(texts, "texts must not be null"));
            images = Map.copyOf(Objects.requireNonNull(images, "images must not be null"));
            materials = List.copyOf(Objects.requireNonNull(materials, "materials must not be null"));
            hashes = Map.copyOf(Objects.requireNonNull(hashes, "hashes must not be null"));
        }
    }

    /** A material of another language and the number (from 1) of the base material it stands for. */
    public record NumberedMaterial(int no, Material material) {}

    /**
     * A material; its file fields are keys of {@link #files}. {@code watchToAnswer} (Q19, optional in version 1, absent
     * is false): the video watching which is enough to answer. {@code durationSec} (Q75, optional, a video of the base
     * language only): how long the video is.
     */
    public record Material(String kind, String title, String description, String body, String url, String pdf,
        String audio, List<Image> images, boolean watchToAnswer, Integer durationSec) {
        public Material {
            images = List.copyOf(Objects.requireNonNull(images, "images must not be null"));
        }

        public Material(String kind, String title, String description, String body, String url, String pdf,
            String audio, List<Image> images, boolean watchToAnswer) {
            this(kind, title, description, body, url, pdf, audio, images, watchToAnswer, null);
        }

        public Material(String kind, String title, String description, String body, String url, String pdf,
            String audio, List<Image> images) {
            this(kind, title, description, body, url, pdf, audio, images, false);
        }
    }

    public record Image(String image, String caption) {}

    /** @param selection a survey's {@code SINGLE} or {@code MULTIPLE}; null for a scored question */
    public record Question(String stem, String image, Integer points, String selection, List<Option> options) {
        public Question {
            options = List.copyOf(Objects.requireNonNull(options, "options must not be null"));
        }
    }

    public record Option(String text, String image, boolean correct) {}

    /**
     * A file the quiz uses, as the file describes it: its media type and size (the data itself is read by the browser
     * and uploaded, never by the server).
     */
    public record FileEntry(String name, String type, long size) {}
}
