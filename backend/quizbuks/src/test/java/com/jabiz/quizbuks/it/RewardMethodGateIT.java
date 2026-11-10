package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.play.SponsorPlay;
import com.jabiz.quizbuks.publishing.PublishingCodes;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.tree.TreeRevocations;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reward methods a lit node opens (F-S4b, docs/quizbuks/plans/impl/F-S4.md §3): the gate parameters
 * {@code qb.tree.gate.digital-prize-node} / {@code offline-redeem-node} name a node of a platform template Sponsors
 * light; a Sponsor who lit that node in an operator's copy of it may publish with the method, until the lighting is
 * revoked. The methods are the default's, the Sponsor's own setting's and the opened ones (the requester,
 * 2026-11-10); {@code qb.sponsor.reward-methods} agrees with the save ({@code publishing.PublicationEditing}).
 */
class RewardMethodGateIT extends TreeItSupport {

    private static List<Object> problemCodes(Map<String, Object> saved) {
        return ((List<?>) saved.get("problems")).stream().map(p -> (Object) ((Map<?, ?>) p).get("code")).toList();
    }

    private Map<String, Object> methods(String sponsor) {
        return items("qb.sponsor.reward-methods", sponsorToken(sponsor), Map.of()).getFirst();
    }

    /** Whether a draft with the method is refused for the method alone. */
    private boolean refused(String sponsor, String method) {
        String token = sponsorToken(sponsor);
        Map<String, Object> save = complete(version(token, "Gate quiz"), unique("Gate "), clock.instant(),
            clock.instant().plus(java.time.Duration.ofDays(7)));
        save.put("rewardMethod", method);
        var result = client.post().uri("/api/processes/QB_PUBLICATION_SAVE/latest")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .header(org.springframework.http.HttpHeaders.AUTHORIZATION, token).bodyValue(save).exchange()
            .expectBody(MAP).returnResult();
        Map<String, Object> body = result.getResponseBody();
        if (result.getStatus().value() == 422) {
            return codes(body).contains(PublishingCodes.REWARD_METHOD_NOT_ALLOWED);
        }
        assertThat(result.getStatus().value()).as(String.valueOf(body)).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) body.get("output");
        return problemCodes(output).contains(PublishingCodes.REWARD_METHOD_NOT_ALLOWED);
    }

    /** The Sponsor passes the challenge, and the lighting's events are delivered. */
    private void pass(String sponsor, String publicationId) {
        String token = sponsorToken(sponsor);
        String attemptId = (String) run(SponsorPlay.START, token, body("publicationId", publicationId))
            .get("attemptId");
        Map<String, Object> input = submission(attemptId);
        input.put("answers", shown(attemptId, answers(true, true)));
        assertThat(run(SponsorPlay.SUBMIT, token, input)).containsEntry("passed", true);
        deliverEvents();
    }

    private void name(String key, String value) {
        Map<String, Object> change = run("CONTROL_CHANGE_PROPOSE", proposer(), body("targetEntity", "SysParam",
            "values", Map.of("paramKey", key, "value", value, "valueKind", QbParams.TREE_GATE_KIND),
            "reason", "F-S4b gate"));
        run("CONTROL_CHANGE_PUBLISH", financeAdmin(), body("changeId", change.get("changeId")));
    }

    private List<Object> health() {
        return items("qb.admin.tree-health", TestTokens.bearer(tokens, "fs4b-health", QbPermissions.TREES_READ),
            Map.of("params", Map.of("at", clock.instant().toString()))).stream().map(row -> row.get("checkCode"))
            .toList();
    }

    private String revoker() {
        return TestTokens.bearer(tokens, verifiedUser(unique("fs4b-revoker")), QbPermissions.TREE_LIT_REVOKE,
            QbPermissions.TREES_READ, "approval.decide");
    }

    @Test
    void aNodeLitInTheOperatorsCopyOpensItsMethodUntilTheLightingIsRevoked() {
        Copy copy = copy();
        String challenge = liveBound(copy.operator().leadId(), unique("FS4b digital"), copy.treeId(), copy.start(),
            Map.of("tiers", List.of(body("fromScore", 0, "toScore", 5, "reward", 0))));
        String sponsor = sponsorOf(copy.operator(), "s-fs4b-gate");
        String other = sponsorOf(copy.operator(), "s-fs4b-other");

        // Not named (the default): closed, lit or not.
        pass(sponsor, challenge);
        assertThat(refused(sponsor, "DIGITAL_PRIZE")).isTrue();
        assertThat(String.valueOf(methods(sponsor).get("methods"))).doesNotContain("DIGITAL_PRIZE");

        // Named by the template's node: open to the Sponsor who lit it in the copy, not to the one who did not.
        name(QbParams.TREE_GATE_DIGITAL_PRIZE, copy.templateId() + "/" + copy.start());
        assertThat(refused(sponsor, "DIGITAL_PRIZE")).isFalse();
        assertThat(refused(sponsor, "OFFLINE_REDEEM")).isTrue();
        assertThat(methods(sponsor)).containsEntry("unlocked", "DIGITAL_PRIZE");
        assertThat(String.valueOf(methods(sponsor).get("methods"))).contains("DIGITAL_PRIZE");
        assertThat(refused(other, "DIGITAL_PRIZE")).isTrue();
        assertThat(methods(other)).containsEntry("unlocked", "");
        assertThat(health()).doesNotContain("GATE_INVALID");

        // The lighting revoked (four eyes): closed again.
        Object litId = query("SELECT lit_id FROM qb_tree_lit_version WHERE subject_kind = 'SPONSOR' AND subject_id = ? "
            + "AND node_id = ?", sponsor, copy.start()).getFirst().get("lit_id");
        Map<String, Object> asked = run(TreeRevocations.REVOKE, revoker(), body("litId", litId, "reason",
            "Answers shared"));
        decide(revoker(), asked.get("approvalRequestId"), "APPROVE", null);
        assertThat(refused(sponsor, "DIGITAL_PRIZE")).isTrue();
        assertThat(methods(sponsor)).containsEntry("unlocked", "");
    }

    @Test
    void aGateNamingNoTemplateSponsorsLightStaysClosedAndIsReported() {
        Copy copy = copy();
        String challenge = liveBound(copy.operator().leadId(), unique("FS4b redeem"), copy.treeId(), copy.start(),
            Map.of("tiers", List.of(body("fromScore", 0, "toScore", 5, "reward", 0))));
        String sponsor = sponsorOf(copy.operator(), "s-fs4b-bad");
        pass(sponsor, challenge);

        // The copy's node (an operator's map, not the platform's template): closed, reported.
        name(QbParams.TREE_GATE_OFFLINE_REDEEM, copy.treeId() + "/" + copy.start());
        assertThat(refused(sponsor, "OFFLINE_REDEEM")).isTrue();
        assertThat(methods(sponsor)).containsEntry("unlocked", "");
        assertThat(health()).contains("GATE_INVALID");

        // The template's node: open; reported no more.
        name(QbParams.TREE_GATE_OFFLINE_REDEEM, copy.templateId() + "/" + copy.start());
        assertThat(refused(sponsor, "OFFLINE_REDEEM")).isFalse();
        assertThat(methods(sponsor)).containsEntry("unlocked", "OFFLINE_REDEEM");
        assertThat(health()).doesNotContain("GATE_INVALID");
    }

    @Test
    void theOwnSettingAddsToTheDefaultInsteadOfReplacingIt() {
        String sponsor = newSponsor(unique("s-fs4b-own"));
        String defaults = String.valueOf(methods(sponsor).get("methods"));
        run(com.jabiz.quizbuks.publishing.RewardMethodProcesses.SET, TestTokens.bearer(tokens, "fs4b-methods",
            QbPermissions.ADMIN_REWARD_METHODS), body("userId", sponsor, "methods", List.of("SPONSOR_CASH")));
        Map<String, Object> now = methods(sponsor);
        assertThat(now).containsEntry("own", true);
        for (String method : defaults.split(",")) {
            if (!method.isBlank()) {
                assertThat(String.valueOf(now.get("methods"))).contains(method);
                assertThat(refused(sponsor, method)).as(method).isFalse();
            }
        }
        assertThat(String.valueOf(now.get("methods"))).contains("SPONSOR_CASH");
        assertThat(refused(sponsor, "SPONSOR_CASH")).isFalse();
    }
}
