package com.jabiz.culture;

import com.jabiz.entity.Violation;
import com.jabiz.runtime.EntityInstance;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/** Every rule of the publish check (docs/culture/00-design.md section 6.3), one at a time, against a valid story. */
class PublishCheckTest {

    private static final String STORY_ID = "s-1";
    private static final String PARTICIPANT_ID = "p-1";
    private static final String CONTRIBUTION_ID = "c-1";

    private static EntityInstance row(String entity, String id, Map<String, Object> attributes) {
        return new EntityInstance(id, entity, 1, null, attributes);
    }

    private static Map<String, Object> en(String text) {
        return Map.of("en", text);
    }

    /** A story that passes: a video story of one perspective with a photo, all consented. */
    private final Map<String, Object> story = new HashMap<>(Map.of(
        "title", en("What does home mean to us?"),
        "summary", en("Six places, one question."),
        "thumbnailFileId", UUID.randomUUID(),
        "thumbnailAlt", en("A kitchen table"),
        "mediaType", "VIDEO"));
    private final List<EntityInstance> themes = new ArrayList<>(List.of(row("StoryTheme", "t-1", Map.of())));
    private final Map<String, Object> contribution = new HashMap<>(Map.of(
        "storyId", STORY_ID, "participantId", PARTICIPANT_ID, "text", en("My home is noisy."),
        "videoProvider", "YOUTUBE", "videoId", "abcdef123", "captionsConfirmed", true));
    private final Map<String, Object> photo = new HashMap<>(Map.of(
        "storyId", STORY_ID, "contributionId", CONTRIBUTION_ID, "kind", "PHOTO", "alt", en("My street"),
        "showsIdentifiablePeople", false));
    private final Map<String, Object> participant = new HashMap<>(Map.of("status", "ACTIVE", "adult", true));
    private final List<Map<String, Object>> consents = new ArrayList<>(List.of(new HashMap<>(Map.of(
        "participantId", PARTICIPANT_ID, "party", "PARTICIPANT",
        "coversPhoto", true, "coversVideo", true, "coversVoice", true))));
    private final List<EntityInstance> extraMedia = new ArrayList<>();
    private boolean withContribution = true;
    private boolean guardianRequired = false;

    private List<Violation> check() {
        List<EntityInstance> media = new ArrayList<>(List.of(row("MediaItem", "m-1", photo)));
        media.addAll(extraMedia);
        List<EntityInstance> contributions = withContribution
            ? List.of(row("Contribution", CONTRIBUTION_ID, contribution)) : List.of();
        if (!withContribution) {
            media.clear();
        }
        List<EntityInstance> consentRows = new ArrayList<>();
        for (int i = 0; i < consents.size(); i++) {
            consentRows.add(row("Consent", "k-" + i, consents.get(i)));
        }
        return PublishCheck.check(new PublishCheck.Input(row("Story", STORY_ID, story), themes, contributions, media,
            Map.of(PARTICIPANT_ID, row("Participant", PARTICIPANT_ID, participant)), consentRows,
            guardianRequired));
    }

    private List<String> codes() {
        return check().stream().map(v -> v.field() + ":" + v.ruleCode()).toList();
    }

    private List<String> codesAfter(Consumer<PublishCheckTest> change) {
        change.accept(this);
        return codes();
    }

    @Test
    void aCompleteStoryPasses() {
        assertThat(check()).isEmpty();
    }

    @Test
    void englishTextsAreRequired() {
        assertThat(codesAfter(t -> {
            t.story.put("title", Map.of("ja", "家とは"));
            t.story.remove("summary");
            t.story.put("thumbnailAlt", Map.of("en", " "));
        })).containsExactlyInAnyOrder("Story.title:ENGLISH_REQUIRED", "Story.summary:ENGLISH_REQUIRED",
            "Story.thumbnailAlt:ENGLISH_REQUIRED");
    }

    @Test
    void aThumbnailIsRequired() {
        assertThat(codesAfter(t -> t.story.remove("thumbnailFileId")))
            .containsExactly("Story.thumbnailFileId:THUMBNAIL_REQUIRED");
    }

    @Test
    void aThemeIsRequired() {
        assertThat(codesAfter(t -> t.themes.clear())).containsExactly("Story.themes:THEME_REQUIRED");
    }

    @Test
    void articlesAndInterviewsNeedText() {
        assertThat(codesAfter(t -> {
            t.story.put("mediaType", "ARTICLE");
            t.contribution.remove("text");
        })).containsExactly("Story.body:BODY_REQUIRED");
        story.put("mediaType", "INTERVIEW");
        story.put("body", en("Q: What is home?"));
        assertThat(codes()).isEmpty();
    }

    @Test
    void videoStoriesNeedAVideo() {
        assertThat(codesAfter(t -> {
            t.contribution.remove("videoProvider");
            t.contribution.remove("videoId");
        })).containsExactly("Story.videoId:VIDEO_REQUIRED");
        story.put("videoProvider", "VIMEO");
        story.put("videoId", "123456789");
        story.put("captionsConfirmed", true);
        assertThat(codes()).isEmpty();
    }

    @Test
    void videosNeedConfirmedCaptions() {
        assertThat(codesAfter(t -> {
            t.contribution.put("captionsConfirmed", false);
            t.story.put("videoProvider", "YOUTUBE");
            t.story.put("videoId", "zyxwvu987");
        })).containsExactlyInAnyOrder("Contribution.captionsConfirmed:CAPTIONS_NOT_CONFIRMED",
            "Story.captionsConfirmed:CAPTIONS_NOT_CONFIRMED");
    }

    @Test
    void audioNeedsATranscript() {
        Map<String, Object> audio = new HashMap<>(Map.of("storyId", STORY_ID, "kind", "AUDIO"));
        extraMedia.add(row("MediaItem", "m-2", audio));
        assertThat(codesAfter(t -> t.contribution.put("audioFileId", UUID.randomUUID())))
            .containsExactlyInAnyOrder("Contribution.transcript:TRANSCRIPT_REQUIRED",
                "MediaItem.audioFileId:TRANSCRIPT_REQUIRED");
        contribution.put("transcript", en("..."));
        story.put("transcript", en("..."));
        assertThat(codes()).isEmpty();
    }

    @Test
    void photosNeedATextAlternative() {
        assertThat(codesAfter(t -> t.photo.remove("alt"))).containsExactly("MediaItem.alt:ALT_TEXT_REQUIRED");
    }

    @Test
    void recognisablePeopleMustHaveAgreed() {
        assertThat(codesAfter(t -> t.photo.put("showsIdentifiablePeople", true)))
            .containsExactly("MediaItem.peopleConsentConfirmed:PEOPLE_CONSENT_NOT_CONFIRMED");
        photo.put("peopleConsentConfirmed", true);
        assertThat(codes()).isEmpty();
    }

    @Test
    void mediaMustBelongToAPerspectiveOfTheStory() {
        extraMedia.add(row("MediaItem", "m-3", Map.of("storyId", STORY_ID, "contributionId", "c-other",
            "kind", "PHOTO", "alt", en("Elsewhere"))));
        assertThat(codes()).containsExactly("MediaItem.contributionId:MEDIA_STORY_MISMATCH");
    }

    @Test
    void theParticipantMustBeActive() {
        assertThat(codesAfter(t -> t.participant.put("status", "HIDDEN")))
            .containsExactly("Contribution.participantId:PARTICIPANT_NOT_ACTIVE");
    }

    @Test
    void consentMustCoverWhatIsPublished() {
        consents.getFirst().put("coversPhoto", false);
        consents.getFirst().put("coversVideo", false);
        List<Violation> violations = check();
        assertThat(violations).extracting(v -> v.field() + ":" + v.ruleCode())
            .containsExactly("Contribution.participantId:CONSENT_MISSING");
        assertThat(violations.getFirst().params()).containsEntry("participant", PARTICIPANT_ID)
            .containsEntry("missing", "PHOTO, VIDEO").containsEntry("entity", "Contribution")
            .containsEntry("id", CONTRIBUTION_ID);
    }

    @Test
    void withdrawnConsentAndTheGuardianSwitchCount() {
        consents.getFirst().put("withdrawnTime", Instant.parse("2026-05-01T00:00:00Z"));
        assertThat(check()).extracting(v -> v.params().get("missing")).containsExactly("PARTICIPANT");

        consents.getFirst().put("withdrawnTime", null);
        participant.put("adult", false);
        guardianRequired = true;
        assertThat(check()).extracting(v -> v.params().get("missing")).containsExactly("GUARDIAN");
        guardianRequired = false;
        assertThat(check()).isEmpty();
    }

    @Test
    void aCorrespondentsPerspectiveMustBeTheirOwn() {
        participant.put("accountActorId", "cu-jp-01");
        contribution.put("ownerActorId", "cu-jp-01");
        photo.put("ownerActorId", "cu-pl-01");
        assertThat(codes()).containsExactly("MediaItem.ownerActorId:CONTRIBUTION_OWNER_MISMATCH");
        contribution.put("ownerActorId", "cu-pl-01");
        assertThat(codes()).containsExactly("Contribution.ownerActorId:CONTRIBUTION_OWNER_MISMATCH",
            "MediaItem.ownerActorId:CONTRIBUTION_OWNER_MISMATCH");
    }

    @Test
    void storyLevelMediaOfACorrespondentBelongOnTheirOwnStory() {
        extraMedia.add(row("MediaItem", "m-4", Map.of("storyId", STORY_ID, "kind", "PHOTO", "alt", en("A lake"),
            "ownerActorId", "cu-se-01")));
        assertThat(codes()).containsExactly("MediaItem.ownerActorId:CONTRIBUTION_OWNER_MISMATCH");
        story.put("ownerActorId", "cu-se-01");
        assertThat(codes()).isEmpty();
    }

    @Test
    void thereAreNoEmptyStories() {
        withContribution = false;
        story.put("mediaType", "PHOTO");
        assertThat(codes()).containsExactly("Story.body:STORY_EMPTY");
        story.put("body", en("A photo essay."));
        assertThat(codes()).isEmpty();
    }

    @Test
    void anEmptyArticleIsOneProblem() {
        withContribution = false;
        story.put("mediaType", "ARTICLE");
        assertThat(codes()).containsExactly("Story.body:BODY_REQUIRED");
    }

    @Test
    void violationsNameOnlyKeysAndCodes() {
        participant.put("status", "DRAFT");
        story.remove("thumbnailFileId");
        assertThat(check()).allSatisfy(v -> assertThat(v.params().values())
            .allSatisfy(value -> assertThat(String.valueOf(value)).doesNotContain("home", "noisy", "street")));
    }
}
