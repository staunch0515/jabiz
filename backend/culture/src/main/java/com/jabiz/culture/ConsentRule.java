package com.jabiz.culture;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.jabiz.culture.Culture.PARTY_GUARDIAN;
import static com.jabiz.culture.Culture.PARTY_PARTICIPANT;

/**
 * When a participant's content may be public (docs/culture/00-design.md section 6.2): a consent of the participant
 * themselves, always; a guardian's too when the switch requires it and the participant is not an adult; and every
 * kind of media to be published covered by each of the required consents. Withdrawn consents do not count.
 */
public final class ConsentRule {

    /** Kinds of media a consent covers; text and the name are covered by the consent itself. */
    public enum Media {
        PHOTO("coversPhoto"), VIDEO("coversVideo"), VOICE("coversVoice");

        private final String field;

        Media(String field) {
            this.field = field;
        }

        String field() {
            return field;
        }
    }

    /**
     * What is missing for the participant's content to be public, as codes: {@code PARTICIPANT} or {@code GUARDIAN}
     * for a missing consent, then {@code PHOTO}, {@code VIDEO} or {@code VOICE} for a kind of media some required
     * consent does not cover. Empty when nothing is missing.
     *
     * @param consents the participant's consent records (attributes), withdrawn ones included
     */
    public static List<String> missing(boolean adult, boolean guardianRequired,
        Collection<? extends Map<String, Object>> consents, Set<Media> media) {
        List<String> parties = new ArrayList<>(List.of(PARTY_PARTICIPANT));
        if (guardianRequired && !adult) {
            parties.add(PARTY_GUARDIAN);
        }
        List<String> missing = new ArrayList<>();
        Set<Media> uncovered = EnumSet.noneOf(Media.class);
        for (String party : parties) {
            List<? extends Map<String, Object>> valid = consents.stream()
                .filter(c -> party.equals(c.get("party")) && c.get("withdrawnTime") == null)
                .toList();
            if (valid.isEmpty()) {
                missing.add(party);
                continue;
            }
            for (Media kind : media) {
                if (valid.stream().noneMatch(c -> Boolean.TRUE.equals(c.get(kind.field())))) {
                    uncovered.add(kind);
                }
            }
        }
        uncovered.forEach(kind -> missing.add(kind.name()));
        return List.copyOf(missing);
    }

    private ConsentRule() {}
}
