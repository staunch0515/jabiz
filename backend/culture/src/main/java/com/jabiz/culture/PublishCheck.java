package com.jabiz.culture;

import com.jabiz.entity.Violation;
import com.jabiz.runtime.EntityInstance;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.jabiz.culture.Culture.*;

/**
 * The publish check of a story (docs/culture/00-design.md section 6.3): everything that must hold before the story
 * and all its parts go public. All violations are returned at once, so an editor sees the whole list. Fields are
 * named {@code <Entity>.<field>}; parameters hold keys and codes only, never names or text, since the errors of a
 * failed operation are recorded.
 */
public final class PublishCheck {

    /**
     * @param story         the story
     * @param themes        its theme links
     * @param contributions its perspectives
     * @param media         its photos and audio items
     * @param participants  the participants of the perspectives, by key
     * @param consents      the consent records of those participants
     */
    public record Input(EntityInstance story, List<EntityInstance> themes, List<EntityInstance> contributions,
        List<EntityInstance> media, Map<Object, EntityInstance> participants, List<EntityInstance> consents,
        boolean guardianRequired) {

        public Input {
            themes = List.copyOf(themes);
            contributions = List.copyOf(contributions);
            media = List.copyOf(media);
            participants = Map.copyOf(participants);
            consents = List.copyOf(consents);
        }
    }

    public static List<Violation> check(Input in) {
        List<Violation> violations = new ArrayList<>();
        EntityInstance story = in.story();
        Map<String, Object> s = story.attributes();

        // 1. The story itself.
        for (String field : List.of("title", "summary", "thumbnailAlt")) {
            if (!CultureEntities.hasEnglish(s.get(field))) {
                violations.add(violation(story, field, "ENGLISH_REQUIRED", "The English text is missing"));
            }
        }
        if (s.get("thumbnailFileId") == null) {
            violations.add(violation(story, "thumbnailFileId", "THUMBNAIL_REQUIRED", "The story has no thumbnail"));
        }
        if (in.themes().isEmpty()) {
            violations.add(violation(story, "themes", "THEME_REQUIRED", "The story belongs to no theme"));
        }
        boolean storyText = hasText(s.get("body"));
        boolean contributionText = in.contributions().stream().anyMatch(c -> hasText(c.get("text")));
        String mediaType = String.valueOf((Object) s.get("mediaType"));
        boolean bodyMissing = ("ARTICLE".equals(mediaType) || "INTERVIEW".equals(mediaType)) && !storyText
            && !contributionText;
        if (bodyMissing) {
            violations.add(violation(story, "body", "BODY_REQUIRED", "An article or interview needs its text"));
        }
        boolean storyVideo = s.get("videoId") != null;
        if ("VIDEO".equals(mediaType) && !storyVideo
            && in.contributions().stream().noneMatch(c -> c.get("videoId") != null)) {
            violations.add(violation(story, "videoId", "VIDEO_REQUIRED", "A video story needs a video"));
        }
        // 6. No empty story.
        if (in.contributions().isEmpty() && !storyText && !bodyMissing) {
            violations.add(violation(story, "body", "STORY_EMPTY", "The story has neither perspectives nor text"));
        }

        // 2, 3: videos have confirmed captions, audio has a transcript, photos have a text alternative.
        if (storyVideo && !Boolean.TRUE.equals(s.get("captionsConfirmed"))) {
            violations.add(violation(story, "captionsConfirmed", "CAPTIONS_NOT_CONFIRMED",
                "The captions of the video are not confirmed"));
        }
        Map<Object, EntityInstance> contributions = new LinkedHashMap<>();
        for (EntityInstance c : in.contributions()) {
            contributions.put(c.id(), c);
            if (c.get("videoId") != null && !Boolean.TRUE.equals(c.get("captionsConfirmed"))) {
                violations.add(violation(c, "captionsConfirmed", "CAPTIONS_NOT_CONFIRMED",
                    "The captions of the video are not confirmed"));
            }
            if (c.get("audioFileId") != null && !hasText(c.get("transcript"))) {
                violations.add(violation(c, "transcript", "TRANSCRIPT_REQUIRED", "The audio has no transcript"));
            }
        }
        for (EntityInstance m : in.media()) {
            Object contributionId = m.get("contributionId");
            if (contributionId != null && !contributions.containsKey(contributionId)) {
                violations.add(violation(m, "contributionId", "MEDIA_STORY_MISMATCH",
                    "The item belongs to a perspective of another story"));
            }
            if (PHOTO.equals(m.get("kind"))) {
                if (!CultureEntities.hasEnglish(m.get("alt"))) {
                    violations.add(violation(m, "alt", "ALT_TEXT_REQUIRED", "The photo has no English text alternative"));
                }
                // 4. People who can be recognised agreed to be shown.
                if (Boolean.TRUE.equals(m.get("showsIdentifiablePeople"))
                    && !Boolean.TRUE.equals(m.get("peopleConsentConfirmed"))) {
                    violations.add(violation(m, "peopleConsentConfirmed", "PEOPLE_CONSENT_NOT_CONFIRMED",
                        "The consent of the people in the photo is not confirmed"));
                }
            } else if (AUDIO.equals(m.get("kind"))) {
                EntityInstance owner = contributionId == null ? null : contributions.get(contributionId);
                Object transcript = owner != null ? owner.get("transcript") : s.get("transcript");
                if (!hasText(transcript)) {
                    violations.add(violation(m, "audioFileId", "TRANSCRIPT_REQUIRED", "The audio has no transcript"));
                }
            }
        }

        // 5. Every perspective, with its media, is backed by an active participant's consent, and a
        // correspondent's perspective is their own.
        for (EntityInstance c : in.contributions()) {
            EntityInstance participant = in.participants().get(c.get("participantId"));
            Set<ConsentRule.Media> needs = EnumSet.noneOf(ConsentRule.Media.class);
            if (c.get("videoId") != null) {
                needs.add(ConsentRule.Media.VIDEO);
            }
            if (c.get("audioFileId") != null) {
                needs.add(ConsentRule.Media.VOICE);
            }
            List<EntityInstance> ownMedia = in.media().stream()
                .filter(m -> Objects.equals(m.get("contributionId"), c.id())).toList();
            for (EntityInstance m : ownMedia) {
                needs.add(PHOTO.equals(m.get("kind")) ? ConsentRule.Media.PHOTO : ConsentRule.Media.VOICE);
            }
            if (participant == null || !ACTIVE.equals(participant.get("status"))) {
                violations.add(violation(c, "participantId", "PARTICIPANT_NOT_ACTIVE",
                    "The participant of the perspective is not active", Map.of("participant", String.valueOf((Object) c.get("participantId")))));
                continue;
            }
            List<Map<String, Object>> consents = in.consents().stream()
                .filter(k -> Objects.equals(k.get("participantId"), participant.id()))
                .map(EntityInstance::attributes)
                .toList();
            List<String> missing = ConsentRule.missing(Boolean.TRUE.equals(participant.get("adult")),
                in.guardianRequired(), consents, needs);
            if (!missing.isEmpty()) {
                violations.add(violation(c, "participantId", "CONSENT_MISSING", "Consent is missing",
                    Map.of("participant", String.valueOf(participant.id()), "missing", String.join(", ", missing))));
            }
            Object account = participant.get("accountActorId");
            for (EntityInstance owned : concat(List.of(c), ownMedia)) {
                Object owner = owned.get("ownerActorId");
                if (owner != null && !owner.equals(account)) {
                    violations.add(violation(owned, "ownerActorId", "CONTRIBUTION_OWNER_MISMATCH",
                        "Added by someone other than the participant",
                        Map.of("participant", String.valueOf(participant.id()))));
                }
            }
        }
        return List.copyOf(violations);
    }

    /** Whether a multilingual text has some content in any language. */
    static boolean hasText(Object text) {
        return text instanceof Map<?, ?> map && map.values().stream()
            .anyMatch(value -> value instanceof String string && !string.isBlank());
    }

    private static List<EntityInstance> concat(Collection<EntityInstance> a, Collection<EntityInstance> b) {
        List<EntityInstance> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }

    private static Violation violation(EntityInstance on, String field, String code, String message) {
        return violation(on, field, code, message, Map.of());
    }

    private static Violation violation(EntityInstance on, String field, String code, String message,
        Map<String, Object> params) {
        Map<String, Object> all = new LinkedHashMap<>(params);
        all.put("entity", on.entityType());
        all.put("id", String.valueOf(on.id()));
        return new Violation(on.entityType() + "." + field, code, message, all);
    }

    private PublishCheck() {}
}
