package com.jabiz.culture.it;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.jabiz.culture.Culture.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which files anonymous visitors get (docs/culture/00-design.md sections 3.11 and 6.4; docs/design/15-public-access.md
 * section 4): only those that published content shows. Consent scans never; drafts and offline content not; content
 * taken offline or whose consent was withdrawn stops at once, since its processes end the remembered decisions.
 *
 * <p>Decisions are remembered (also negative ones) for a minute: each file here is asked for anonymously only in states
 * whose answer does not change afterwards except through such a process.
 */
class PublicFilesIT extends PublicItSupport {

    @Test
    void consentScansAreNeverServedAnonymously() {
        String place = location("Tokyo area");
        String participant = participant(place, null, true);
        String scan = consentScan();
        create(dataset(CONSENT), curator(), Map.of("participantId", participant, "party", "PARTICIPANT",
            "coversPhoto", true, "coversVideo", true, "coversVoice", true, "signedOn", "2026-01-10T00:00:00Z",
            "documentFileId", scan));
        ok("CULTURE_PARTICIPANT_ACTIVATE", curator(), Map.of("participantId", participant));
        published(Map.of(), List.of(theme("home")), List.of(participant));

        // The participant is public and in a published story; their consent record is not, nor its scan.
        assertThat(publicFile(scan)).isEqualTo(404);
        // Editors with the consent permission still get it.
        get("/api/files/" + scan + "/content", curator()).expectStatus().isOk();
    }

    @Test
    void draftsAndUnpublishedAdditionsAreNotServed() {
        String place = location("Mbabane");
        String draftPortrait = photo(curator());
        String draft = create(dataset(PARTICIPANT), curator(), new HashMap<>(Map.of("slug", unique("person"),
            "displayName", "Sipho", "locationId", place, "adult", true, "portraitFileId", draftPortrait,
            "portraitAlt", en("A portrait"))));
        String draftStory = story(Map.of(), List.of(theme("food")), List.of(activeParticipant(place, null)));
        String draftThumbnail = (String) attributes(dataset(STORY), draftStory).get("thumbnailFileId");

        // A photo added to a published story waits for the next publication.
        String story = published(Map.of(), List.of(theme("school")), List.of(activeParticipant(place, null)));
        String late = photo(curator());
        create(dataset(MEDIA_ITEM), curator(), Map.of("storyId", story, "kind", PHOTO, "imageFileId", late,
            "alt", en("A classroom")));

        assertThat(publicFile(draftPortrait)).isEqualTo(404);
        assertThat(publicFile(draftThumbnail)).isEqualTo(404);
        assertThat(publicFile(late)).isEqualTo(404);
        assertThat(attributes(dataset(PARTICIPANT), draft)).containsEntry("status", DRAFT);
    }

    @Test
    void unpublishingStopsTheStorysFilesAtOnce() {
        String place = location("Tokyo area");
        String participant = activeParticipant(place, null);
        String story = story(Map.of(), List.of(theme("home")), List.of());
        String audio = audio(curator());
        Map<String, Object> contribution = contribution(story, participant);
        contribution.put("audioFileId", audio);
        contribution.put("transcript", en("What I said."));
        String perspective = create(dataset(CONTRIBUTION), curator(), contribution);
        String photo = photo(curator());
        create(dataset(MEDIA_ITEM), curator(), Map.of("storyId", story, "contributionId", perspective,
            "kind", PHOTO, "imageFileId", photo, "alt", en("My street")));
        ok(PUBLISH, curator(), Map.of("storyId", story));
        String thumbnail = (String) attributes(dataset(STORY), story).get("thumbnailFileId");

        for (String file : List.of(thumbnail, audio, photo)) {
            assertThat(publicFile(file)).as(file).isEqualTo(200);
        }
        ok(UNPUBLISH, curator(), Map.of("storyId", story));
        for (String file : List.of(thumbnail, audio, photo)) {
            assertThat(publicFile(file)).as(file).isEqualTo(404);
        }
    }

    @Test
    void withdrawingConsentStopsTheParticipantsFilesAtOnce() {
        String place = location("Tokyo area");
        String participant = person(place, "Aiko", en("Interested in food."));
        String portrait = (String) attributes(dataset(PARTICIPANT), participant).get("portraitFileId");
        String other = person(place, "Ren", en("Interested in trains."));
        String otherPortrait = (String) attributes(dataset(PARTICIPANT), other).get("portraitFileId");
        String story = published(Map.of(), List.of(theme("home")), List.of(participant, other));
        String thumbnail = (String) attributes(dataset(STORY), story).get("thumbnailFileId");
        assertThat(publicFile(portrait)).isEqualTo(200);
        assertThat(publicFile(thumbnail)).isEqualTo(200);

        String consent = (String) query(dataset(CONSENT), curator(), eq("participantId", participant)).getFirst()
            .get("id");
        ok("CULTURE_CONSENT_WITHDRAW", curator(), Map.of("consentId", consent));

        assertThat(publicFile(portrait)).isEqualTo(404);
        // The story relied on the consent: it is offline, its thumbnail too; the other participant stays public.
        assertThat(publicFile(thumbnail)).isEqualTo(404);
        assertThat(publicFile(otherPortrait)).isEqualTo(200);
    }

    @Test
    void hidingAParticipantStopsTheirPortraitAtOnce() {
        String participant = person(location("Warsaw"), "Ola", en("Interested in music."));
        String portrait = (String) attributes(dataset(PARTICIPANT), participant).get("portraitFileId");
        assertThat(publicFile(portrait)).isEqualTo(200);

        ok("CULTURE_PARTICIPANT_HIDE", curator(), Map.of("participantId", participant));

        assertThat(publicFile(portrait)).isEqualTo(404);
    }

    @Test
    void unpublishingAResourceStopsItsPdfAtOnce() {
        String pdf = upload("culture.pdf", com.jabiz.runtime.test.FileSamples.pdf(), "activity.pdf",
            "application/pdf", curator());
        String resource = create(dataset(RESOURCE), curator(), Map.of("slug", unique("resource"),
            "title", en("Mapping home"), "description", en("Draw what home means."), "activityType", "CLASSROOM",
            "ageGroup", "14-16", "durationMinutes", 45, "pdfFileId", pdf));
        ok("CULTURE_RESOURCE_PUBLISH", curator(), Map.of("resourceId", resource));
        assertThat(publicFile(pdf)).isEqualTo(200);

        ok("CULTURE_RESOURCE_UNPUBLISH", curator(), Map.of("resourceId", resource));

        assertThat(publicFile(pdf)).isEqualTo(404);
    }
}
