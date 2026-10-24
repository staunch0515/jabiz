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
 * @param kind  {@code QUIZ} or {@code SURVEY}
 * @param cover a key of {@link #files}, or null
 */
public record QuizFile(String kind, String title, String intro, String cover, Integer timeLimitSec,
    List<Material> materials, List<Question> questions, Map<String, FileEntry> files) {

    public QuizFile {
        materials = List.copyOf(Objects.requireNonNull(materials, "materials must not be null"));
        questions = List.copyOf(Objects.requireNonNull(questions, "questions must not be null"));
        files = Map.copyOf(Objects.requireNonNull(files, "files must not be null"));
    }

    /**
     * A material; its file fields are keys of {@link #files}. {@code watchToAnswer} (Q19, optional in version 1, absent
     * is false): the video watching which is enough to answer.
     */
    public record Material(String kind, String title, String description, String body, String url, String pdf,
        String audio, List<Image> images, boolean watchToAnswer) {
        public Material {
            images = List.copyOf(Objects.requireNonNull(images, "images must not be null"));
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
