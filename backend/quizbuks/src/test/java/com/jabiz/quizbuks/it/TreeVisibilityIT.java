package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.tree.TreeRevocations;
import com.jabiz.runtime.test.TestTokens;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A node's three states to a person and what they see (F-S2d, docs/quizbuks/first-launch-spec.md F-S2): a publication
 * bound to a node whose prerequisites (the nodes to be lit before answering) the person has not all lit is as if it
 * did not exist on every page and process: the home, its page and tiers, the view and the start (404), the public
 * pages, and the inbox's notices about it. It appears once they are lit, its page says the node is open and then lit;
 * an attempt keeps its own result visible; a revocation does not reach the nodes lit after it.
 */
class TreeVisibilityIT extends TreeItSupport {

    private Map<String, Object> detail(String token, String publicationId) {
        List<Map<String, Object>> rows = items("qb.app.publication", token, Map.of("params", Map.of(
            "publicationId", publicationId, "at", clock.instant().toString(), "country", "JP")));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private List<Map<String, Object>> tiers(String token, String publicationId) {
        return items("qb.app.publication-tiers", token, Map.of("params", Map.of("publicationId", publicationId,
            "at", clock.instant().toString(), "country", "JP")));
    }

    private List<String> feedIds(String token) {
        return feed(token, "JP", Map.of()).stream().map(row -> String.valueOf(row.get("publicationId"))).toList();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> shared(String publicationId) {
        Map<String, Object> page = client.get().uri(b -> b.path("/api/public/queries/qb.public.publication")
                .queryParam("p.publicationId", "{id}").build(publicationId))
            .exchange().expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        return (List<Map<String, Object>>) page.get("items");
    }

    private List<Object> inboxItems(String token) {
        return items("qb.app.inbox", token, Map.of("params", Map.of(), "limit", 500)).stream()
            .map(item -> item.get("itemId")).toList();
    }

    private long unreadBroadcasts(String token) {
        return ((Number) items("qb.app.inbox-unread", token, Map.of("params", Map.of())).getFirst()
            .get("broadcasts")).longValue();
    }

    /** The Sponsor's notice about the publication, to Japan, sent now; its id. */
    private String notice(String sponsor, String publicationId) {
        Map<String, Object> saved = run("QB_BROADCAST_SAVE", sponsorToken(sponsor), body("title", body("en",
            "Come and play"), "body", body("en", "A new quiz"), "publicationId", publicationId, "regions",
            List.of("JP")));
        assertThat((List<?>) saved.get("problems")).as("problems").isEmpty();
        run("QB_BROADCAST_SEND", sponsorToken(sponsor), body("broadcastId", saved.get("broadcastId"), "baseRevision",
            saved.get("revision")));
        return (String) saved.get("broadcastId");
    }

    private String revoker() {
        return TestTokens.bearer(tokens, verifiedUser(unique("fs2d-revoker")), QbPermissions.TREE_LIT_REVOKE,
            QbPermissions.TREES_READ, "approval.decide");
    }

    private void revoke(String userId, String nodeId) {
        Object litId = query("SELECT lit_id FROM qb_tree_lit_version WHERE subject_id = ? AND node_id = ?", userId,
            nodeId).getFirst().get("lit_id");
        Map<String, Object> asked = run(TreeRevocations.REVOKE, revoker(), body("litId", litId, "reason",
            "Answers shared"));
        decide(revoker(), asked.get("approvalRequestId"), "APPROVE", null);
    }

    private List<Object> litNodes(String userId) {
        return query("SELECT node_id FROM qb_tree_lit_version WHERE subject_kind = 'USER' AND subject_id = ? ORDER BY "
            + "row_id", userId).stream().map(row -> row.get("node_id")).toList();
    }

    @Test
    void aPublicationIsInvisibleUntilItsNodesPrerequisitesAreLitThenOpenThenLit() {
        String sponsor = newSponsor(unique("s-fs2d-see"));
        credit(sponsor, 10_000);
        Map3 map = map(sponsor);
        String basics = liveBound(sponsor, "See basics", map.treeId(), map.basics());
        String advanced = liveBound(sponsor, "See advanced", map.treeId(), map.advanced());
        String noticeId = notice(sponsor, advanced);
        String userId = newTaker(unique("t-fs2d-see"), "JP");
        String mine = taker(userId);

        // 1. Unmet: nowhere, and a view or a start is refused as for a publication that does not exist.
        assertThat(feedIds(mine)).contains(basics).doesNotContain(advanced);
        assertThat(detail(mine, advanced)).isNull();
        assertThat(tiers(mine, advanced)).isEmpty();
        refused("QB_VIEW", mine, body("publicationId", advanced), 404);
        refused("QB_ATTEMPT_START", mine, body("publicationId", advanced), 404);
        assertThat(inboxItems(mine)).doesNotContain(noticeId);
        long unreadBefore = unreadBroadcasts(mine);
        // The public share page (nobody signed in): a node with prerequisites is never shown; one without is.
        assertThat(shared(advanced)).isEmpty();
        assertThat(shared(basics)).extracting(row -> row.get("publicationId")).containsExactly(basics);
        // The node without prerequisites is open to be lit.
        assertThat(detail(mine, basics)).containsEntry("nodeLit", false)
            .containsEntry("nodeName", Map.of("en", "Basics"));

        // 2. The prerequisite lit: the publication appears everywhere, its node open (not lit).
        assertThat(play(userId, basics, true, true)).containsEntry("passed", true);
        deliverEvents();
        assertThat(feedIds(mine)).contains(advanced);
        assertThat(detail(mine, advanced)).containsEntry("nodeLit", false).containsEntry("canStart", true)
            .containsEntry("nodeName", Map.of("en", "Advanced"));
        assertThat(tiers(mine, advanced)).isNotEmpty();
        assertThat(inboxItems(mine)).contains(noticeId);
        // A sponsor's notice is the inbox's promotion about a publication (category PROMO): the case the condition
        // covers; platform announcements carry no publication, so there is no system one to keep.
        assertThat(items("qb.app.inbox", mine, Map.of("params", Map.of(), "limit", 500)))
            .filteredOn(item -> noticeId.equals(item.get("itemId"))).singleElement()
            .satisfies(item -> assertThat(item).containsEntry("category", "PROMO")
                .containsEntry("publicationId", advanced));
        assertThat(unreadBroadcasts(mine)).isEqualTo(unreadBefore + 1);
        run("QB_VIEW", mine, body("publicationId", advanced));
        assertThat(shared(advanced)).as("still nobody's: the public page knows no one").isEmpty();
        assertThat(detail(mine, basics)).containsEntry("nodeLit", true);

        // 3. Passed: the node is lit (and the expert node after it, whose condition both now hold).
        assertThat(play(userId, advanced, true, true)).containsEntry("passed", true);
        deliverEvents();
        assertThat(detail(mine, advanced)).containsEntry("nodeLit", true);
        assertThat(litNodes(userId)).containsExactly(map.basics(), map.advanced(), map.expert());
    }

    @Test
    void aRevocationHidesWhatItsNodeOpenedButNeitherReachesTheNodesLitAfterItNorTheResultsAlreadyHad() {
        String sponsor = newSponsor(unique("s-fs2d-rev"));
        credit(sponsor, 10_000);
        Map3 map = map(sponsor);
        String basics = liveBound(sponsor, "Rev basics", map.treeId(), map.basics());
        String advanced = liveBound(sponsor, "Rev advanced", map.treeId(), map.advanced());
        String second = liveBound(sponsor, "Rev advanced two", map.treeId(), map.advanced());
        String userId = newTaker(unique("t-fs2d-rev"), "JP");
        String mine = taker(userId);
        play(userId, basics, true, true);
        deliverEvents();
        play(userId, advanced, true, true);
        deliverEvents();
        assertThat(litNodes(userId)).containsExactly(map.basics(), map.advanced(), map.expert());
        assertThat(feedIds(mine)).contains(second);

        // The basics' lighting revoked (four eyes): no new record, the nodes lit after it stay lit (no cascade).
        revoke(userId, map.basics());
        assertThat(litNodes(userId)).containsExactly(map.basics(), map.advanced(), map.expert());
        assertThat(query("SELECT count(*) AS n FROM qb_tree_lit_revocation_version WHERE subject_id = ?", userId)
            .getFirst()).containsEntry("n", 1L);
        // The other publication of the advanced node is hidden again by its prerequisite; the one answered keeps its
        // page (the result is theirs), its node still lit.
        assertThat(feedIds(mine)).doesNotContain(second);
        assertThat(detail(mine, second)).isNull();
        refused("QB_ATTEMPT_START", mine, body("publicationId", second), 404);
        assertThat(detail(mine, advanced)).containsEntry("nodeLit", true).containsEntry("canStart", false);
        run("QB_VIEW", mine, body("publicationId", advanced));
    }
}
