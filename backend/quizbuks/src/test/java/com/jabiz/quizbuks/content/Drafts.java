package com.jabiz.quizbuks.content;

import java.util.List;
import java.util.UUID;

/** Snapshots for the unit tests. */
final class Drafts {

    static final UUID COVER = UUID.fromString("0190a000-0000-7000-8000-000000000001");
    static final UUID PICTURE = UUID.fromString("0190a000-0000-7000-8000-000000000002");
    static final UUID PDF = UUID.fromString("0190a000-0000-7000-8000-000000000003");
    static final UUID AUDIO = UUID.fromString("0190a000-0000-7000-8000-000000000004");

    static QuizSnapshot.Option option(int no, String text, boolean correct) {
        return new QuizSnapshot.Option(no, text, null, correct);
    }

    static QuizSnapshot.Question question(int no, String stem, int points, QuizSnapshot.Option... options) {
        return new QuizSnapshot.Question(no, stem, null, points, List.of(options));
    }

    /** A complete quiz: two questions and an article. */
    static QuizSnapshot complete() {
        return new QuizSnapshot("Capitals", "About *capitals*.", COVER, 300, false,
            List.of(new QuizSnapshot.Material(1, "ARTICLE", "Reading", "Read first", "Paris is…", null, null, null,
                List.of())),
            List.of(question(1, "Capital of France?", 2, option(1, "Paris", true), option(2, "Lyon", false)),
                new QuizSnapshot.Question(2, "Which flag?", PICTURE, 3, List.of(
                    new QuizSnapshot.Option(1, null, PICTURE, true), option(2, "None", false)))));
    }

    private Drafts() {}
}
