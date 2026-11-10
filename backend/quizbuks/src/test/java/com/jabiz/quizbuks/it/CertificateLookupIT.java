package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Looking a certificate up (F-S5a, docs/quizbuks/plans/impl/F-S5.md §2.4): the platform's administrators find any by its
 * number or its holder, with the state of its lighting; an operator's members find only those of the maps they look
 * after (their copies and their Sponsors' maps), never another operator's or the platform's.
 */
class CertificateLookupIT extends NodeCertificateItSupport {

    private String reader() {
        return TestTokens.bearer(tokens, "fs5a-lookup-reader", QbPermissions.ADMIN_USERS_READ);
    }

    private static Map<String, Object> byKey(String key) {
        return Map.of("filters", List.of(Map.of("field", "verifyKey", "op", "eq", "value", key)), "limit", 100);
    }

    @Test
    void theAdministratorFindsAnyCertificateByItsNumberAndAnOperatorOnlyItsOwn() {
        Map<String, Object> start = node(null, "start", "Getting started", all(passed()));
        start.put("certificateNames", names("Getting started"));
        Copy copy = copy(List.of(start, node(null, "next", "Running a quiz", all(lit("start", true), passed()))));
        String sponsor = sponsorOf(copy.operator(), "s-fs5a-lookup");
        pass(sponsor, liveBound(copy.operator().leadId(), unique("FS5a lookup"), copy.treeId(), copy.start(),
            Map.of("tiers", List.of(body("fromScore", 0, "toScore", 5, "reward", 0)))));
        Map<String, Object> copysRow = only(certificateRows(sponsor));
        String copysKey = (String) copysRow.get("verify_key");

        // A Taker's certificate of the platform's own map.
        Gate gate = gate(names("Platform"));
        String ann = newTaker("fs5a-lookup-ann", "JP");
        light(gate, ann);
        String platformsKey = (String) only(certificateRows(ann)).get("verify_key");

        // The platform's administrators: by number, with the state of the lighting; by holder.
        assertThat(items("qb.admin.certificates", reader(), byKey(copysKey))).singleElement().satisfies(row ->
            assertThat(row).containsEntry("source", "NODE").containsEntry("subjectKind", "SPONSOR")
                .containsEntry("userId", sponsor).containsEntry("treeId", copy.treeId())
                .containsEntry("revokedAt", null).containsEntry("shared", true)
                .containsEntry("issuerName", copysRow.get("issuer_name")));
        assertThat(items("qb.admin.certificates", reader(), Map.of("params", Map.of("userId", ann))))
            .singleElement().satisfies(row -> assertThat(row).containsEntry("verifyKey", platformsKey)
                .containsEntry("subjectKind", "USER"));
        revoke(copysRow.get("lit_id"));
        assertThat(items("qb.admin.certificates", reader(), byKey(copysKey))).singleElement().satisfies(row ->
            assertThat(row.get("revokedAt")).isNotNull());
        assertThat(items("qb.admin.certificates", reader(), byKey("0".repeat(32)))).isEmpty();

        // The operator's member: the copy's certificate (and its Sponsor), not the platform's map's.
        String member = memberSession(copy.operator().leadId(), copy.operator().operatorId());
        assertThat(items("qb.operator.certificates", member, byKey(copysKey))).singleElement().satisfies(row ->
            assertThat(row).containsEntry("userId", sponsor).containsEntry("treeId", copy.treeId())
                .containsEntry("holderName", copysRow.get("holder_name")));
        assertThat(items("qb.operator.certificates", member, byKey(platformsKey))).isEmpty();
        assertThat(items("qb.operator.certificates", member, Map.of("params", Map.of("userId", ann)))).isEmpty();

        // Another operator's member finds neither.
        NewOperator other = officialOperator("FS5a lookup other");
        String stranger = memberSession(other.leadId(), other.operatorId());
        assertThat(items("qb.operator.certificates", stranger, byKey(copysKey))).isEmpty();
        assertThat(items("qb.operator.certificates", stranger, byKey(platformsKey))).isEmpty();
        assertThat(items("qb.operator.certificates", stranger, Map.of("params", Map.of("userId", sponsor)))).isEmpty();

        // The platform's own operator looks after the Sponsors who signed up by themselves: it finds a certificate of
        // such a Sponsor's own map (below) and still not the copy's.
        String platformMember = platformOperatorMember();
        assertThat(items("qb.operator.certificates", platformMember, byKey(copysKey))).isEmpty();
    }

    @Test
    void anOperatorFindsTheCertificatesOfItsSponsorsOwnMaps() {
        String owner = newSponsor("fs5a-lookup-owner");
        String token = sponsorToken(owner);
        Map<String, Object> tree = run(com.jabiz.quizbuks.tree.TreeProcesses.CREATE, token, body("ownerKind",
            "SPONSOR", "name", name("Lookup graph")));
        Map<String, Object> node = node(null, "own", "Own", all(passed()));
        node.put("certificateNames", names("Own certificate"));
        Map<String, Object> saved = save(token, tree, List.of(node));
        decide(platformOperatorMember(), submit(token, saved).get("approvalRequestId"), "APPROVE", null);
        @SuppressWarnings("unchecked")
        String ownNode = ((Map<String, String>) saved.get("nodeIds")).get("own");
        String publication = liveBound(owner, unique("Lookup"), String.valueOf(tree.get("treeId")), ownNode,
            Map.of("tiers", List.of(body("fromScore", 0, "toScore", 5, "reward", 0))));
        String bob = newTaker("fs5a-lookup-bob", "JP");
        assertThat(play(bob, publication, true, true)).containsEntry("passed", true);
        deliverEvents();
        String key = (String) only(certificateRows(bob)).get("verify_key");

        // The Sponsor has no operator of their own but the platform's: its member finds it, another operator's not.
        assertThat(items("qb.operator.certificates", platformOperatorMember(), byKey(key))).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("userId", bob).containsEntry("subjectKind", "USER"));
        NewOperator other = officialOperator("FS5a lookup own");
        assertThat(items("qb.operator.certificates", memberSession(other.leadId(), other.operatorId()), byKey(key)))
            .isEmpty();
    }
}
