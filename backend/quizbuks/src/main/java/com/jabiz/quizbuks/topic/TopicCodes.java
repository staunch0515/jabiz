package com.jabiz.quizbuks.topic;

import java.util.List;

/** The error codes of topics (F-S9); each has its three-language message. */
public final class TopicCodes {

    /** A word chosen anew is not on the list, deactivated or merged into another. */
    public static final String NOT_SELECTABLE = "QB_TOPIC_NOT_SELECTABLE";
    /** More topics (words and custom words together) than {@code qb.topic.max-per-quiz}. */
    public static final String TOO_MANY = "QB_TOPIC_TOO_MANY";
    /** A custom word longer than {@code qb.topic.custom-max-length}. */
    public static final String CUSTOM_LENGTH = "QB_TOPIC_CUSTOM_LENGTH";
    /** A custom word blank or with control characters. */
    public static final String CUSTOM_INVALID = "QB_TOPIC_CUSTOM_INVALID";
    /** A new word's code is not lower-case letters, digits and single hyphens of 2 to 40 characters. */
    public static final String CODE_FORMAT = "QB_TOPIC_CODE_FORMAT";
    /** A new word's code is taken. */
    public static final String CODE_TAKEN = "QB_TOPIC_CODE_TAKEN";
    /** A word was merged into another: it no longer changes. */
    public static final String MERGED = "QB_TOPIC_MERGED";
    /** A word cannot be merged into itself, nor into one deactivated or merged. */
    public static final String MERGE_TARGET = "QB_TOPIC_MERGE_TARGET";
    /** Strict mode needs at least one liked topic. */
    public static final String STRICT_NEEDS_TOPICS = "QB_TOPIC_STRICT_NEEDS_TOPICS";
    /** More liked topics than allowed. */
    public static final String TOO_MANY_LIKED = "QB_TOPIC_TOO_MANY_LIKED";
    /** A publication's topics change from its version's: it needs one first. */
    public static final String NO_VERSION = "QB_PUBLICATION_TOPICS_NO_VERSION";

    public static final List<String> ALL = List.of(NOT_SELECTABLE, TOO_MANY, CUSTOM_LENGTH, CUSTOM_INVALID, CODE_FORMAT,
        CODE_TAKEN, MERGED, MERGE_TARGET, STRICT_NEEDS_TOPICS, TOO_MANY_LIKED, NO_VERSION);

    private TopicCodes() {}
}
