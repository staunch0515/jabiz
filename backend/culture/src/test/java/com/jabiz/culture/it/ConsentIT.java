package com.jabiz.culture.it;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static com.jabiz.culture.Culture.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Withdrawing consent and erasing a participant (docs/culture/00-design.md sections 6.2 and 6.4): whatever of the
 * participant is public goes offline at once, and erasure leaves neither rows nor files behind.
 */
class ConsentIT extends CultureItSupport {

    private static final String WITHDRAW = "CULTURE_CONSENT_WITHDRAW";
    private static final String ERASE = "CULTURE_PARTICIPANT_ERASE";
    private static final String PUBLISH = "CULTURE_STORY_PUBLISH";

    /** A story with a perspective of each participant, published. */
    private String publishedStory(String theme, String... participants) {
        String story = create(dataset(STORY), curator(), storyAttributes(curator()));
        link(story, theme);
        for (String participant : participants) {
            create(dataset(CONTRIBUTION), curator(), contribution(story, participant));
        }
        ok(PUBLISH, curator(), Map.of("storyId", story));
        return story;
    }

    private String consentOf(String participant) {
        return (String) query(dataset(CONSENT), curator(), eq("participantId", participant)).getFirst().get("id");
    }

    private String status(String entity, String id) {
        return (String) attributes(dataset(entity), id).get("status");
    }

    @Test
    @SuppressWarnings("unchecked")
    void withdrawingConsentTakesEveryStoryWithTheParticipantOffline() {
        String place = location("Poland");
        String theme = theme("home");
        String withdrawing = activeParticipant(place, null);
        String staying = activeParticipant(place, null);
        String shared = publishedStory(theme, withdrawing, staying);
        String alone = publishedStory(theme, withdrawing);
        String others = publishedStory(theme, staying);
        String draft = create(dataset(STORY), curator(), storyAttributes(curator()));
        create(dataset(CONTRIBUTION), curator(), contribution(draft, withdrawing));

        Map<String, Object> out = ok(WITHDRAW, curator(), Map.of("consentId", consentOf(withdrawing)));

        assertThat(out).containsEntry("participantStatus", WITHDRAWN);
        assertThat((List<Object>) out.get("unpublishedStories")).containsExactlyInAnyOrder(shared, alone);
        assertThat(status(PARTICIPANT, withdrawing)).isEqualTo(WITHDRAWN);
        assertThat(status(STORY, shared)).isEqualTo(UNPUBLISHED);
        assertThat(status(STORY, alone)).isEqualTo(UNPUBLISHED);
        assertThat(status(STORY, others)).isEqualTo(PUBLISHED);
        assertThat(status(STORY, draft)).isEqualTo(DRAFT);
        // Every part of the stories taken offline is private, the other participant's included.
        assertThat(query(dataset(CONTRIBUTION), curator(), eq("storyId", shared)))
            .hasSize(2).allSatisfy(c -> assertThat(attributesOf(c)).containsEntry("visibility", PRIVATE));
        assertThat(query(dataset(STORY_THEME), curator(), eq("storyId", alone)))
            .allSatisfy(t -> assertThat(attributesOf(t)).containsEntry("visibility", PRIVATE));
        assertThat(attributes(dataset(CONSENT), consentOf(withdrawing))).containsEntry("withdrawnTime",
            START.toString());

        assertThat(refused(WITHDRAW, curator(), Map.of("consentId", consentOf(withdrawing)), 422))
            .containsExactly("withdrawnTime:CONSENT_ALREADY_WITHDRAWN");
        // Back only with a new consent, through activation and publication.
        assertThat(refused(PUBLISH, curator(), Map.of("storyId", alone), 422))
            .containsExactly("Contribution.participantId:PARTICIPANT_NOT_ACTIVE");
    }

    @Test
    void withdrawingAConsentThatIsNotNeededChangesNothingElse() {
        String place = location("Sweden");
        String participant = activeParticipant(place, null);
        String story = publishedStory(theme("school"), participant);
        String second = consent(participant, PARTY_PARTICIPANT, true, true, true);

        Map<String, Object> out = ok(WITHDRAW, curator(), Map.of("consentId", second));
        assertThat(out).containsEntry("participantStatus", ACTIVE);
        assertThat((List<?>) out.get("unpublishedStories")).isEmpty();
        assertThat(status(STORY, story)).isEqualTo(PUBLISHED);
    }

    @Test
    void consentMustKeepCoveringTheMediaThatArePublic() {
        String place = location("Singapore");
        String participant = participant(place, null, true);
        String full = consent(participant, PARTY_PARTICIPANT, true, true, true);
        consent(participant, PARTY_PARTICIPANT, true, true, false);
        ok("CULTURE_PARTICIPANT_ACTIVATE", curator(), Map.of("participantId", participant));
        String story = create(dataset(STORY), curator(), storyAttributes(curator()));
        link(story, theme("language"));
        Map<String, Object> voice = contribution(story, participant);
        voice.put("audioFileId", audio(curator()));
        voice.put("transcript", en("Hello from Singapore."));
        create(dataset(CONTRIBUTION), curator(), voice);
        ok(PUBLISH, curator(), Map.of("storyId", story));

        // The remaining consent does not cover the voice recording that is public.
        assertThat(ok(WITHDRAW, curator(), Map.of("consentId", full))).containsEntry("participantStatus", WITHDRAWN);
        assertThat(status(STORY, story)).isEqualTo(UNPUBLISHED);
    }

    @Test
    void erasureDeletesTheParticipantsRowsAndFiles() {
        String place = location("Japan");
        String participant = participant(place, null, true);
        String portrait = photo(curator());
        commit(dataset(PARTICIPANT), curator(), update(participant, 1, Map.of("portraitFileId", portrait,
            "portraitAlt", en("A drawing of Aiko")))).expectStatus().isOk();
        String scan = consentScan();
        String consent = create(dataset(CONSENT), curator(), Map.of("participantId", participant,
            "party", PARTY_PARTICIPANT, "coversPhoto", true, "coversVideo", true, "coversVoice", true,
            "signedOn", "2026-01-10T00:00:00Z", "documentFileId", scan));
        ok("CULTURE_PARTICIPANT_ACTIVATE", curator(), Map.of("participantId", participant));
        String other = activeParticipant(place, null);

        String story = create(dataset(STORY), curator(), storyAttributes(curator()));
        link(story, theme("food"));
        Map<String, Object> mine = contribution(story, participant);
        String voice = audio(curator());
        mine.put("audioFileId", voice);
        mine.put("transcript", en("What we eat on Sundays."));
        String contribution = create(dataset(CONTRIBUTION), curator(), mine);
        String photo = photo(curator());
        String media = create(dataset(MEDIA_ITEM), curator(), new HashMap<>(Map.of("storyId", story,
            "contributionId", contribution, "kind", PHOTO, "imageFileId", photo, "alt", en("Rice and miso soup"))));
        String theirs = create(dataset(CONTRIBUTION), curator(), contribution(story, other));
        ok(PUBLISH, curator(), Map.of("storyId", story));

        // Only a withdrawn participant can be erased.
        assertThat(refused(ERASE, admin(), Map.of("participantId", participant), 422))
            .containsExactly("status:WRONG_STATE");
        run(ERASE, curator(), Map.of("participantId", participant)).expectStatus().isForbidden();
        ok(WITHDRAW, curator(), Map.of("consentId", consent));

        Map<String, Object> out = ok(ERASE, admin(), Map.of("participantId", participant));
        assertThat(out).containsEntry("deletedRows", 4).containsEntry("deletedFiles", 4);

        assertThat(query("SELECT 1 FROM cu_participant WHERE participant_id = ?", participant)).isEmpty();
        assertThat(query("SELECT 1 FROM cu_consent WHERE participant_id = ?", participant)).isEmpty();
        assertThat(query("SELECT 1 FROM cu_contribution WHERE contribution_id = ?", contribution)).isEmpty();
        assertThat(query("SELECT 1 FROM cu_media_item WHERE media_item_id = ?", media)).isEmpty();
        for (String file : List.of(portrait, scan, voice, photo)) {
            assertThat(query("SELECT 1 FROM sys_file WHERE file_id = ?", UUID.fromString(file))).as(file).isEmpty();
            awaitNoStoredObjects(file);
        }
        // Everything else stays: the story, the other participant and their perspective.
        assertThat(status(STORY, story)).isEqualTo(UNPUBLISHED);
        assertThat(query("SELECT 1 FROM cu_participant WHERE participant_id = ?", other)).hasSize(1);
        assertThat(query("SELECT 1 FROM cu_contribution WHERE contribution_id = ?", theirs)).hasSize(1);
        // The operation records hold keys only.
        assertThat(query("SELECT input_summary::text AS summary FROM op_process WHERE process_name = ?", ERASE))
            .allSatisfy(row -> assertThat(String.valueOf(row.get("summary"))).doesNotContain("Aiko"));
    }

    /** The stored objects are removed after the commit, on their own. */
    private static void awaitNoStoredObjects(String fileId) {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (storedPaths(fileId).isEmpty()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        assertThat(storedPaths(fileId)).as("stored objects of " + fileId).isEmpty();
    }

    private static List<Path> storedPaths(String fileId) {
        try (Stream<Path> walk = Files.walk(filesRoot())) {
            return walk.filter(Files::isRegularFile).filter(path -> path.toString().contains(fileId)).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> attributesOf(Map<String, Object> row) {
        return (Map<String, Object>) row.get("attributes");
    }
}
