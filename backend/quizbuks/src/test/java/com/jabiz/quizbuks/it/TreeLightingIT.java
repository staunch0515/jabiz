package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.play.PlayCodes;
import com.jabiz.quizbuks.publishing.PublishingCodes;
import com.jabiz.quizbuks.tree.TreeLights;
import com.jabiz.quizbuks.tree.TreeProcesses;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Publications bound to knowledge map nodes, and lighting (F-S2b1, docs/quizbuks/plans/impl/F-S2.md §1): the
 * binding's checks, the start and the three templates following the nodes to be lit before answering, a pass
 * lighting its node outside the answering and the nodes depending on it in turn, lighting being permanent, and the
 * templates agreeing with the Java rule ({@link TreeLights}).
 */
class TreeLightingIT extends TreeItSupport {

    private static List<Object> problemCodes(Map<String, Object> saved) {
        return ((List<?>) saved.get("problems")).stream().map(p -> (Object) ((Map<?, ?>) p).get("code")).toList();
    }

    private List<Map<String, Object>> lits(String userId) {
        return query("SELECT tree_id, node_id, basis FROM qb_tree_lit_version WHERE subject_kind = 'USER' AND "
            + "subject_id = ? ORDER BY row_id", userId);
    }

    private Map<String, Object> detail(String token, String publicationId) {
        List<Map<String, Object>> rows = items("qb.app.publication", token, Map.of("params", Map.of(
            "publicationId", publicationId, "at", clock.instant().toString(), "country", "JP")));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private List<String> feedIds(String token) {
        return feed(token, "JP", Map.of()).stream().map(row -> String.valueOf(row.get("publicationId"))).toList();
    }

    @Test
    void aPublicationIsBoundToANodeOfTheSponsorsOwnMapThatAsksForAPass() {
        String sponsor = newSponsor(unique("s-fs2b-bind"));
        credit(sponsor, 10_000);
        String token = sponsorToken(sponsor);
        Map3 map = map(sponsor);
        String versionId = version(token, "Bind quiz");
        // A node asking for no pass lights by itself: nothing can be bound to it.
        Map<String, Object> saved = run("QB_PUBLICATION_SAVE", token, bound(versionId, "Bind", map.treeId(),
            map.expert()));
        assertThat(problemCodes(saved)).contains(PublishingCodes.NODE_NOT_BINDABLE);
        assertThat(codes(refused("QB_PUBLICATION_SUBMIT", token, body("publicationId", saved.get("publicationId")),
            422))).contains(PublishingCodes.NODE_NOT_BINDABLE);
        // A node nobody has: unknown; another Sponsor's node: unknown to them.
        assertThat(problemCodes(run("QB_PUBLICATION_SAVE", token, bound(versionId, "Bind", map.treeId(),
            UUID.randomUUID().toString())))).contains(PublishingCodes.NODE_UNKNOWN);
        String other = newSponsor(unique("s-fs2b-other"));
        String otherToken = sponsorToken(other);
        assertThat(problemCodes(run("QB_PUBLICATION_SAVE", otherToken, bound(version(otherToken, "Other quiz"),
            "Other", map.treeId(), map.basics())))).contains(PublishingCodes.NODE_UNKNOWN);
        // Bound to the basics: no problem, and it may pay nothing (the requester, 2026-11-08: §0 item 4).
        Map<String, Object> free = bound(versionId, "Bind free", map.treeId(), map.basics());
        free.put("tiers", List.of(body("fromScore", 0, "toScore", 5, "reward", 0)));
        Map<String, Object> ok = run("QB_PUBLICATION_SAVE", token, free);
        assertThat(problemCodes(ok)).doesNotContain(PublishingCodes.NODE_UNKNOWN, PublishingCodes.NODE_NOT_BINDABLE,
            PublishingCodes.TIER_NO_REWARD);
        // The same without a node must pay something.
        Map<String, Object> unbound = new LinkedHashMap<>(free);
        unbound.remove("treeId");
        unbound.remove("treeNodeId");
        assertThat(problemCodes(run("QB_PUBLICATION_SAVE", token, unbound))).contains(PublishingCodes.TIER_NO_REWARD);
        // A copy keeps the node; the card freezes it.
        String copy = (String) run("QB_PUBLICATION_COPY", token, body("publicationId", ok.get("publicationId")))
            .get("publicationId");
        assertThat(query("SELECT tree_id, tree_node_id FROM qb_publication_version WHERE publication_id = ?::uuid "
            + "ORDER BY version_no DESC LIMIT 1", copy).getFirst()).containsEntry("tree_node_id", map.basics());
        String live = liveBound(sponsor, "Bind live", map.treeId(), map.basics());
        assertThat(query("SELECT tree_id, tree_node_id FROM qb_publication_card WHERE publication_id = ?::uuid", live)
            .getFirst()).containsEntry("tree_id", map.treeId()).containsEntry("tree_node_id", map.basics());
    }

    @Test
    void aPassLightsItsNodeAndTheNodesDependingOnItOpenTheirPublications() {
        String sponsor = newSponsor(unique("s-fs2b-light"));
        credit(sponsor, 10_000);
        Map3 map = map(sponsor);
        String basics = liveBound(sponsor, "Light basics", map.treeId(), map.basics());
        String advanced = liveBound(sponsor, "Light advanced", map.treeId(), map.advanced());
        String plain = livePublication(sponsor, "Light plain");
        String userId = newTaker(unique("t-fs2b-light"), "JP");
        String mine = taker(userId);

        // The advanced node wants the basics lit before answering: not in the feed, not startable, refused.
        assertThat(feedIds(mine)).contains(basics, plain).doesNotContain(advanced);
        assertThat(detail(mine, advanced)).containsEntry("nodeUnlocked", false).containsEntry("canStart", false)
            .containsEntry("treeNodeId", map.advanced());
        Map<String, Object> refusal = refused("QB_ATTEMPT_START", mine, body("publicationId", advanced), 422);
        assertThat(codes(refusal)).containsExactly(PlayCodes.TREE_NOT_UNLOCKED);
        assertThat(violations(refusal).getFirst()).containsEntry("field", "publicationId");

        // A failed attempt lights nothing; an ordinary publication's pass neither.
        assertThat(play(userId, plain, true, true)).containsEntry("passed", true);
        deliverEvents();
        assertThat(lits(userId)).isEmpty();

        // Another taker fails the basics: nothing.
        String failer = newTaker(unique("t-fs2b-fail"), "JP");
        assertThat(play(failer, basics, true, false)).containsEntry("passed", false);
        deliverEvents();
        assertThat(lits(failer)).isEmpty();

        // The pass lights the basics once its event is delivered (outside the answering).
        assertThat(play(userId, basics, true, true)).containsEntry("passed", true);
        assertThat(lits(userId)).isEmpty();
        deliverEvents();
        assertThat(lits(userId)).extracting(row -> row.get("node_id")).containsExactly(map.basics());
        assertThat(String.valueOf(lits(userId).getFirst().get("basis"))).contains("attemptId");
        assertThat(feedIds(mine)).contains(advanced);
        assertThat(detail(mine, advanced)).containsEntry("nodeUnlocked", true).containsEntry("canStart", true);

        // Passing the advanced one lights it, and the expert node (both lit) follows in the next transaction.
        assertThat(play(userId, advanced, true, true)).containsEntry("passed", true);
        deliverEvents();
        assertThat(lits(userId)).extracting(row -> row.get("node_id"))
            .containsExactly(map.basics(), map.advanced(), map.expert());
        assertThat(String.valueOf(lits(userId).get(2).get("basis"))).contains(map.advanced());

        // Lighting is permanent and written once: evaluating again writes nothing more.
        runAsSystem("QB_TREE_EVALUATE", body("subjectKind", "USER", "subjectId", userId, "treeId", map.treeId(),
            "nodeIds", List.of(map.basics()), "dependants", false, "depth", 1));
        deliverEvents();
        assertThat(lits(userId)).hasSize(3);
        assertThat(query("SELECT count(*) AS n FROM qb_tree_lit_version WHERE subject_id = ? AND version_no > 1",
            userId).getFirst()).containsEntry("n", 0L);
    }

    @Test
    void aRetiredMapsNodesNoLongerOpenTheirPublications() {
        String sponsor = newSponsor(unique("s-fs2b-retire"));
        credit(sponsor, 10_000);
        Map3 map = map(sponsor);
        String basics = liveBound(sponsor, "Retire basics", map.treeId(), map.basics());
        Map<String, Object> tree = query("SELECT revision FROM qb_tree WHERE tree_id = ?", map.treeId()).getFirst();
        run(TreeProcesses.RETIRE, sponsorToken(sponsor), body("treeId", map.treeId(), "baseRevision",
            tree.get("revision")));
        // Its nodes are out of effect, and (F-S2b2) its running publications ended.
        assertThat(query("SELECT count(*) AS n FROM qb_tree_node WHERE tree_id = ? AND active", map.treeId())
            .getFirst()).containsEntry("n", 0L);
        String mine = taker(newTaker(unique("t-fs2b-retire"), "JP"));
        assertThat(feedIds(mine)).doesNotContain(basics);
        refused("QB_ATTEMPT_START", mine, body("publicationId", basics), 422);
    }

    @Test
    void theCountAndThePagesAgreeWithTheJavaRule() {
        String sponsor = newSponsor(unique("s-fs2b-count"));
        credit(sponsor, 10_000);
        String token = sponsorToken(sponsor);
        Map3 map = map(sponsor);
        String basics = liveBound(sponsor, "Count basics", map.treeId(), map.basics());
        String advanced = liveBound(sponsor, "Count advanced", map.treeId(), map.advanced());
        // Takers of every kind: nothing done, failed, passed (lit), in progress.
        List<String> takers = List.of(verifiedTaker(unique("t-fs2b-none")), verifiedTaker(unique("t-fs2b-failed")),
            verifiedTaker(unique("t-fs2b-passed")), verifiedTaker(unique("t-fs2b-open")));
        play(takers.get(1), basics, true, false);
        play(takers.get(2), basics, true, true);
        start(taker(takers.get(3)), basics);
        deliverEvents();
        for (String userId : takers) {
            boolean unlocked = javaUnlocked(userId, map.treeId(), map.advanced());
            assertThat(userId.equals(takers.get(2))).as("lit " + userId).isEqualTo(unlocked);
            assertThat(detail(taker(userId), advanced)).as(userId).containsEntry("nodeUnlocked", unlocked);
            assertThat(feedIds(taker(userId)).contains(advanced)).as(userId).isEqualTo(unlocked);
        }
        // The count of a draft bound to the advanced node, against the Java rule over every complete profile.
        String draft = (String) run("QB_PUBLICATION_SAVE", token, bound(version(token, "Count draft quiz"),
            "Count draft", map.treeId(), map.advanced())).get("publicationId");
        String auditor = TestTokens.bearer(tokens, "tree-auditor", QbPermissions.AUDIENCE_SYSTEM);
        Map<String, Object> params = new HashMap<>();
        params.put("publicationId", draft);
        params.put("at", clock.instant().toString());
        params.put("publisherId", sponsor);
        params.put("regions", "JP,ASIA");
        params.put("treeId", map.treeId());
        params.put("treeNodeId", map.advanced());
        long counted = ((Number) items("qb.system.audience-count", auditor, Map.of("params", params)).getFirst()
            .get("takers")).longValue();
        params.remove("treeId");
        params.remove("treeNodeId");
        long unbound = ((Number) items("qb.system.audience-count", auditor, Map.of("params", params)).getFirst()
            .get("takers")).longValue();
        long expected = 0;
        long everyone = 0;
        for (Map<String, Object> profile : latest("qb_profile_version", "profile_id", "country_code = 'JP'")) {
            String userId = (String) profile.get("user_id");
            if (userId.equals(sponsor) || !canAnswer(userId)) {
                continue;
            }
            everyone++;
            if (javaUnlocked(userId, map.treeId(), map.advanced())) {
                expected++;
            }
        }
        assertThat(unbound).isEqualTo(everyone);
        assertThat(counted).isEqualTo(expected).isPositive().isLessThan(everyone);
    }

    /** The Java rule over the stored rows: the node in effect, and every node to be lit before answering lit now. */
    private static boolean javaUnlocked(String userId, String treeId, String nodeId) {
        List<Map<String, Object>> nodes = query("SELECT active, before_answer FROM qb_tree_node WHERE tree_id = ? AND "
            + "node_id = ?", treeId, nodeId);
        if (nodes.isEmpty() || !Boolean.TRUE.equals(nodes.getFirst().get("active"))) {
            return false;
        }
        List<TreeLights.Lit> lits = query("SELECT lit_id, node_id FROM qb_tree_lit_version WHERE subject_kind = 'USER' "
            + "AND subject_id = ?", userId).stream().map(row -> new TreeLights.Lit(String.valueOf(row.get("lit_id")),
                (String) row.get("node_id"))).toList();
        List<String> revoked = query("SELECT lit_id FROM qb_tree_lit_revocation_version WHERE subject_kind = 'USER' "
            + "AND subject_id = ?", userId).stream().map(row -> (String) row.get("lit_id")).toList();
        return TreeLights.unmet(TreeProcesses.nodeIds(nodes.getFirst().get("before_answer")),
            TreeLights.litNow(lits, revoked)).isEmpty();
    }

    /** A taker in Japan whose address is verified: a new version of the platform's user, as the verification writes. */
    private String verifiedTaker(String name) {
        String userId = newTaker(name, "JP");
        List<String> columns = query("SELECT column_name FROM information_schema.columns WHERE table_schema = "
            + "current_schema() AND table_name = 'sec_user_version' AND column_name NOT IN ('row_id', 'version_no', "
            + "'verified_email') ORDER BY ordinal_position").stream().map(row -> (String) row.get("column_name"))
            .toList();
        String list = String.join(", ", columns);
        execute("INSERT INTO sec_user_version (" + list + ", version_no, verified_email) SELECT " + list
            + ", version_no + 1, email FROM sec_user_version WHERE user_id = ?::uuid ORDER BY version_no DESC LIMIT 1",
            userId);
        return userId;
    }

    /** An enabled account with a verified address, as the count template asks. */
    private static boolean canAnswer(String userId) {
        List<Map<String, Object>> users = latest("sec_user_version", "user_id", "user_id = '" + userId + "'");
        if (users.isEmpty()) {
            return false;
        }
        Map<String, Object> user = users.getFirst();
        return Boolean.TRUE.equals(user.get("enabled")) && user.get("verified_email") != null
            && String.valueOf(user.get("verified_email")).equalsIgnoreCase(String.valueOf(user.get("email")));
    }
}
