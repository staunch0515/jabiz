package com.jabiz.quizbuks.content;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The whole content of a quiz at one moment (docs/quizbuks/plans/Q3-content.md, D-Q3-2): what a version keeps,
 * and the draft as a version would keep it. Questions, options, materials and images are numbered from 1 in their
 * order ({@code no}), never by their keys: answers refer to them by these numbers, and the numbers of a version never
 * change. Files are their {@code fileId}s.
 */
public record QuizSnapshot(String title, String intro, UUID cover, Integer timeLimitSec, boolean aiGenerated,
    List<Material> materials, List<Question> questions) {

    /** The version of this format, written into every snapshot. */
    public static final int SCHEMA = 1;

    public QuizSnapshot {
        materials = List.copyOf(Objects.requireNonNull(materials, "materials must not be null"));
        questions = List.copyOf(Objects.requireNonNull(questions, "questions must not be null"));
    }

    /**
     * A reference material; which content fields it uses depends on its {@code kind}
     * ({@link MaterialKind}).
     */
    public record Material(int no, String kind, String title, String description, String body, String url, UUID pdf,
        UUID audio, List<Image> images) {
        public Material {
            Objects.requireNonNull(kind, "kind must not be null");
            images = List.copyOf(Objects.requireNonNull(images, "images must not be null"));
        }
    }

    /** One image of an image-group material, with its caption (the text alternative). */
    public record Image(int no, UUID image, String caption) {}

    public record Question(int no, String stem, UUID image, int points, List<Option> options) {
        public Question {
            options = List.copyOf(Objects.requireNonNull(options, "options must not be null"));
        }
    }

    public record Option(int no, String text, UUID image, boolean correct) {}

    /** The sum of the questions' points. */
    public int fullScore() {
        return questions.stream().mapToInt(Question::points).sum();
    }
}
