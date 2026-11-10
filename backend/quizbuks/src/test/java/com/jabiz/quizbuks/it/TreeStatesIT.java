package com.jabiz.quizbuks.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.jabiz.quizbuks.tree.TreeNodeStates;
import com.jabiz.quizbuks.tree.TreeProcesses;
import com.jabiz.quizbuks.tree.TreeSupport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * F-S3a: what the graph pages read (docs/quizbuks/plans/impl/F-S3.md §2). A person's five states come from
 * {@code qb.app.tree} (the facts) and {@code qb.app.feed} with its {@code tree} parameter (what they can start), as the
 * pages classify them with {@link TreeNodeStates}; the Sponsor's graphs, versions and nodes; the nodes the wizard
 * offers; and support's recorded read of one person ({@code QB_SUPPORT_TREE_STATE}), limited to the operator's own.
 */
class TreeStatesIT extends TreeItSupport {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private List<Map<String, Object>> graph(String token, String treeId) {
        return items("qb.app.tree", token, Map.of("params", Map.of("treeId", treeId), "limit", 500));
    }

    /** Each node's state as the pages show it: the template's facts, and whether the feed has a row of it. */
    @SuppressWarnings("unchecked")
    private Map<String, String> states(String token, String treeId) {
        Set<Object> startable = Set.copyOf(feed(token, "JP", Map.of("tree", treeId)).stream()
            .map(row -> row.get("treeNodeId")).toList());
        Map<String, String> states = new LinkedHashMap<>();
        for (Map<String, Object> row : graph(token, treeId)) {
            List<String> unmet = JSON.readValue(String.valueOf(row.get("unmet")), List.class);
            List<Map<String, Object>> refs = JSON.readValue(String.valueOf(row.get("refs")), List.class);
            List<String> litRefs = new ArrayList<>();
            refs.stream().filter(ref -> Boolean.TRUE.equals(ref.get("lit")))
                .forEach(ref -> litRefs.add(String.valueOf(ref.get("node"))));
            Map<String, Object> condition = JSON.readValue(String.valueOf(row.get("condition")), Map.class);
            states.put(String.valueOf(row.get("nodeId")), TreeNodeStates.of(new TreeNodeStates.Facts(
                Boolean.TRUE.equals(row.get("lit")), unmet, startable.contains(row.get("nodeId")),
                com.jabiz.quizbuks.tree.TreeCondition.parse(condition), Set.copyOf(litRefs))).name());
        }
        return states;
    }

    @Test
    void aPersonsNodesShowTheFiveStatesFromTheTemplatesAndTheFeed() {
        String sponsor = newSponsor(unique("s-fs3-states"));
        credit(sponsor, 10_000);
        Map3 map = map(sponsor);
        String basics = liveBound(sponsor, "States basics", map.treeId(), map.basics());
        String advanced = liveBound(sponsor, "States advanced", map.treeId(), map.advanced());
        String userId = newTaker(unique("t-fs3-states"), "JP");
        String mine = taker(userId);

        // Nothing lit: basics can be answered, advanced is locked behind basics, expert misses both nodes.
        assertThat(states(mine, map.treeId())).containsExactly(Map.entry(map.basics(), "CAN_ANSWER"),
            Map.entry(map.advanced(), "LOCKED"), Map.entry(map.expert(), "MISSING"));
        assertThat(graph(mine, map.treeId())).filteredOn(row -> map.advanced().equals(row.get("nodeId")))
            .singleElement().satisfies(row -> {
                assertThat(row.get("unmet")).isEqualTo("[\"" + map.treeId() + "/" + map.basics() + "\"]");
                assertThat(row).containsEntry("lit", false);
                assertThat(String.valueOf(row.get("treeName"))).contains("Coffee");
            });
        // "My graphs" lists a Sponsor's graph once the person has answered or lit something in it.
        assertThat(myTrees(mine)).doesNotContain(map.treeId());
        // The feed's tree parameter lists only the graph's publications the person can start.
        assertThat(feed(mine, "JP", Map.of("tree", map.treeId()))).extracting(row -> row.get("publicationId"))
            .containsExactly(basics);

        // Basics passed: lit; advanced opens and can be answered; expert still misses advanced.
        assertThat(play(userId, basics, true, true)).containsEntry("passed", true);
        deliverEvents();
        assertThat(states(mine, map.treeId())).containsExactly(Map.entry(map.basics(), "LIT"),
            Map.entry(map.advanced(), "CAN_ANSWER"), Map.entry(map.expert(), "MISSING"));
        assertThat(myTrees(mine)).contains(map.treeId());
        assertThat(items("qb.app.trees", mine, Map.of("limit", 500)))
            .filteredOn(row -> map.treeId().equals(row.get("treeId"))).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("nodes", 3).containsEntry("litNodes", 1));

        // Another person fails advanced: one attempt only, so nothing left to start, and only a pass missing.
        String other = newTaker(unique("t-fs3-waiting"), "JP");
        play(other, basics, true, true);
        deliverEvents();
        assertThat(play(other, advanced, false, false)).containsEntry("passed", false);
        assertThat(states(taker(other), map.treeId())).containsEntry(map.advanced(), "WAITING");

        // The first passes advanced: advanced and expert (both its nodes now lit) are lit.
        play(userId, advanced, true, true);
        deliverEvents();
        deliverEvents();
        assertThat(states(mine, map.treeId())).containsExactly(Map.entry(map.basics(), "LIT"),
            Map.entry(map.advanced(), "LIT"), Map.entry(map.expert(), "LIT"));
        // Expert's condition names both, resolved to this graph, both lit.
        assertThat(graph(mine, map.treeId())).filteredOn(row -> map.expert().equals(row.get("nodeId")))
            .singleElement().satisfies(row -> assertThat(String.valueOf(row.get("refs")))
                .contains("\"lit\": true").doesNotContain("\"lit\": false"));
    }

    @Test
    void aGateNodeSaysWhatItOpensAndAnotherGraphIsNotRead() {
        Gate gate = gate();
        nameGate("qb.tree.gate.referrer-node", gate);
        String mine = taker(newTaker(unique("t-fs3-gate"), "JP"));
        assertThat(graph(mine, gate.treeId())).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("gates", "qb.tree.gate.referrer-node"));
        // The platform's graphs Takers light are everybody's.
        assertThat(myTrees(mine)).contains(gate.treeId());
        // A graph that is not one Takers light in effect gives no rows (a draft, another kind or none).
        Map<String, Object> draft = run(TreeProcesses.CREATE, sponsorToken(newSponsor(unique("s-fs3-draft"))),
            body("ownerKind", "SPONSOR", "name", name("Draft")));
        assertThat(graph(mine, String.valueOf(draft.get("treeId")))).isEmpty();
    }

    @Test
    void theSponsorReadsItsOwnGraphsVersionsAndNodesAndTheNodesItMayBind() {
        String sponsor = newSponsor(unique("s-fs3-own"));
        credit(sponsor, 10_000);
        String token = sponsorToken(sponsor);
        Map3 map = map(sponsor);
        String ownBasics = liveBound(sponsor, "Own basics", map.treeId(), map.basics());

        Map<String, Object> mineRow = items("qb.sponsor.trees", token, Map.of("limit", 50)).stream()
            .filter(row -> map.treeId().equals(row.get("treeId"))).findFirst().orElseThrow();
        assertThat(mineRow).containsEntry("status", "ACTIVE").containsEntry("currentVersionNo", 1)
            .containsEntry("nodes", 3).containsEntry("runningBound", 1).containsEntry("openVersionId", null);
        // Another Sponsor reads none of it.
        String stranger = sponsorToken(newSponsor(unique("s-fs3-stranger")));
        assertThat(items("qb.sponsor.trees", stranger, Map.of("limit", 50)))
            .noneMatch(row -> map.treeId().equals(row.get("treeId")));
        assertThat(items("qb.sponsor.tree-versions", stranger, Map.of("params", Map.of("treeId", map.treeId()))))
            .isEmpty();

        // A new draft sent back (another Sponsor's: one graph each): the open version with the reviewer's reason, and
        // the history.
        String drafter = sponsorToken(newSponsor(unique("s-fs3-drafter")));
        Map<String, Object> tree = run(TreeProcesses.CREATE, drafter, body("ownerKind", "SPONSOR", "name",
            name("Tea")));
        Map<String, Object> saved = save(drafter, tree, List.of(node(null, "one", "One", all(passed()))));
        Map<String, Object> submitted = submit(drafter, saved);
        decide(platformOperatorMember(), submitted.get("approvalRequestId"), "REJECT", "Name the node better");
        String treeId = String.valueOf(tree.get("treeId"));
        assertThat(items("qb.sponsor.trees", drafter, Map.of("limit", 50))).filteredOn(row -> treeId.equals(
            row.get("treeId"))).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("openStatus", "REJECTED").containsEntry("rejectReason", "Name the node better")
                .containsEntry("currentVersionId", null));
        List<Map<String, Object>> versions = items("qb.sponsor.tree-versions", drafter, Map.of("params",
            Map.of("treeId", treeId)));
        assertThat(versions).singleElement().satisfies(row -> assertThat(row).containsEntry("status", "REJECTED"));

        // The version's nodes: one lit person is under the minimum group (no count); the bound publication counts.
        String versionId = String.valueOf(mineRow.get("currentVersionId"));
        String userId = newTaker(unique("t-fs3-own"), "JP");
        play(userId, ownBasics, true, true);
        deliverEvents();
        assertThat(items("qb.sponsor.tree", token, Map.of("params", Map.of("versionId", versionId))))
            .filteredOn(row -> map.basics().equals(row.get("nodeId"))).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("inEffect", true).containsEntry("litCount", null)
                .containsEntry("runningBound", 1));
        assertThat(items("qb.sponsor.tree", stranger, Map.of("params", Map.of("versionId", versionId)))).isEmpty();

        // The wizard offers the nodes asking for a pass of the own graph (not expert's, which asks none).
        assertThat(items("qb.sponsor.tree-bindable-nodes", token, Map.of("limit", 500)))
            .filteredOn(row -> map.treeId().equals(row.get("treeId"))).extracting(row -> row.get("nodeId"))
            .containsExactlyInAnyOrder(map.basics(), map.advanced());
        assertThat(items("qb.sponsor.tree-bindable-nodes", stranger, Map.of("limit", 500)))
            .noneMatch(row -> map.treeId().equals(row.get("treeId")));
    }

    @Test
    void anOperatorsDirectSponsorMayBindThePlatformsNodes() {
        Gate gate = gate();
        NewOperator operator = officialOperator("FS3 bind");
        assertThat(items("qb.sponsor.tree-bindable-nodes", sponsorToken(operator.leadId()), Map.of("limit", 500)))
            .extracting(row -> row.get("nodeId")).contains(gate.nodeId());
        assertThat(items("qb.sponsor.tree-bindable-nodes", sponsorToken(newSponsor(unique("s-fs3-not-direct"))),
            Map.of("limit", 500))).noneMatch(row -> gate.treeId().equals(row.get("treeId")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void supportReadsOnePersonsLightingRecordedAndAnotherOperatorFindsNothing() {
        String sponsor = newSponsor(unique("s-fs3-support"));
        credit(sponsor, 10_000);
        Map3 map = map(sponsor);
        String basics = liveBound(sponsor, "Support basics", map.treeId(), map.basics());
        String userId = newTaker(unique("t-fs3-support"), "JP");
        play(userId, basics, true, true);
        deliverEvents();

        // The Sponsor's operator (the platform's default): the states and the lit record.
        Map<String, Object> shown = run(TreeSupport.PROCESS, platformOperatorMember(), body("userId", userId,
            "treeId", map.treeId()));
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) shown.get("nodes");
        assertThat(nodes).extracting(node -> node.get("nodeId"), node -> node.get("state")).containsExactly(
            org.assertj.core.groups.Tuple.tuple(map.basics(), "LIT"),
            org.assertj.core.groups.Tuple.tuple(map.advanced(), "OPEN"),
            org.assertj.core.groups.Tuple.tuple(map.expert(), "OPEN"));
        assertThat((List<?>) nodes.getFirst().get("lits")).hasSize(1);
        // Every read is in the record of processes, its summary only the two ids.
        assertThat(query("SELECT count(*) AS n FROM op_process WHERE process_name = ? AND input_summary::text LIKE ?",
            TreeSupport.PROCESS, "%" + userId + "%").getFirst().get("n")).isEqualTo(1L);

        // Another operator's member: as if the graph did not exist. A person without the permission: refused.
        NewOperator other = officialOperator("FS3 support other");
        refused(TreeSupport.PROCESS, memberSession(other.leadId(), other.operatorId()), body("userId", userId,
            "treeId", map.treeId()), 404);
        refused(TreeSupport.PROCESS, taker(newTaker(unique("t-fs3-nosy"), "JP")), body("userId", userId,
            "treeId", map.treeId()), 403);
    }

    private List<Object> myTrees(String token) {
        return items("qb.app.trees", token, Map.of("limit", 500)).stream().map(row -> row.get("treeId")).toList();
    }
}
