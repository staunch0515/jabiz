package com.jabiz.quizbuks.asset;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The kinds of a sponsor's assets (F-C1, dictionary {@code urn:jabiz:dict:quizbuks:asset-kind}): the materials'
 * media, a text for one use, and a prize card. Each kind has its own content fields; the other content fields stay
 * empty (as {@link com.jabiz.quizbuks.content.MaterialKind#contentField()}). Never changed once an asset is made.
 */
public enum AssetKind {
    IMAGE(List.of("image")),
    PDF(List.of("pdf")),
    AUDIO(List.of("audio")),
    /** A link to one video of a site QuizBuks plays ({@link com.jabiz.quizbuks.content.VideoEmbeds}). */
    VIDEO_LINK(List.of("url", "durationSec")),
    LINK(List.of("url")),
    /** Markdown text, as a material's. */
    ARTICLE(List.of("body")),
    /** A text for one use ({@link AssetRules#USAGES}): a quiz's introduction or a publication's description. */
    TEXT(List.of("body", "usage")),
    /** How a prize reaches the taker and what it is, as a publication's reward step takes it. */
    PRIZE(List.of("prizeMethod", "prizeDescription", "digitalDelivery", "digitalSendDays"));

    /** Every content field of an asset, of which a kind uses its own ({@link #contentFields()}). */
    public static final List<String> CONTENT_FIELDS = List.of("image", "pdf", "audio", "url", "durationSec", "body",
        "usage", "prizeMethod", "prizeDescription", "digitalDelivery", "digitalSendDays");

    private final List<String> contentFields;

    AssetKind(List<String> contentFields) {
        this.contentFields = contentFields;
    }

    /** The content fields this kind may fill. */
    public List<String> contentFields() {
        return contentFields;
    }

    /** Whether the kind's content is a file (deduplicated by the file's id, not by a hash). */
    public boolean file() {
        return this == IMAGE || this == PDF || this == AUDIO;
    }

    public static Optional<AssetKind> of(String code) {
        return Arrays.stream(values()).filter(kind -> kind.name().equals(code)).findFirst();
    }

    public static List<String> codes() {
        return Arrays.stream(values()).map(Enum::name).toList();
    }
}
