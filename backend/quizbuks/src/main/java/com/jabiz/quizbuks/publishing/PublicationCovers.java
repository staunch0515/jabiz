package com.jabiz.quizbuks.publishing;

import com.jabiz.entity.Violation;
import com.jabiz.quizbuks.content.QbContent;
import com.jabiz.quizbuks.content.QuizSnapshot;
import com.jabiz.quizbuks.content.QuizSnapshots;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Covers per language (docs/quizbuks/plans/Q98-covers.md, Q98b, D-Q98-6 to D-Q98-8): a publication may have a cover of
 * its own in each language it offers besides its version's base ({@code coverJa}, {@code coverZh}, {@code coverEn});
 * its card keeps, for each language, the cover chosen in this order: the publication's own in that language, the
 * publication's own ({@code cover}), the version's in that language, the version's. A language whose cover is the
 * card's {@code cover} keeps none (a reader of it is shown {@code cover}).
 */
public final class PublicationCovers {

    /** The field of each language's cover, on the publication and on its card (the platform's languages). */
    public static final Map<String, String> FIELDS = fields();

    private static Map<String, String> fields() {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String language : QbContent.LANGUAGES) {
            fields.put(language, "cover" + Character.toUpperCase(language.charAt(0)) + language.substring(1));
        }
        return java.util.Collections.unmodifiableMap(fields);
    }

    private PublicationCovers() {}

    /**
     * Why the covers given cannot be saved: a language not of the platform's, or not one the publication offers besides
     * its base (whose cover is {@code cover}).
     */
    public static List<Violation> refusals(Map<String, UUID> covers, List<String> offered, String base) {
        List<Violation> refusals = new ArrayList<>();
        if (covers == null) {
            return refusals;
        }
        covers.forEach((language, file) -> {
            if (file == null) {
                return;
            }
            if (!FIELDS.containsKey(language) || !offered.contains(language) || language.equals(base)) {
                refusals.add(new Violation("covers." + language, PublishingCodes.COVER_LANGUAGE, "A cover of its own "
                    + "only for a language the publication offers besides its base", Map.of("language",
                    String.valueOf(language))));
            }
        });
        return refusals;
    }

    /** The publication's own covers by language, as its fields hold them (none for a language without one). */
    public static Map<String, String> own(Function<String, Object> field) {
        Map<String, String> own = new LinkedHashMap<>();
        FIELDS.forEach((language, name) -> {
            Object value = field.apply(name);
            if (value != null) {
                own.put(language, value.toString());
            }
        });
        return own;
    }

    /**
     * The card's cover of each language (D-Q98-7), by its field: null where it is the card's {@code base} cover.
     *
     * @param publication the publication's fields
     * @param snapshot    the version's snapshot, its languages' own covers included
     * @param base        the card's {@code cover} ({@link PublicationEditing#cardCover})
     */
    public static Map<String, Object> card(Function<String, Object> publication, QuizSnapshot snapshot, String base) {
        Map<String, Object> card = new LinkedHashMap<>();
        Object ownBase = publication.apply("cover");
        FIELDS.forEach((language, name) -> {
            Object own = publication.apply(name);
            String chosen;
            if (own != null) {
                chosen = own.toString();
            } else if (ownBase != null) {
                chosen = ownBase.toString();
            } else {
                UUID version = snapshot == null ? null : QuizSnapshots.in(snapshot, language).cover();
                chosen = version == null ? base : version.toString();
            }
            card.put(name, chosen == null || chosen.equals(base) ? null : chosen);
        });
        return card;
    }
}
