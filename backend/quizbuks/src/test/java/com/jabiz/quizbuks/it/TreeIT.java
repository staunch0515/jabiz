package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.page.QbSponsorPages;
import com.jabiz.quizbuks.page.SponsorPageProcesses;
import com.jabiz.quizbuks.play.PrerequisiteCheck;
import com.jabiz.quizbuks.publishing.QbPrerequisites;
import com.jabiz.quizbuks.tree.TreeStates;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tree's pictures (docs/quizbuks/plans/Q85-tech-tree.md): the sponsor's nodes with their counts and the people
 * on each, one customer's tree by their key, the taker's own tree on the sponsor's page; the templates agreeing with
 * {@link TreeStates}; nobody seeing another sponsor's tree.
 */
class TreeIT extends PlayItSupport {

    private static final List<String> COUNTS = List.of("lit", "answered", "inProgress", "unlocked", "locked");

    /** A sponsor's tree: basics → advanced (a pass needed), basics → survey (answered is enough), later alone. */
    private record Tree(String sponsor, String basics, String advanced, String survey, String later) {}

    private Tree tree(String name) {
        String sponsor = newSponsor(unique(name));
        credit(sponsor, 100_000);
        Map<String, Object> global = Map.of("regions", List.of("GLOBAL"));
        String basics = livePublication(sponsor, name + " basics", global);
        String advanced = liveRequiring(sponsor, name + " advanced", global,
            List.of(prerequisite(basics, QbPrerequisites.PASSED)));
        String survey = liveRequiring(sponsor, name + " survey", global,
            List.of(prerequisite(basics, QbPrerequisites.ANSWERED)));
        // Not open yet: scheduled a day ahead, requiring nothing.
        String later = livePublication(sponsor, name + " later", Map.of("regions", List.of("GLOBAL"),
            "startAt", clock.instant().plus(Duration.ofDays(1)).toString(),
            "endAt", clock.instant().plus(Duration.ofDays(8)).toString()));
        return new Tree(sponsor, basics, advanced, survey, later);
    }

    private Map<String, Object> at() {
        return Map.of("at", clock.instant().toString());
    }

    private Map<String, Map<String, Object>> nodes(String token) {
        return items("qb.sponsor.tree", token, Map.of("params", at())).stream()
            .collect(Collectors.toMap(row -> (String) row.get("publicationId"), Function.identity()));
    }

    private static String counts(Map<String, Object> node) {
        return COUNTS.stream().map(c -> c + "=" + ((Number) node.get(c)).intValue()).collect(Collectors.joining(" "));
    }

    private List<Map<String, Object>> people(String token, String publicationId, String state) {
        Map<String, Object> params = new java.util.HashMap<>(at());
        params.put("publicationId", publicationId);
        params.put("state", state);
        return items("qb.sponsor.tree-people", token, Map.of("params", params));
    }

    private Map<String, String> person(String token, String userKey) {
        Map<String, Object> params = new java.util.HashMap<>(at());
        params.put("userKey", userKey);
        return items("qb.sponsor.tree-person", token, Map.of("params", params)).stream()
            .collect(Collectors.toMap(row -> (String) row.get("publicationId"), row -> (String) row.get("state")));
    }

    private static String userKey(String sponsor, String userId) {
        return org.springframework.util.DigestUtils.md5DigestAsHex((sponsor + ":" + userId)
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void theSponsorSeesEveryNodeWithItsCountsAndThePeopleOnIt() {
        Tree t = tree("s-tree");
        String token = sponsorToken(t.sponsor());
        String passed = newTaker("t-tree-pass-" + UUID.randomUUID(), "JP");
        String failed = newTaker("t-tree-fail-" + UUID.randomUUID(), "JP");
        String busy = newTaker("t-tree-busy-" + UUID.randomUUID(), "JP");
        play(passed, t.basics(), true, true);
        play(failed, t.basics(), false, false);
        start(taker(busy), t.basics());

        Map<String, Map<String, Object>> nodes = nodes(token);
        assertThat(nodes).containsOnlyKeys(t.basics(), t.advanced(), t.survey(), t.later());
        // The customers are the two who finished: the one under way is counted on the node only.
        assertThat(counts(nodes.get(t.basics()))).isEqualTo("lit=1 answered=1 inProgress=1 unlocked=0 locked=0");
        assertThat(counts(nodes.get(t.advanced()))).isEqualTo("lit=0 answered=0 inProgress=0 unlocked=1 locked=1");
        assertThat(counts(nodes.get(t.survey()))).isEqualTo("lit=0 answered=0 inProgress=0 unlocked=2 locked=0");
        // Not open yet: nobody can start it, nobody is locked out (closed, not counted).
        assertThat(counts(nodes.get(t.later()))).isEqualTo("lit=0 answered=0 inProgress=0 unlocked=0 locked=0");
        assertThat(nodes.get(t.later())).containsEntry("live", false).containsEntry("isolated", true);
        assertThat(nodes.get(t.basics())).containsEntry("live", true).containsEntry("isolated", false);
        assertThat((String) nodes.get(t.advanced()).get("prerequisites"))
            .contains("\"requiredPublicationId\": \"" + t.basics() + "\"").contains("\"requirement\": \"PASSED\"");
        assertThat(nodes.get(t.basics())).containsEntry("prerequisites", "[]");

        // The people on a node, by name and key, with no more of them (D-Q85-5).
        List<Map<String, Object>> unlocked = people(token, t.advanced(), "UNLOCKED");
        assertThat(unlocked).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("userKey", userKey(t.sponsor(), passed))
                .containsEntry("state", "UNLOCKED").containsEntry("hasConversation", false)
                .containsKey("userName").doesNotContainKeys("userId", "score", "email");
            assertThat(row.get("finishedAt")).isNull();
        });
        assertThat(people(token, t.advanced(), "LOCKED")).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("userKey", userKey(t.sponsor(), failed)));
        assertThat(people(token, t.basics(), "LIT")).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("userKey", userKey(t.sponsor(), passed));
            assertThat(row.get("finishedAt")).isNotNull();
        });
        assertThat(people(token, t.basics(), "ANSWERED")).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("userKey", userKey(t.sponsor(), failed)));
        assertThat(people(token, t.basics(), "IN_PROGRESS")).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("userKey", userKey(t.sponsor(), busy)));
        assertThat(people(token, t.survey(), "UNLOCKED")).hasSize(2);
        assertThat(people(token, t.later(), "UNLOCKED")).isEmpty();
        // The key is the transactions page's: the same person filtered there.
        Map<String, Object> params = new java.util.HashMap<>(Map.of("userKey", userKey(t.sponsor(), passed),
            "publicationId", t.basics()));
        assertThat(items("qb.sponsor.transactions", token, Map.of("params", params))).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("userKey", userKey(t.sponsor(), passed)));

        // One customer's whole tree by their key; a key that is nobody's finds nothing.
        assertThat(person(token, userKey(t.sponsor(), passed))).containsExactlyInAnyOrderEntriesOf(Map.of(
            t.basics(), "LIT", t.advanced(), "UNLOCKED", t.survey(), "UNLOCKED", t.later(), "CLOSED"));
        assertThat(person(token, userKey(t.sponsor(), failed))).containsExactlyInAnyOrderEntriesOf(Map.of(
            t.basics(), "ANSWERED", t.advanced(), "LOCKED", t.survey(), "UNLOCKED", t.later(), "CLOSED"));
        assertThat(person(token, userKey(t.sponsor(), busy))).isEmpty();
        assertThat(person(token, "0123456789abcdef0123456789abcdef")).isEmpty();

        // Another sponsor sees none of it: not the nodes, not the people, not the person.
        String other = newSponsor(unique("s-tree-other"));
        String othersToken = sponsorToken(other);
        assertThat(nodes(othersToken)).doesNotContainKeys(t.basics(), t.advanced(), t.survey(), t.later());
        assertThat(people(othersToken, t.advanced(), "UNLOCKED")).isEmpty();
        assertThat(person(othersToken, userKey(t.sponsor(), passed))).isEmpty();
        assertThat(person(othersToken, userKey(other, passed))).isEmpty();
    }

    @Test
    void theTakerSeesTheSponsorsTreeAsTheyStandOnItOncePublished() {
        Tree t = tree("s-tree-app");
        String token = sponsorToken(t.sponsor());
        String handle = "q85-" + UUID.randomUUID().toString().substring(0, 8);
        run(SponsorPageProcesses.SAVE, token, body("handle", handle, "language", "en", "headline", "Our quizzes"));
        Map<String, Object> submitted = run(SponsorPageProcesses.SUBMIT, token, body());
        run("APPROVAL_DECIDE", sponsorReviewer(), body("requestId", submitted.get("approvalRequestId"),
            "decision", "APPROVE"));
        deliverEvents();
        assertThat(query("SELECT status FROM qb_sponsor_page WHERE user_id = ?", t.sponsor()).getFirst())
            .containsEntry("status", QbSponsorPages.PUBLISHED);

        String passed = newTaker("t-tree-app-pass-" + UUID.randomUUID(), "JP");
        String failed = newTaker("t-tree-app-fail-" + UUID.randomUUID(), "JP");
        play(passed, t.basics(), true, true);
        play(failed, t.basics(), false, false);

        // Open nodes only, each as the caller stands on it; the one not open yet is not on the taker's tree.
        Map<String, Map<String, Object>> mine = appTree(taker(passed), handle);
        assertThat(mine).containsOnlyKeys(t.basics(), t.advanced(), t.survey());
        assertThat(states(mine)).containsExactlyInAnyOrderEntriesOf(Map.of(t.basics(), "LIT", t.advanced(), "UNLOCKED",
            t.survey(), "UNLOCKED"));
        assertThat(states(appTree(taker(failed), handle))).containsExactlyInAnyOrderEntriesOf(Map.of(
            t.basics(), "ANSWERED", t.advanced(), "LOCKED", t.survey(), "UNLOCKED"));
        assertThat((String) mine.get(t.advanced()).get("prerequisites"))
            .contains("\"requiredPublicationId\": \"" + t.basics() + "\"").contains("\"name\": \"");
        assertThat(mine.get(t.basics())).containsEntry("live", true).containsKey("maxReward").containsKey("endAt");
        // Somebody new sees every open node free or locked.
        String fresh = newTaker("t-tree-app-new-" + UUID.randomUUID(), "JP");
        assertThat(states(appTree(taker(fresh), handle))).containsExactlyInAnyOrderEntriesOf(Map.of(
            t.basics(), "UNLOCKED", t.advanced(), "LOCKED", t.survey(), "LOCKED"));
        // A page nobody published, or that is not there, draws nothing; nobody signed in reads nothing.
        assertThat(appTree(taker(passed), "q85-nobody")).isEmpty();
        client.post().uri("/api/queries/qb.app.sponsor-tree").contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("params", Map.of("handle", handle, "at", clock.instant().toString())))
            .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void theTemplatesAgreeWithTreeStates() {
        Tree t = tree("s-tree-rule");
        String token = sponsorToken(t.sponsor());
        List<String> takers = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            takers.add(newTaker("t-tree-rule-" + i + "-" + UUID.randomUUID(), "JP"));
        }
        play(takers.get(0), t.basics(), true, true);
        play(takers.get(0), t.advanced(), false, true);
        play(takers.get(1), t.basics(), false, false);
        play(takers.get(1), t.survey(), true, true);
        play(takers.get(2), t.basics(), true, true);
        start(taker(takers.get(2)), t.advanced());
        start(taker(takers.get(3)), t.basics());

        Map<String, Map<String, Object>> nodes = nodes(token);
        Map<String, List<PrerequisiteCheck.Prerequisite>> edges = new java.util.HashMap<>();
        Map<String, Boolean> live = new java.util.HashMap<>();
        for (Map<String, Object> node : nodes.values()) {
            String id = (String) node.get("publicationId");
            live.put(id, Boolean.TRUE.equals(node.get("live")));
            edges.put(id, query("SELECT required_publication_id, requirement FROM qb_publication_prerequisite"
                + " WHERE publication_id = ?::uuid", id).stream().map(row -> new PrerequisiteCheck.Prerequisite(
                    UUID.fromString(String.valueOf(row.get("required_publication_id"))),
                    (String) row.get("requirement"))).toList());
        }
        Map<String, Map<String, Integer>> expected = new java.util.HashMap<>();
        for (String userId : takers) {
            List<PrerequisiteCheck.Attempt> attempts = query("SELECT publication_id, status, passed FROM qb_attempt"
                + " WHERE user_id = ?", userId).stream().map(row -> new PrerequisiteCheck.Attempt(
                    UUID.fromString(String.valueOf(row.get("publication_id"))), (String) row.get("status"),
                    (Boolean) row.get("passed"))).toList();
            boolean customer = attempts.stream().anyMatch(a -> !"IN_PROGRESS".equals(a.status()));
            Map<String, String> byTemplate = person(token, userKey(t.sponsor(), userId));
            for (String id : nodes.keySet()) {
                TreeStates.State state = TreeStates.of(UUID.fromString(id), live.get(id), edges.get(id), attempts);
                boolean onNode = attempts.stream().anyMatch(a -> id.equals(a.publicationId().toString()));
                // The templates count a person on a node when they have an attempt on it, or are a customer.
                if (onNode || customer) {
                    expected.computeIfAbsent(id, k -> new java.util.HashMap<>()).merge(state.name(), 1, Integer::sum);
                }
                if (customer) {
                    assertThat(byTemplate).as("person %s on %s", userId, id).containsEntry(id, state.name());
                }
                if (onNode || customer) {
                    assertThat(people(token, id, state.name())).as("people %s on %s", state, id)
                        .anySatisfy(row -> assertThat(row).containsEntry("userKey", userKey(t.sponsor(), userId)));
                }
            }
        }
        for (String id : nodes.keySet()) {
            Map<String, Integer> counts = expected.getOrDefault(id, Map.of());
            assertThat(counts(nodes.get(id))).as("counts of %s", id).isEqualTo(
                "lit=" + counts.getOrDefault("LIT", 0) + " answered=" + counts.getOrDefault("ANSWERED", 0)
                + " inProgress=" + counts.getOrDefault("IN_PROGRESS", 0)
                + " unlocked=" + counts.getOrDefault("UNLOCKED", 0) + " locked=" + counts.getOrDefault("LOCKED", 0));
        }
    }

    private Map<String, Map<String, Object>> appTree(String token, String handle) {
        Map<String, Object> params = new java.util.HashMap<>(at());
        params.put("handle", handle);
        params.put("lang", "en");
        return items("qb.app.sponsor-tree", token, Map.of("params", params)).stream()
            .collect(Collectors.toMap(row -> (String) row.get("publicationId"), Function.identity()));
    }

    private static Map<String, String> states(Map<String, Map<String, Object>> nodes) {
        return nodes.values().stream().collect(Collectors.toMap(row -> (String) row.get("publicationId"),
            row -> (String) row.get("state")));
    }
}
