package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.tree.TreeProcesses;
import com.jabiz.quizbuks.tree.TreeRules;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The certificates of lit nodes (F-S5a, docs/quizbuks/plans/impl/F-S5.md §2): a node that names one issues it when lit,
 * once, frozen as lit (the holder, the names, the issuer, the time); it is not issued for a node that names none, nor
 * back-issued to those who lit a node before it named one; a revoked lighting leaves the certificate revoked on its
 * verify page and a new lighting gets a new certificate; the issuer is the owner of the graph (the platform's controlled
 * parameter, an operator's name for its copy, a Sponsor's company for their own graph); a Sponsor's certificates are
 * theirs as a Sponsor and a person's as a Taker are theirs as a Taker; who may name a certificate and what it may be named.
 */
class NodeCertificateIT extends NodeCertificateItSupport {

    private static boolean issuerNamed;

    /** The platform's issuer named once for the class's schema (until then the platform's own name stands). */
    private void nameIssuer() {
        if (!issuerNamed) {
            param(QbParams.ISSUER_NAME, "QuizBuks Operations K.K.");
            issuerNamed = true;
        }
    }

    @AfterEach
    void onlyTheSharingAndTheDocumentChange() {
        // The rows are only inserted, but for the holder's two changes (the platform's audit records them): the sharing
        // and the document run of the PDF issued.
        assertThat(query("SELECT DISTINCT f FROM sys_audit_record, unnest(changed_fields) AS f "
            + "WHERE entity_type = 'QbCertificate' AND action = 'UPDATE'").stream().map(r -> (String) r.get("f"))
            .filter(f -> !"version".equals(f)).toList()).isSubsetOf("shared", "runId");
        assertThat(query("SELECT coalesce(n_tup_del, 0) AS deleted FROM pg_stat_user_tables WHERE "
            + "schemaname = current_schema() AND relname = 'qb_certificate'").getFirst().get("deleted"))
            .isEqualTo(0L);
    }

    @Test
    void aLightingIssuesOneCertificateFrozenAsLitAndNoneForANodeWithoutOne() {
        Gate certified = gate(names("Fair dealing"));
        Gate plain = gate();
        String ann = newTaker("fs5a-ann", "JP");

        long events = outboxEvents("qb.tree.certificate-due", "subjectId", ann);
        light(certified, ann);
        light(plain, ann);

        // One certificate, of the node that names one; the other node issued none and asked for none.
        Map<String, Object> row = only(certificateRows(ann));
        assertThat(row).containsEntry("source", "NODE").containsEntry("subject_kind", "USER")
            .containsEntry("tree_id", certified.treeId()).containsEntry("node_id", certified.nodeId())
            .containsEntry("holder_name", "Taker JP").containsEntry("shared", true).containsEntry("valid_until", null)
            .containsEntry("certification", null).containsEntry("run_id", null);
        assertThat((String) row.get("verify_key")).matches("[0-9a-f]{32}");
        assertThat(String.valueOf(row.get("certificate_names"))).contains("Fair dealing");
        assertThat(outboxEvents("qb.tree.certificate-due", "subjectId", ann)).isEqualTo(events + 1);

        // Frozen as lit: when, and who vouches (the platform's graph: its own name while none is set).
        Map<String, Object> lit = query("SELECT lit_id, lit_at FROM qb_tree_lit_version WHERE subject_id = ? "
            + "AND node_id = ?", ann, certified.nodeId()).getFirst();
        assertThat(row.get("held_from")).isEqualTo(lit.get("lit_at"));
        assertThat(row.get("lit_id")).isEqualTo(String.valueOf(lit.get("lit_id")));
        assertThat(row.get("issuer_name")).isIn("QuizBuks", "QuizBuks Operations K.K.");
        // The key says nothing of the record it came from.
        assertThat((String) row.get("verify_key")).doesNotContain(String.valueOf(lit.get("lit_id")).replace("-", ""));

        // The same event delivered again, a settlement run again, the daily job: still one.
        settle(ann);
        runRetry();
        assertThat(certificateRows(ann)).hasSize(1);
        assertThat(only(certificateRows(ann)).get("verify_key")).isEqualTo(row.get("verify_key"));

        // The App's list: the certificate, not revoked, shared, no PDF yet.
        assertThat(mine(taker(ann))).singleElement().satisfies(c -> assertThat(c)
            .containsEntry("source", "NODE").containsEntry("verifyKey", row.get("verify_key"))
            .containsEntry("issuerName", row.get("issuer_name")).containsEntry("revokedAt", null)
            .containsEntry("shared", true).containsEntry("runId", null).containsEntry("heldUntil", null));
        // The held certifications of Q77's kind stay as they were: none.
        assertThat(run(com.jabiz.quizbuks.certification.CertificateProcesses.MINE, taker(ann), body()).get("held"))
            .isEqualTo(List.of());

        // The verify page: valid (no revocation), with no end.
        assertThat(verify(row.get("verify_key"))).containsEntry("source", "NODE").containsEntry("shared", true)
            .containsEntry("holderName", "Taker JP").containsEntry("revokedAt", null)
            .containsEntry("validUntil", null).containsEntry("certification", null)
            .containsKey("certificateNames");
    }

    @Test
    void theIssuerIsTheOwnerOfTheGraphAndAnOperatorsCopyKeepsTheTemplatesCertificate() {
        nameIssuer();
        // The platform's own graph: the controlled parameter.
        Gate platform = gate(names("Platform"));
        String ann = newTaker("fs5a-issuer-ann", "JP");
        light(platform, ann);
        assertThat(only(certificateRows(ann))).containsEntry("issuer_name", "QuizBuks Operations K.K.");

        // A Sponsor's own graph lit by a Taker: the Sponsor's company.
        String owner = newSponsor("fs5a-owner");
        String token = sponsorToken(owner);
        Map<String, Object> tree = run(TreeProcesses.CREATE, token, body("ownerKind", "SPONSOR", "name",
            name("Own graph")));
        Map<String, Object> node = node(null, "own", "Own", all(passed()));
        node.put("certificateNames", names("Own certificate"));
        Map<String, Object> saved = save(token, tree, List.of(node));
        assertThat(problemCodes(saved)).isEmpty();
        // Reviewed as a Sponsor's map with no operator is (by the platform).
        decide(platformOperatorMember(), submit(token, saved).get("approvalRequestId"), "APPROVE", null);
        @SuppressWarnings("unchecked")
        String ownNode = ((Map<String, String>) saved.get("nodeIds")).get("own");
        String publication = liveBound(owner, unique("Own"), String.valueOf(tree.get("treeId")), ownNode,
            Map.of("tiers", List.of(body("fromScore", 0, "toScore", 5, "reward", 0))));
        String bob = newTaker("fs5a-issuer-bob", "JP");
        assertThat(play(bob, publication, true, true)).containsEntry("passed", true);
        deliverEvents();
        assertThat(only(certificateRows(bob))).containsEntry("tree_id", String.valueOf(tree.get("treeId")))
            .containsEntry("issuer_name", String.valueOf(query("SELECT company FROM qb_sponsor_version WHERE user_id = ? "
                + "ORDER BY version_no DESC LIMIT 1", owner).getFirst().get("company")));
    }

    @SuppressWarnings("unchecked")
    private static List<Object> problemCodes(Map<String, Object> saved) {
        return ((List<Map<String, Object>>) saved.get("problems")).stream().map(p -> p.get("code")).toList();
    }

    @Test
    void aSponsorLightingAnOperatorsCopyIsIssuedTheCopysCertificateNamingTheOperatorAndNeverMixedWithTheirTakers() {
        Map<String, Object> start = node(null, "start", "Getting started", all(passed()));
        start.put("certificateNames", names("Getting started"));
        Copy copy = copy(List.of(start, node(null, "next", "Running a quiz", all(lit("start", true), passed()))));
        String sponsor = sponsorOf(copy.operator(), "s-fs5a-cert");
        // The Sponsor is also a Taker (a Sponsor's account answers in the app): they light the platform's gate too.
        Gate gate = gate(names("As a Taker"));
        String publication = liveBound(copy.operator().leadId(), unique("FS5a"), copy.treeId(), copy.start(),
            Map.of("tiers", List.of(body("fromScore", 0, "toScore", 5, "reward", 0))));
        pass(sponsor, publication);
        light(gate, sponsor);

        List<Map<String, Object>> rows = query("SELECT subject_kind, tree_id, issuer_name FROM qb_certificate "
            + "WHERE user_id = ? AND source = 'NODE' ORDER BY subject_kind", sponsor);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("subject_kind", "SPONSOR").containsEntry("tree_id", copy.treeId());
        // The operator's name, not the Sponsor's and not the platform's.
        assertThat(rows.get(0).get("issuer_name")).isEqualTo(String.valueOf(query(
            "SELECT name FROM qb_operator WHERE operator_id = ?", copy.operator().operatorId()).getFirst().get("name")));
        assertThat(rows.get(1)).containsEntry("subject_kind", "USER").containsEntry("tree_id", gate.treeId());

        // Each sees theirs: the Sponsor's list has the one as a Sponsor, the App's the one as a Taker.
        assertThat(sponsorMine(sponsorToken(sponsor))).singleElement().satisfies(c -> assertThat(c)
            .containsEntry("source", "NODE").containsEntry("issuerName", rows.get(0).get("issuer_name")));
        assertThat(mine(sponsorToken(sponsor))).singleElement().satisfies(c -> assertThat(c)
            .containsEntry("source", "NODE").containsEntry("issuerName", rows.get(1).get("issuer_name")));
        // A Taker as such has nothing of a Sponsor's.
        assertThat(rows.get(0).get("subject_kind")).isNotEqualTo(rows.get(1).get("subject_kind"));
    }

    @Test
    void aRevokedLightingLeavesTheCertificateRevokedAndANewLightingGetsANewOne() {
        Gate gate = gate(names("Revocable"));
        String ann = newTaker("fs5a-rev-ann", "JP");
        light(gate, ann);
        Map<String, Object> first = only(certificateRows(ann));
        String firstKey = (String) first.get("verify_key");
        assertThat(verify(firstKey)).containsEntry("revokedAt", null);

        // Revoked (four eyes): the row is not touched, the verify page and the list say so at once.
        revoke(first.get("lit_id"));
        assertThat(verify(firstKey).get("revokedAt")).isNotNull();
        assertThat(mine(taker(ann))).singleElement().satisfies(c -> assertThat(c.get("revokedAt")).isNotNull());
        assertThat(only(certificateRows(ann)).get("verify_key")).isEqualTo(firstKey);
        // The settlement and the daily job issue nothing for a revoked lighting.
        settle(ann);
        runRetry();
        assertThat(certificateRows(ann)).hasSize(1);

        // Passed again after the revocation: a new lit record, a new certificate; the old one stays revoked.
        clock.set(clock.instant().plus(java.time.Duration.ofMinutes(1)));
        String again = liveBound(officialOperator("FS5a again").leadId(), unique("Again"), gate.treeId(),
            gate.nodeId(), Map.of("tiers", List.of(body("fromScore", 0, "toScore", 5, "reward", 0))));
        light(new Gate(gate.treeId(), gate.nodeId(), again), ann);
        List<Map<String, Object>> rows = certificateRows(ann);
        assertThat(rows).hasSize(2);
        String secondKey = (String) rows.get(1).get("verify_key");
        assertThat(secondKey).isNotEqualTo(firstKey);
        assertThat(rows.get(1).get("lit_id")).isNotEqualTo(first.get("lit_id"));
        assertThat(verify(secondKey)).containsEntry("revokedAt", null);
        assertThat(verify(firstKey).get("revokedAt")).isNotNull();
        assertThat(mine(taker(ann))).hasSize(2);
    }

    @Test
    void aCertificateAddedToANodeLaterIsNotIssuedToThoseWhoLitItBeforeButToThoseWhoLightItAfter() {
        String writer = mapWriter();
        Map<String, Object> tree = run(TreeProcesses.CREATE, writer, body("ownerKind", "PLATFORM", "subjectKind",
            "TAKER", "name", name("Later")));
        Map<String, Object> saved = save(writer, tree, List.of(node(null, "later", "Later", all(passed()))));
        decide(mapWriter(), submit(writer, saved).get("approvalRequestId"), "APPROVE", null);
        @SuppressWarnings("unchecked")
        String nodeId = ((Map<String, String>) saved.get("nodeIds")).get("later");
        String treeId = String.valueOf(tree.get("treeId"));
        NewOperator operator = officialOperator("FS5a later");
        String publicationId = liveBound(operator.leadId(), unique("Later"), treeId, nodeId,
            Map.of("tiers", List.of(body("fromScore", 0, "toScore", 5, "reward", 0))));
        Gate gate = new Gate(treeId, nodeId, publicationId);
        String early = newTaker("fs5a-early", "JP");
        light(gate, early);
        assertThat(certificateRows(early)).isEmpty();

        // The node now names a certificate (a new version, reviewed and in effect).
        Map<String, Object> current = Map.of("treeId", treeId, "revision", ((Number) query(
            "SELECT revision FROM qb_tree WHERE tree_id = ?", treeId).getFirst().get("revision")).longValue());
        Map<String, Object> changed = node(nodeId, null, "Later", all(passed()));
        changed.put("certificateNames", names("Added later"));
        Map<String, Object> second = save(writer, current, List.of(changed));
        assertThat(problemCodes(second)).isEmpty();
        decide(mapWriter(), submit(writer, second).get("approvalRequestId"), "APPROVE", null);

        // Whoever lit it before has none, however the job runs; whoever lights it now has one.
        runRetry();
        assertThat(certificateRows(early)).isEmpty();
        String late = newTaker("fs5a-late", "JP");
        light(gate, late);
        assertThat(only(certificateRows(late)).get("certificate_names").toString()).contains("Added later");
    }

    @Test
    void whoMayNameACertificateAndWhatItMayBeNamed() {
        String writer = mapWriter();
        // A template names one; the names need a text, at most the node's name length, in the platform's languages.
        Map<String, Object> template = run(TreeProcesses.CREATE, writer, body("ownerKind", "PLATFORM", "subjectKind",
            "SPONSOR", "name", name("Rules")));
        Map<String, Object> blank = node(null, "n", "N", all(passed()));
        blank.put("certificateNames", Map.of("en", "  ", "zh", ""));
        assertThat(problemCodes(save(writer, template, List.of(blank)))).containsExactly(TreeRules.CERTIFICATE_INVALID);
        Map<String, Object> tooLong = node(null, "n", "N", all(passed()));
        tooLong.put("certificateNames", Map.of("en", "x".repeat(121)));
        assertThat(problemCodes(save(writer, current(template), List.of(tooLong)))).containsExactly(
            TreeRules.CERTIFICATE_INVALID);
        // None named at all (null or empty) is no certificate, not a problem.
        Map<String, Object> none = node(null, "n", "N", all(passed()));
        none.put("certificateNames", Map.of());
        assertThat(problemCodes(save(writer, current(template), List.of(none)))).isEmpty();
        Map<String, Object> one = node(null, "n", "N", all(passed()));
        one.put("certificateNames", Map.of("ja", "認定"));
        Map<String, Object> saved = save(writer, current(template), List.of(one));
        assertThat(problemCodes(saved)).isEmpty();

        // An operator's copy keeps the template's certificate: a save naming another is a problem, none keeps it.
        decide(mapWriter(), submit(writer, saved).get("approvalRequestId"), "APPROVE", null);
        NewOperator operator = officialOperator("FS5a rules");
        String lead = memberSession(operator.leadId(), operator.operatorId());
        Map<String, Object> copy = run(TreeProcesses.COPY, lead, body("templateTreeId", template.get("treeId")));
        String nodeId = String.valueOf(query("SELECT node_id FROM qb_tree_node_def WHERE version_id = ?",
            copy.get("versionId")).getFirst().get("node_id"));
        assertThat(String.valueOf(query("SELECT certificate_names FROM qb_tree_node_def WHERE version_id = ?",
            copy.get("versionId")).getFirst().get("certificate_names"))).contains("認定");
        Map<String, Object> changed = node(nodeId, null, "N", all(passed()));
        changed.put("certificateNames", Map.of("en", "Another"));
        assertThat(problemCodes(save(lead, copy, List.of(changed)))).containsExactly(TreeRules.CERTIFICATE_COPY);
        assertThat(problemCodes(save(lead, current(copy), List.of(node(nodeId, null, "N", all(passed()))))))
            .isEmpty();
        assertThat(String.valueOf(query("SELECT certificate_names FROM qb_tree_node_def WHERE version_id = ?",
            copy.get("versionId")).getFirst().get("certificate_names"))).contains("認定");
        // The same names given again are no change.
        Map<String, Object> same = node(nodeId, null, "N", all(passed()));
        same.put("certificateNames", Map.of("ja", "認定"));
        assertThat(problemCodes(save(lead, current(copy), List.of(same)))).isEmpty();
    }

    private Map<String, Object> current(Map<String, Object> tree) {
        return Map.of("treeId", tree.get("treeId"), "revision", ((Number) query(
            "SELECT revision FROM qb_tree WHERE tree_id = ?", String.valueOf(tree.get("treeId"))).getFirst()
            .get("revision")).longValue());
    }

    @Test
    void theNodeRowOfTheVersionInEffectCarriesTheCertificateAndTheLitRecordFreezesIt() {
        Gate gate = gate(names("Carried"));
        assertThat(String.valueOf(query("SELECT certificate_names FROM qb_tree_node WHERE tree_id = ? AND node_id = ?",
            gate.treeId(), gate.nodeId()).getFirst().get("certificate_names"))).contains("Carried");
        String ann = newTaker("fs5a-frozen", "JP");
        light(gate, ann);
        Map<String, Object> lit = new LinkedHashMap<>(query("SELECT certificate_names FROM qb_tree_lit_version "
            + "WHERE subject_id = ?", ann).getFirst());
        assertThat(String.valueOf(lit.get("certificate_names"))).contains("Carried");
    }
}
