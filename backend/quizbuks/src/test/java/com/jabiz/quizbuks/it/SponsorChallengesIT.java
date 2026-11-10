package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.play.SponsorPlay;
import com.jabiz.quizbuks.setup.QbParams;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-S4c: the Sponsor's challenges ({@code qb.sponsor.challenges}) give the nodes of their operator's copies with the
 * challenges bound to them, and say a challenge can be started exactly when {@code QB_SPONSOR_ATTEMPT_START} starts
 * it: before a pass, not after it; another operator's Sponsor sees none of them (and is refused, 404).
 */
class SponsorChallengesIT extends TreeItSupport {

    private static final ObjectMapper JSON = new ObjectMapper();

    private List<Map<String, Object>> challenges(String sponsor) {
        return items("qb.sponsor.challenges", sponsorToken(sponsor), Map.of("params",
            Map.of("at", clock.instant().toString()), "limit", 200));
    }

    private Map<String, Object> row(String sponsor, String treeId, String nodeId) {
        return challenges(sponsor).stream().filter(r -> treeId.equals(r.get("treeId"))
            && nodeId.equals(r.get("nodeId"))).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> publications(Map<String, Object> row) throws Exception {
        return JSON.readValue(String.valueOf(row.get("publications")), List.class);
    }

    private void publishGate(String value) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("paramKey", QbParams.TREE_GATE_DIGITAL_PRIZE);
        values.put("value", value);
        values.put("valueKind", QbParams.TREE_GATE_KIND);
        Map<String, Object> change = run("CONTROL_CHANGE_PROPOSE", proposer(), body("targetEntity", "SysParam",
            "values", values, "reason", "F-S4c test"));
        run("CONTROL_CHANGE_PUBLISH", financeAdmin(), body("changeId", change.get("changeId")));
    }

    @Test
    void aChallengeIsStartableExactlyWhenTheSponsorsStartStartsIt() throws Exception {
        Copy copy = copy();
        publishGate(copy.templateId() + "/" + copy.start());
        String publicationId = liveBound(copy.operator().leadId(), unique("FS4c challenge"), copy.treeId(),
            copy.start(), Map.of("tiers", List.of(body("fromScore", 0, "toScore", 5, "reward", 0))));
        String sponsor = sponsorOf(copy.operator(), "s-fs4c");

        Map<String, Object> before = row(sponsor, copy.treeId(), copy.start());
        assertThat(before).containsEntry("approved", true).containsEntry("lit", false)
            .containsEntry("templateTreeId", copy.templateId());
        assertThat(String.valueOf(before.get("gates"))).contains(QbParams.TREE_GATE_DIGITAL_PRIZE);
        assertThat(publications(before)).singleElement().satisfies(p -> assertThat(p)
            .containsEntry("publicationId", publicationId).containsEntry("startable", true));
        // The other node has no challenge bound to it.
        assertThat(publications(row(sponsor, copy.treeId(), copy.next()))).isEmpty();

        // Startable: the start starts it; passed, the node is lit and the challenge no longer startable.
        String token = sponsorToken(sponsor);
        String attemptId = (String) run(SponsorPlay.START, token, body("publicationId", publicationId)).get("attemptId");
        assertThat(publications(row(sponsor, copy.treeId(), copy.start()))).singleElement().satisfies(p ->
            assertThat(p).containsEntry("attemptId", attemptId).containsEntry("startable", true));
        Map<String, Object> input = submission(attemptId);
        input.put("answers", shown(attemptId, answers(true, true)));
        assertThat(run(SponsorPlay.SUBMIT, token, input)).containsEntry("passed", true);
        deliverEvents();
        Map<String, Object> after = row(sponsor, copy.treeId(), copy.start());
        assertThat(after).containsEntry("lit", true);
        assertThat(publications(after)).singleElement().satisfies(p -> assertThat(p)
            .containsEntry("startable", false).containsEntry("status", "SUBMITTED"));
        assertThat(codes(refused(SponsorPlay.START, token, body("publicationId", publicationId), 422))).isNotEmpty();

        // Another operator's Sponsor: none of these rows, and the start is refused as if there were none.
        Copy other = copy();
        String stranger = sponsorOf(other.operator(), "s-fs4c-other");
        assertThat(challenges(stranger)).noneMatch(r -> copy.treeId().equals(r.get("treeId")));
        refused(SponsorPlay.START, sponsorToken(stranger), body("publicationId", publicationId), 404);
    }

    @Test
    void aSponsorWithNoRewardMethodPublishesAChallenge() throws Exception {
        // F-S4c: the default methods empty (F-S4b, option A): a challenge pays nothing and is published all the same.
        Map<String, Object> change = run("CONTROL_CHANGE_PROPOSE", proposer(), body("targetEntity", "SysParam",
            "values", Map.of("paramKey", QbParams.REWARD_DEFAULT_METHODS, "value", ""), "reason", "F-S4c test"));
        run("CONTROL_CHANGE_PUBLISH", financeAdmin(), body("changeId", change.get("changeId")));
        try {
            Copy copy = copy();
            String publicationId = liveBound(copy.operator().leadId(), unique("FS4c no methods"), copy.treeId(),
                copy.start(), Map.of("tiers", List.of(body("fromScore", 0, "toScore", 5, "reward", 0))));
            assertThat(publications(row(sponsorOf(copy.operator(), "s-fs4c-nomethod"), copy.treeId(), copy.start())))
                .singleElement().satisfies(p -> assertThat(p).containsEntry("publicationId", publicationId));
        } finally {
            platformRewardMethods();
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.beans.factory.annotation.Qualifier("qbTreeNodeCountJob")
    com.jabiz.job.JobDefinition<com.jabiz.quizbuks.tree.TreeNodeCounts.CountInput> countJob;

    private void answer(String sponsor, String publicationId, boolean right) {
        String token = sponsorToken(sponsor);
        String attemptId = (String) run(SponsorPlay.START, token, body("publicationId", publicationId)).get("attemptId");
        Map<String, Object> input = submission(attemptId);
        input.put("answers", shown(attemptId, answers(right, right)));
        assertThat(run(SponsorPlay.SUBMIT, token, input)).containsEntry("passed", right);
        deliverEvents();
    }

    @Test
    void aCopysNodesShowTheOperatorHowManySponsorsLitThemAndAnsweredWithout() {
        Copy copy = copy();
        String publicationId = liveBound(copy.operator().leadId(), unique("FS4c counts"), copy.treeId(),
            copy.start(), Map.of("tiers", List.of(body("fromScore", 0, "toScore", 5, "reward", 0))));
        // Five pass (the default minimum group), one does not.
        for (int i = 0; i < 5; i++) {
            answer(sponsorOf(copy.operator(), "s-fs4c-count-" + i), publicationId, true);
        }
        answer(sponsorOf(copy.operator(), "s-fs4c-count-failed"), publicationId, false);

        // The daily count covers the copy (a map Sponsors light): one answered and has not lit it.
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        jobRunner.execute(countJob, clock.instant().plus(java.time.Duration.ofDays(1)));
        assertThat(query("SELECT answered_unlit FROM qb_tree_node_count WHERE tree_id = ? AND node_id = ?",
            copy.treeId(), copy.start())).singleElement().satisfies(row ->
                assertThat(((Number) row.get("answered_unlit")).intValue()).isEqualTo(1));

        // The operator's member reads the lit count of the node (from the minimum group up) on the copy's version.
        Object versionId = query("SELECT current_version_id FROM qb_tree WHERE tree_id = ?", copy.treeId()).getFirst()
            .get("current_version_id");
        String member = memberSession(copy.operator().leadId(), copy.operator().operatorId());
        List<Map<String, Object>> nodes = items("qb.operator.tree-version", member, Map.of("params",
            Map.of("versionId", String.valueOf(versionId))));
        assertThat(nodes.stream().filter(n -> copy.start().equals(n.get("nodeId"))).findFirst().orElseThrow()
            .get("litCount")).satisfies(n -> assertThat(((Number) n).intValue()).isEqualTo(5));
        assertThat(nodes.stream().filter(n -> copy.next().equals(n.get("nodeId"))).findFirst().orElseThrow()
            .get("litCount")).isNull();
    }

    @Test
    void theGrantShownIsTheTemplatesNodesNow() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("paramKey", QbParams.TREE_GRANT_MAX_PER_NODE);
        values.put("value", "300");
        values.put("valueKind", QbParams.FEE_CREDIT_MANUAL_MAX_KIND);
        Map<String, Object> change = run("CONTROL_CHANGE_PROPOSE", proposer(), body("targetEntity", "SysParam",
            "values", values, "reason", "F-S4c test"));
        run("CONTROL_CHANGE_PUBLISH", financeAdmin(), body("changeId", change.get("changeId")));
        Copy copy = copy(new BigDecimal("200"));
        String sponsor = sponsorOf(copy.operator(), "s-fs4c-grant");
        assertThat(new BigDecimal(String.valueOf(row(sponsor, copy.treeId(), copy.start()).get("grantAmount"))))
            .isEqualByComparingTo("200");
        assertThat(row(sponsor, copy.treeId(), copy.next()).get("grantAmount")).isNull();
    }
}
