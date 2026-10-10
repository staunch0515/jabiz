package com.jabiz.quizbuks.content;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The kinds of reference material (M-23; dictionary {@code urn:jabiz:dict:quizbuks:material-kind}). Each has a title
 * and a description and exactly one field of content; the other content fields stay empty. A video is only a link
 * (confirmed): QuizBuks stores no video.
 */
public enum MaterialKind {
    /** Markdown text. */
    ARTICLE("body"),
    LINK("url"),
    VIDEO_LINK("url"),
    PDF("pdf"),
    /** One to {@link ContentLimits#MAX_IMAGES} images with captions, rows of {@code QbMaterialImage}. */
    IMAGES("images"),
    AUDIO("audio");

    /** The content fields of a material, of which its kind uses one. */
    public static final List<String> CONTENT_FIELDS = List.of("body", "url", "pdf", "audio", "images");

    private final String contentField;

    MaterialKind(String contentField) {
        this.contentField = contentField;
    }

    /** The field holding this kind's content: {@code body}, {@code url}, {@code pdf}, {@code audio} or {@code images}. */
    public String contentField() {
        return contentField;
    }

    public static Optional<MaterialKind> of(String code) {
        return Arrays.stream(values()).filter(kind -> kind.name().equals(code)).findFirst();
    }
}
