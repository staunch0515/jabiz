package com.jabiz.culture.it;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.jabiz.culture.Culture.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The workflow of stories through the HTTP API (docs/culture/00-design.md sections 5 and 6): what correspondents can
 * see and change, submitting, returning, publishing and unpublishing, and the two switches.
 */
class StoryWorkflowIT extends CultureItSupport {

    private static final String SUBMIT = "CULTURE_STORY_SUBMIT";
    private static final String RETURN = "CULTURE_STORY_RETURN";
    private static final String PUBLISH = "CULTURE_STORY_PUBLISH";
    private static final String UNPUBLISH = "CULTURE_STORY_UNPUBLISH";
    private static final String REOPEN = "CULTURE_STORY_REOPEN";

    /** A correspondent's draft story in a theme, with their own perspective; returns the story and perspective. */
    private String[] draftOf(String account) {
        String place = location("Japan");
        activeParticipant(place, account);
        String participant = (String) query(ownView(PARTICIPANT), correspondent(account), List.of()).getFirst()
            .get("id");
        String story = create(own(STORY), correspondent(account), storyAttributes(correspondent(account)));
        link(story, theme("home"));
        String contribution = create(own(CONTRIBUTION), correspondent(account), contribution(story, participant));
        return new String[] {story, contribution};
    }

    @Test
    void correspondentsSeeAndChangeOnlyTheirOwnContent() {
        String[] mine = draftOf(unique("cu-jp"));
        String other = unique("cu-pl");
        String story = mine[0];

        // Another correspondent: not in their datasets, and not reachable around them.
        assertThat(query(ownView(STORY), correspondent(other), List.of())).isEmpty();
        assertThat(query(ownView(CONTRIBUTION), correspondent(other), List.of())).isEmpty();
        get("/api/datasets/" + own(STORY) + "/entities/" + story, correspondent(other)).expectStatus().isNotFound();
        commit(own(STORY), correspondent(other), update(story, 1, Map.of("title", en("Taken over"))))
            .expectStatus().is4xxClientError();
        get("/api/datasets/" + dataset(STORY) + "/entities/" + story, correspondent(other))
            .expectStatus().isForbidden();
        run(SUBMIT, correspondent(other), Map.of("storyId", story)).expectStatus().isNotFound();
        assertThat(attributes(dataset(STORY), story)).containsEntry("title", en("What does home mean to us?"))
            .containsEntry("status", DRAFT);
    }

    @Test
    void newRowsOfACorrespondentAreFilledByTheirScope() {
        String account = unique("cu-se");
        String[] mine = draftOf(account);
        assertThat(attributes(dataset(STORY), mine[0])).containsEntry("ownerActorId", account)
            .containsEntry("status", DRAFT);
        assertThat(attributes(dataset(CONTRIBUTION), mine[1])).containsEntry("ownerActorId", account)
            .containsEntry("editable", true).containsEntry("visibility", PRIVATE);
    }

    @Test
    void workflowFieldsCannotBeWrittenThroughDatasets() {
        String account = unique("cu-sg");
        String[] mine = draftOf(account);
        Map<String, Object> sneaky = new HashMap<>(storyAttributes(correspondent(account)));
        sneaky.put("status", PUBLISHED);
        assertThat(codes(commit(own(STORY), correspondent(account), insert(sneaky)).expectStatus().isBadRequest()
            .expectBody(MAP).returnResult().getResponseBody())).containsExactly("status:PROCESS_ONLY_FIELD");
        assertThat(codes(commit(dataset(CONTRIBUTION), curator(), update(mine[1], 1,
            Map.of("visibility", PUBLIC))).expectStatus().isBadRequest().expectBody(MAP).returnResult()
            .getResponseBody())).containsExactly("visibility:PROCESS_ONLY_FIELD");
    }

    @Test
    void aSubmittedStoryCannotBeChangedUntilItIsReturned() {
        String account = unique("cu-uk");
        String[] mine = draftOf(account);
        String story = mine[0];
        String contribution = mine[1];

        assertThat(ok(SUBMIT, correspondent(account), Map.of("storyId", story))).containsEntry("status", IN_REVIEW);
        assertThat(attributes(dataset(CONTRIBUTION), contribution)).containsEntry("editable", false);

        // Out of the correspondent's writable datasets now, still visible in their read-only ones.
        commit(own(STORY), correspondent(account), update(story, version(dataset(STORY), story),
            Map.of("title", en("Changed after submitting")))).expectStatus().is4xxClientError();
        commit(own(CONTRIBUTION), correspondent(account), update(contribution,
            version(dataset(CONTRIBUTION), contribution), Map.of("text", en("Changed")))).expectStatus()
            .is4xxClientError();
        assertThat(query(own(STORY), correspondent(account), List.of())).isEmpty();
        assertThat(query(ownView(STORY), correspondent(account), List.of())).hasSize(1);

        // Returned with a note: editable again.
        ok(RETURN, curator(), Map.of("storyId", story, "note", "Please add what surprised you."));
        assertThat(attributes(dataset(STORY), story)).containsEntry("status", DRAFT)
            .containsEntry("reviewNote", "Please add what surprised you.");
        commit(own(STORY), correspondent(account), update(story, version(dataset(STORY), story),
            Map.of("reflectionSurprised", en("How quiet it was.")))).expectStatus().isOk();
        commit(own(CONTRIBUTION), correspondent(account), update(contribution,
            version(dataset(CONTRIBUTION), contribution), Map.of("text", en("Home is quiet.")))).expectStatus().isOk();
    }

    @Test
    void theNoteOfAReturnStaysOutOfTheOperationRecord() {
        String account = unique("cu-sz");
        String story = draftOf(account)[0];
        ok(SUBMIT, correspondent(account), Map.of("storyId", story));
        String note = "Your grandmother's full name should not be in the text.";
        ok(RETURN, curator(), Map.of("storyId", story, "note", note));
        List<Map<String, Object>> records = query(
            "SELECT input_summary::text AS summary FROM op_process WHERE process_name = ?", RETURN);
        assertThat(records).isNotEmpty().allSatisfy(row -> assertThat(String.valueOf(row.get("summary")))
            .doesNotContain("grandmother").contains("***"));
    }

    @Test
    void publishingChecksTheStoryAndMakesAllItsPartsPublic() {
        String account = unique("cu-jp");
        String[] mine = draftOf(account);
        String story = mine[0];
        ok(SUBMIT, correspondent(account), Map.of("storyId", story));

        // Without its theme the check fails; nothing changes.
        String link = (String) query(dataset(STORY_THEME), curator(), eq("storyId", story)).getFirst().get("id");
        commit(dataset(STORY_THEME), curator(), Map.of("action", "DELETE", "id", link, "version", 1))
            .expectStatus().isOk();
        assertThat(refused(PUBLISH, curator(), Map.of("storyId", story), 422)).containsExactly(
            "Story.themes:THEME_REQUIRED");
        assertThat(attributes(dataset(STORY), story)).containsEntry("status", IN_REVIEW);

        String theme = link(story, theme("food"));
        assertThat(ok(PUBLISH, curator(), Map.of("storyId", story))).containsEntry("status", PUBLISHED);
        assertThat(attributes(dataset(STORY), story)).containsEntry("status", PUBLISHED)
            .containsEntry("publishedTime", START.toString());
        assertThat(attributes(dataset(CONTRIBUTION), mine[1])).containsEntry("visibility", PUBLIC)
            .containsEntry("editable", false);
        assertThat(attributes(dataset(STORY_THEME), theme)).containsEntry("visibility", PUBLIC);

        // Publishing again publishes what was added since.
        Map<String, Object> photo = new HashMap<>(Map.of("storyId", story, "kind", PHOTO,
            "imageFileId", photo(curator()), "alt", en("The street where I live")));
        String media = create(dataset(MEDIA_ITEM), curator(), photo);
        assertThat(attributes(dataset(MEDIA_ITEM), media)).containsEntry("visibility", PRIVATE);
        ok(PUBLISH, curator(), Map.of("storyId", story));
        assertThat(attributes(dataset(MEDIA_ITEM), media)).containsEntry("visibility", PUBLIC);

        // Unpublished: everything private again; reopened: a draft its correspondent can edit.
        ok(UNPUBLISH, curator(), Map.of("storyId", story));
        assertThat(attributes(dataset(STORY), story)).containsEntry("status", UNPUBLISHED);
        assertThat(attributes(dataset(CONTRIBUTION), mine[1])).containsEntry("visibility", PRIVATE);
        assertThat(attributes(dataset(MEDIA_ITEM), media)).containsEntry("visibility", PRIVATE);
        assertThat(attributes(dataset(STORY_THEME), theme)).containsEntry("visibility", PRIVATE);
        ok(REOPEN, curator(), Map.of("storyId", story));
        assertThat(attributes(dataset(STORY), story)).containsEntry("status", DRAFT);
        assertThat(attributes(dataset(CONTRIBUTION), mine[1])).containsEntry("editable", true);
        // The first publication time is kept.
        assertThat(attributes(dataset(STORY), story)).containsEntry("publishedTime", START.toString());
    }

    @Test
    void actionsOnTheWrongStateAreRefused() {
        String account = unique("cu-pl");
        String story = draftOf(account)[0];
        assertThat(refused(RETURN, curator(), Map.of("storyId", story), 422)).containsExactly("status:WRONG_STATE");
        assertThat(refused(UNPUBLISH, curator(), Map.of("storyId", story), 422)).containsExactly("status:WRONG_STATE");
        assertThat(refused(REOPEN, curator(), Map.of("storyId", story), 422)).containsExactly("status:WRONG_STATE");
        // A correspondent has no editor's actions.
        run(PUBLISH, correspondent(account), Map.of("storyId", story)).expectStatus().isForbidden();
    }

    @Test
    void withoutReviewASubmissionIsPublishedAtOnceButStillChecked() {
        setSwitch(REVIEW_REQUIRED, false);
        try {
            String account = unique("cu-se");
            String[] mine = draftOf(account);
            assertThat(ok(SUBMIT, correspondent(account), Map.of("storyId", mine[0])))
                .containsEntry("status", PUBLISHED);
            assertThat(attributes(dataset(CONTRIBUTION), mine[1])).containsEntry("visibility", PUBLIC);

            // An incomplete story is refused as a whole: still a draft, still editable.
            String other = unique("cu-sg");
            String[] incomplete = draftOf(other);
            commit(own(STORY), correspondent(other), update(incomplete[0], version(dataset(STORY), incomplete[0]),
                Map.of("thumbnailAlt", Map.of("ja", "テーブル")))).expectStatus().isOk();
            assertThat(refused(SUBMIT, correspondent(other), Map.of("storyId", incomplete[0]), 422))
                .containsExactly("Story.thumbnailAlt:ENGLISH_REQUIRED");
            assertThat(attributes(dataset(STORY), incomplete[0])).containsEntry("status", DRAFT);
            assertThat(attributes(dataset(CONTRIBUTION), incomplete[1])).containsEntry("editable", true);
        } finally {
            setSwitch(REVIEW_REQUIRED, true);
        }
    }

    @Test
    void withReviewASubmissionWaitsForAnEditor() {
        String account = unique("cu-uk");
        String[] mine = draftOf(account);
        assertThat(ok(SUBMIT, correspondent(account), Map.of("storyId", mine[0]))).containsEntry("status", IN_REVIEW);
        assertThat(attributes(dataset(CONTRIBUTION), mine[1])).containsEntry("visibility", PRIVATE);
    }

    @Test
    void theGuardianSwitchDecidesWhetherMinorsNeedAGuardiansConsent() {
        String place = location("Eswatini");
        String minor = participant(place, null, false);
        consent(minor, PARTY_PARTICIPANT, true, true, true);

        assertThat(refused("CULTURE_PARTICIPANT_ACTIVATE", curator(), Map.of("participantId", minor), 422))
            .containsExactly("Participant.status:CONSENT_MISSING");

        setSwitch(GUARDIAN_REQUIRED, false);
        try {
            ok("CULTURE_PARTICIPANT_ACTIVATE", curator(), Map.of("participantId", minor));
            // The story check follows the same switch.
            String story = create(dataset(STORY), curator(), storyAttributes(curator()));
            link(story, theme("family"));
            create(dataset(CONTRIBUTION), curator(), contribution(story, minor));
            ok(PUBLISH, curator(), Map.of("storyId", story));

            setSwitch(GUARDIAN_REQUIRED, true);
            assertThat(refused(PUBLISH, curator(), Map.of("storyId", story), 422))
                .containsExactly("Contribution.participantId:CONSENT_MISSING");
            consent(minor, PARTY_GUARDIAN, true, true, true);
            ok(PUBLISH, curator(), Map.of("storyId", story));
        } finally {
            setSwitch(GUARDIAN_REQUIRED, true);
        }
    }
}
