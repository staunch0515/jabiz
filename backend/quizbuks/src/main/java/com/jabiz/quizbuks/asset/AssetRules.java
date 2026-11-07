package com.jabiz.quizbuks.asset;

import com.jabiz.entity.Violation;
import com.jabiz.quizbuks.content.ContentLimits;
import com.jabiz.quizbuks.content.QbContent;
import com.jabiz.quizbuks.content.TextTranslations;
import com.jabiz.quizbuks.content.VideoEmbeds;
import com.jabiz.quizbuks.publishing.DigitalDelivery;
import com.jabiz.quizbuks.publishing.PublicationLanguages;
import com.jabiz.quizbuks.publishing.PublishingLimits;
import com.jabiz.quizbuks.publishing.RewardMethod;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * What an asset of the library may hold (F-C1, docs/quizbuks/plans/impl/F-C1.md): each kind its own content fields
 * only ({@link AssetKind}), within the limits of the fields the content is copied into (a material's, a quiz's
 * introduction, a publication's descriptions), tags, translations as the sponsor's short texts have them
 * ({@link TextTranslations}), and the content hash the library is deduplicated by. Pure Java; every problem at once.
 */
public final class AssetRules {

    /** What a text asset is for, each with the length of the field it is copied into. */
    public static final String QUIZ_INTRO = "QUIZ_INTRO";
    public static final String REDEEM_DESCRIPTION = "REDEEM_DESCRIPTION";
    public static final String DIGITAL_DESCRIPTION = "DIGITAL_DESCRIPTION";
    public static final String REFERRAL_DESCRIPTION = "REFERRAL_DESCRIPTION";
    public static final List<String> USAGES = List.of(QUIZ_INTRO, REDEEM_DESCRIPTION, DIGITAL_DESCRIPTION,
        REFERRAL_DESCRIPTION);

    /** The reward methods a prize card can be: those a sponsor hands over themselves. */
    public static final List<String> PRIZE_METHODS = List.of(RewardMethod.OFFLINE_REDEEM, RewardMethod.DIGITAL_PRIZE,
        RewardMethod.SPONSOR_CASH);

    public static final int NAME_LENGTH = ContentLimits.MATERIAL_TITLE_LENGTH;
    public static final int DESCRIPTION_LENGTH = ContentLimits.MATERIAL_DESCRIPTION_LENGTH;
    public static final int URL_LENGTH = ContentLimits.URL_LENGTH;
    /** The column's length: an article's; a text's is shorter, by its use ({@link #bodyLength}). */
    public static final int BODY_LENGTH = ContentLimits.MATERIAL_BODY_LENGTH;
    public static final int PRIZE_DESCRIPTION_LENGTH = PublishingLimits.REDEEM_DESCRIPTION_LENGTH;

    /** The most items one collection takes (its candidates come a page at a time). */
    public static final int MAX_COLLECT = 200;

    private static final Pattern URL = Pattern.compile(QbContent.URL_PATTERN);

    /** The content of an asset, as a save or a collected item gives it. */
    public record Asset(String kind, String name, String description, String language, List<String> tags,
        UUID image, UUID pdf, UUID audio, String url, Integer durationSec, String body, String usage,
        String prizeMethod, String prizeDescription, String digitalDelivery, Integer digitalSendDays) {}

    /** The most characters a text asset's body has for its use; an article's otherwise. */
    public static int bodyLength(String kind, String usage) {
        if (!AssetKind.TEXT.name().equals(kind)) {
            return BODY_LENGTH;
        }
        return QUIZ_INTRO.equals(usage) ? ContentLimits.INTRO_LENGTH : PublishingLimits.REDEEM_DESCRIPTION_LENGTH;
    }

    /**
     * Whether an asset of {@code kind} (and, for a text, {@code usage}) may fill a place of {@code targetKind}
     * (F-C1b): an image for the images, a text of the matching use for an introduction or a description, a prize card
     * for a publication's prize, and for a material the asset kind of the material's kind ({@code materialKind}).
     */
    public static boolean suits(String targetKind, String materialKind, String kind, String usage) {
        if (targetKind == null || kind == null) {
            return false;
        }
        return switch (targetKind) {
            case QbAssets.MATERIAL -> materialKind != null && kind.equals(
                "IMAGES".equals(materialKind) ? AssetKind.IMAGE.name() : materialKind)
                && AssetKind.of(kind).filter(k -> k != AssetKind.TEXT && k != AssetKind.PRIZE).isPresent();
            case QbAssets.QUESTION_IMAGE, QbAssets.OPTION_IMAGE, QbAssets.QUIZ_COVER, QbAssets.PUBLICATION_COVER ->
                AssetKind.IMAGE.name().equals(kind);
            case QbAssets.QUIZ_INTRO -> isText(kind, usage, QUIZ_INTRO);
            case QbAssets.PUBLICATION_REDEEM -> isText(kind, usage, REDEEM_DESCRIPTION);
            case QbAssets.PUBLICATION_DIGITAL -> isText(kind, usage, DIGITAL_DESCRIPTION);
            case QbAssets.PUBLICATION_REFERRAL -> isText(kind, usage, REFERRAL_DESCRIPTION);
            case QbAssets.PUBLICATION_PRIZE -> AssetKind.PRIZE.name().equals(kind);
            default -> false;
        };
    }

    private static boolean isText(String kind, String usage, String wanted) {
        return AssetKind.TEXT.name().equals(kind) && wanted.equals(usage);
    }

    /** Every problem of the asset's own fields (not of its translations: {@link #translationProblems}). */
    public static List<Violation> problems(Asset asset, int maxTags, int tagLength) {
        List<Violation> problems = new ArrayList<>();
        AssetKind kind = AssetKind.of(asset.kind()).orElse(null);
        if (kind == null) {
            problems.add(new Violation("kind", AssetCodes.KIND_INVALID, "Not a kind of asset: " + asset.kind(),
                Map.of("kind", String.valueOf(asset.kind()))));
            return problems;
        }
        String name = text(asset.name());
        if (name == null) {
            problems.add(new Violation("name", AssetCodes.NAME_BLANK, "An asset has a name"));
        } else {
            length(problems, "name", name.strip(), NAME_LENGTH);
        }
        length(problems, "description", text(asset.description()), DESCRIPTION_LENGTH);
        if (asset.language() == null || !QbContent.LANGUAGES.contains(asset.language())) {
            problems.add(new Violation("language", AssetCodes.LANGUAGE_INVALID, "Not a language of the platform: "
                + asset.language(), Map.of("language", String.valueOf(asset.language()))));
        }
        problems.addAll(tagProblems(asset.tags(), maxTags, tagLength));

        Map<String, Boolean> present = present(asset);
        present.forEach((field, given) -> {
            if (given && !kind.contentFields().contains(field)) {
                problems.add(new Violation(field, AssetCodes.FIELD_NOT_ALLOWED, "An asset of kind " + kind
                    + " has no " + field, Map.of("kind", kind.name(), "field", field)));
            }
        });
        switch (kind) {
            case IMAGE -> required(problems, kind, "image", present);
            case PDF -> required(problems, kind, "pdf", present);
            case AUDIO -> required(problems, kind, "audio", present);
            case VIDEO_LINK, LINK -> {
                if (required(problems, kind, "url", present)) {
                    url(problems, kind, asset.url());
                }
                Integer duration = asset.durationSec();
                if (kind == AssetKind.VIDEO_LINK && duration != null
                    && (duration < ContentLimits.MIN_VIDEO_SECONDS || duration > ContentLimits.MAX_VIDEO_SECONDS)) {
                    problems.add(new Violation("durationSec", AssetCodes.DURATION_RANGE, "A video lasts "
                        + ContentLimits.MIN_VIDEO_SECONDS + " to " + ContentLimits.MAX_VIDEO_SECONDS + " seconds",
                        Map.of("min", ContentLimits.MIN_VIDEO_SECONDS, "max", ContentLimits.MAX_VIDEO_SECONDS)));
                }
            }
            case ARTICLE -> {
                if (required(problems, kind, "body", present)) {
                    length(problems, "body", asset.body(), BODY_LENGTH);
                }
            }
            case TEXT -> {
                boolean usageKnown = asset.usage() != null && USAGES.contains(asset.usage());
                if (required(problems, kind, "usage", present) && !usageKnown) {
                    problems.add(new Violation("usage", AssetCodes.USAGE_INVALID, "Not a use of a text: "
                        + asset.usage(), Map.of("usage", String.valueOf(asset.usage()))));
                }
                if (required(problems, kind, "body", present) && usageKnown) {
                    length(problems, "body", asset.body(), bodyLength(kind.name(), asset.usage()));
                }
            }
            case PRIZE -> prizeProblems(problems, asset, present);
        }
        return problems;
    }

    private static void prizeProblems(List<Violation> problems, Asset asset, Map<String, Boolean> present) {
        if (!required(problems, AssetKind.PRIZE, "prizeMethod", present)) {
            return;
        }
        String method = asset.prizeMethod();
        if (!PRIZE_METHODS.contains(method)) {
            problems.add(new Violation("prizeMethod", AssetCodes.PRIZE_METHOD_INVALID, "Not a prize's method: "
                + method, Map.of("method", String.valueOf(method))));
            return;
        }
        length(problems, "prizeDescription", text(asset.prizeDescription()), PRIZE_DESCRIPTION_LENGTH);
        // As a publication asks (PublicationReadiness): a prize at the sponsor's place or a digital one says what it
        // is; a digital one how it is delivered; only one the sponsor sends has days to send it in.
        if (!RewardMethod.SPONSOR_CASH.equals(method) && !present.get("prizeDescription")) {
            problems.add(required(AssetKind.PRIZE, "prizeDescription"));
        }
        boolean digital = RewardMethod.DIGITAL_PRIZE.equals(method);
        String delivery = text(asset.digitalDelivery());
        if (!digital && delivery != null) {
            problems.add(notAllowed(method, "digitalDelivery"));
        } else if (digital && (delivery == null || !DigitalDelivery.ALL.contains(delivery))) {
            problems.add(new Violation("digitalDelivery", AssetCodes.DIGITAL_DELIVERY_INVALID, "A digital prize is "
                + "delivered from a pool, shared, or sent by the sponsor", Map.of()));
        }
        Integer days = asset.digitalSendDays();
        if (days != null && !(digital && DigitalDelivery.SPONSOR_SENT.equals(delivery))) {
            problems.add(notAllowed(method, "digitalSendDays"));
        } else if (days != null && (days < 1 || days > PublishingLimits.MAX_DIGITAL_SEND_DAYS)) {
            problems.add(new Violation("digitalSendDays", AssetCodes.SEND_DAYS_RANGE, "A sponsor sends a prize in 1 to "
                + PublishingLimits.MAX_DIGITAL_SEND_DAYS + " days", Map.of("min", 1,
                    "max", PublishingLimits.MAX_DIGITAL_SEND_DAYS)));
        }
    }

    private static Violation notAllowed(String method, String field) {
        return new Violation(field, AssetCodes.FIELD_NOT_ALLOWED, "A prize of " + method + " has no " + field,
            Map.of("kind", method, "field", field));
    }

    /** The tags as stored: each without its surrounding white space, in the given order; none for none. */
    public static List<String> tags(List<String> given) {
        if (given == null) {
            return List.of();
        }
        return given.stream().map(tag -> tag == null ? "" : tag.strip()).toList();
    }

    private static List<Violation> tagProblems(List<String> given, int maxTags, int tagLength) {
        List<Violation> problems = new ArrayList<>();
        List<String> tags = tags(given);
        if (tags.size() > maxTags) {
            problems.add(new Violation("tags", AssetCodes.TOO_MANY_TAGS, "An asset has at most " + maxTags + " tags",
                Map.of("max", maxTags)));
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < tags.size(); i++) {
            String tag = tags.get(i);
            String field = "tags[" + i + "]";
            if (tag.isEmpty()) {
                problems.add(new Violation(field, AssetCodes.TAG_BLANK, "A tag is not empty"));
            } else if (tag.length() > tagLength) {
                problems.add(new Violation(field, AssetCodes.TAG_TOO_LONG, "A tag has at most " + tagLength
                    + " characters", Map.of("max", tagLength)));
            } else if (!seen.add(tag.toLowerCase(Locale.ROOT))) {
                problems.add(new Violation(field, AssetCodes.TAG_TWICE, "The tag " + tag + " is given twice",
                    Map.of("tag", tag)));
            }
        }
        return problems;
    }

    /**
     * The texts an asset is translated from, by field: its name and description, and an article's, a text's or a
     * prize's own text. Empty texts are none (nothing to translate).
     */
    public static Map<String, String> sources(Asset asset) {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("name", asset.name() == null ? null : text(asset.name().strip()));
        sources.put("description", text(asset.description()));
        String kind = asset.kind();
        if (AssetKind.ARTICLE.name().equals(kind) || AssetKind.TEXT.name().equals(kind)) {
            sources.put("body", text(asset.body()));
        } else if (AssetKind.PRIZE.name().equals(kind)) {
            sources.put("prizeDescription", text(asset.prizeDescription()));
        }
        return sources;
    }

    /** The longest translation of each field: the base field's. */
    public static Map<String, Integer> maxLengths(Asset asset) {
        return Map.of("name", NAME_LENGTH, "description", DESCRIPTION_LENGTH,
            "body", bodyLength(asset.kind(), asset.usage()), "prizeDescription", PRIZE_DESCRIPTION_LENGTH);
    }

    /**
     * The problems of the translations ({@link TextTranslations#problems}, none required): a language of the platform
     * other than the asset's own, at most each field's length, and none made from an older text.
     */
    public static List<Violation> translationProblems(String base, Map<String, Map<String, String>> given,
        Map<String, Map<String, PublicationLanguages.Text>> merged, Map<String, String> sources,
        Map<String, Integer> maxLengths) {
        List<String> languages = new ArrayList<>(given == null ? merged.keySet() : given.keySet());
        List<Violation> problems = new ArrayList<>(TextTranslations.problems(languages, languages, given, merged,
            sources, maxLengths, false));
        if (base != null && languages.contains(base)) {
            problems.add(new Violation("translations." + base, TextTranslations.LANGUAGES_INVALID, "The asset's own "
                + "language is not one of its translations: " + base, Map.of("language", base)));
        }
        return problems;
    }

    /**
     * The hash of an asset's text, which the library is deduplicated by: the SHA-256 of a link's address, an article's
     * or a text's body, or a prize's description, as given (UTF-8, no normalisation; the SQL
     * {@code encode(sha256(convert_to(t, 'UTF8')), 'hex')} gives the same). Null for a file and for no text.
     */
    public static String contentHash(Asset asset) {
        AssetKind kind = AssetKind.of(asset.kind()).orElse(null);
        if (kind == null) {
            return null;
        }
        return switch (kind) {
            case IMAGE, PDF, AUDIO -> null;
            case VIDEO_LINK, LINK -> sha256(text(asset.url()));
            case ARTICLE, TEXT -> sha256(text(asset.body()));
            case PRIZE -> sha256(text(asset.prizeDescription()));
        };
    }

    /** The lower-case hexadecimal SHA-256 of the text in UTF-8; null for none. */
    public static String sha256(String text) {
        if (text == null) {
            return null;
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * What two assets are the same by: a file's id ({@code f:<id>}), else the kind, the use (a text's) or method (a
     * prize's) and the content hash ({@code t:<kind>:<use>:<hash>}, as the template of candidates writes it). Null:
     * the same as nothing.
     */
    public static String dedupeKey(String kind, String usage, String prizeMethod, Object file, String hash) {
        if (file != null) {
            return "f:" + file;
        }
        if (hash == null) {
            return null;
        }
        String of = AssetKind.TEXT.name().equals(kind) ? usage : AssetKind.PRIZE.name().equals(kind) ? prizeMethod
            : null;
        return "t:" + kind + ":" + (of == null ? "" : of) + ":" + hash;
    }

    /** {@link #dedupeKey} of an asset as given. */
    public static String dedupeKey(Asset asset) {
        return dedupeKey(asset.kind(), asset.usage(), asset.prizeMethod(), file(asset), contentHash(asset));
    }

    /** The file an asset holds, of whichever kind; null for none. */
    public static UUID file(Asset asset) {
        return asset.image() != null ? asset.image() : asset.pdf() != null ? asset.pdf() : asset.audio();
    }

    /** The problem as one of the {@code index}-th item of a list ({@code items[<index>].<field>}). */
    public static Violation within(String list, int index, Violation problem) {
        String at = list + "[" + index + "]";
        Map<String, Object> params = new LinkedHashMap<>(problem.params());
        params.put("index", index + 1);
        return new Violation(problem.field() == null ? at : at + "." + problem.field(), problem.ruleCode(),
            "Item " + (index + 1) + ": " + problem.message(), params);
    }

    /** A text as stored: none for an empty one, otherwise as given. */
    public static String text(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static Map<String, Boolean> present(Asset asset) {
        Map<String, Boolean> present = new LinkedHashMap<>();
        present.put("image", asset.image() != null);
        present.put("pdf", asset.pdf() != null);
        present.put("audio", asset.audio() != null);
        present.put("url", text(asset.url()) != null);
        present.put("durationSec", asset.durationSec() != null);
        present.put("body", text(asset.body()) != null);
        present.put("usage", text(asset.usage()) != null);
        present.put("prizeMethod", text(asset.prizeMethod()) != null);
        present.put("prizeDescription", text(asset.prizeDescription()) != null);
        present.put("digitalDelivery", text(asset.digitalDelivery()) != null);
        present.put("digitalSendDays", asset.digitalSendDays() != null);
        return present;
    }

    private static boolean required(List<Violation> problems, AssetKind kind, String field,
        Map<String, Boolean> present) {
        if (present.get(field)) {
            return true;
        }
        problems.add(required(kind, field));
        return false;
    }

    private static Violation required(AssetKind kind, String field) {
        return new Violation(field, AssetCodes.CONTENT_REQUIRED, "An asset of kind " + kind + " has its " + field,
            Map.of("kind", kind.name(), "field", field));
    }

    private static void url(List<Violation> problems, AssetKind kind, String url) {
        if (url.length() > URL_LENGTH) {
            length(problems, "url", url, URL_LENGTH);
        } else if (!URL.matcher(url).matches()) {
            problems.add(new Violation("url", AssetCodes.URL_FORMAT, "A link starts with http:// or https://"));
        } else if (kind == AssetKind.VIDEO_LINK && VideoEmbeds.player(url).isEmpty()) {
            problems.add(new Violation("url", AssetCodes.VIDEO_SITE, "Not a video of a site QuizBuks plays"));
        }
    }

    private static void length(List<Violation> problems, String field, String value, int max) {
        if (value != null && value.length() > max) {
            problems.add(new Violation(field, AssetCodes.TOO_LONG, "The " + field + " is " + value.length()
                + " characters, more than " + max, Map.of("field", field, "length", value.length(), "max", max)));
        }
    }

    private AssetRules() {}
}
