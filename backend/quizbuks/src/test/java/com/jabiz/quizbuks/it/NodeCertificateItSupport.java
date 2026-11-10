package com.jabiz.quizbuks.it;

import com.jabiz.job.JobDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.certification.CertificateProcesses;
import com.jabiz.quizbuks.certification.NodeCertificates;
import com.jabiz.quizbuks.play.SponsorPlay;
import com.jabiz.quizbuks.tree.TreeRevocations;
import com.jabiz.runtime.test.TestTokens;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** What the tests of the certificates of lit nodes (F-S5a) share: lighting, revoking, the daily job, the rows read. */
abstract class NodeCertificateItSupport extends TreeItSupport {

    @Autowired
    @Qualifier("qbTreeCertificateRetryJob")
    JobDefinition<NodeCertificates.SubjectRef> certificateRetryJob;

    static Map<String, Object> names(String text) {
        return Map.of("en", text, "zh", text + " (zh)", "ja", text + " (ja)");
    }

    /** The controlled parameter {@code key} published (four eyes). */
    void param(String key, String value) {
        Map<String, Object> change = run("CONTROL_CHANGE_PROPOSE", proposer(), body("targetEntity", "SysParam",
            "values", Map.of("paramKey", key, "value", value), "reason", "F-S5a test"));
        run("CONTROL_CHANGE_PUBLISH", financeAdmin(), body("changeId", change.get("changeId")));
    }

    /** The node certificates of the user, oldest first (the rows as written). */
    static List<Map<String, Object>> certificateRows(String userId) {
        return query("SELECT * FROM qb_certificate WHERE user_id = ? AND source = 'NODE' "
            + "ORDER BY issued_at, held_from, certificate_id", userId);
    }

    static Map<String, Object> only(List<Map<String, Object>> rows) {
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    /** The lit records of the user as a kind of subject, oldest first. */
    static List<Map<String, Object>> lits(String userId, String subjectKind) {
        return query("SELECT lit_id, tree_id, node_id FROM qb_tree_lit_version WHERE subject_id = ? "
            + "AND subject_kind = ? ORDER BY process_seq_id", userId, subjectKind);
    }

    /** The caller's certificates as the App reads them. */
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> mine(String token) {
        return (List<Map<String, Object>>) run(CertificateProcesses.MINE, token, body()).get("certificates");
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> sponsorMine(String token) {
        return (List<Map<String, Object>>) run(CertificateProcesses.SPONSOR_MINE, token, body()).get("certificates");
    }

    /** Anonymous: a public template's items. */
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> publicItems(String id, String query) {
        Map<String, Object> page = client.get().uri("/api/public/queries/" + id + "?" + query).exchange()
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        return (List<Map<String, Object>>) page.get("items");
    }

    /** The verify page's row of a key (none for an unknown key). */
    Map<String, Object> verify(Object key) {
        List<Map<String, Object>> items = publicItems("qb.public.certificate", "p.key=" + key);
        return items.isEmpty() ? null : items.getFirst();
    }

    /** A Sponsor passes the challenge; the events (the lighting, its certificate) are delivered. */
    void pass(String sponsor, String publicationId) {
        String token = sponsorToken(sponsor);
        String attemptId = (String) run(SponsorPlay.START, token, body("publicationId", publicationId))
            .get("attemptId");
        Map<String, Object> input = submission(attemptId);
        input.put("answers", shown(attemptId, answers(true, true)));
        assertThat(run(SponsorPlay.SUBMIT, token, input)).containsEntry("passed", true);
        deliverEvents();
    }

    String revoker() {
        return TestTokens.bearer(tokens, verifiedUser(unique("fs5a-revoker")), QbPermissions.TREE_LIT_REVOKE,
            QbPermissions.TREES_READ, "approval.decide");
    }

    /** Revokes the lit record (four eyes) and delivers the revocation's event. */
    void revoke(Object litId) {
        Map<String, Object> asked = run(TreeRevocations.REVOKE, revoker(), body("litId", litId, "reason",
            "Answers shared"));
        decide(revoker(), asked.get("approvalRequestId"), "APPROVE", null);
        deliverEvents();
    }

    /** The daily job's next run (a day later: a run of the same time is not run twice). */
    void runRetry() {
        clock.set(clock.instant().plus(Duration.ofDays(1)));
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        jobRunner.execute(certificateRetryJob, clock.instant());
        deliverEvents();
    }

    /** The certificates' settlement of a person run again, as a redelivered event would. */
    void settle(String userId) {
        runAsSystem(NodeCertificates.SETTLE, body("subjectId", userId));
    }

    /** The document of a certificate: first download (the holder issues it), and the PDF's bytes. */
    Map<String, Object> issue(String process, String token, Object certificateId, String language) {
        return run(process, token, body("certificateId", certificateId, "language", language));
    }

    byte[] pdf(String token, Object runId) {
        return client.get().uri("/api/documents/runs/{id}/pdf", runId).header(HttpHeaders.AUTHORIZATION, token)
            .exchange().expectStatus().isOk().expectBody(byte[].class).returnResult().getResponseBody();
    }

    static Map<String, Object> copyOf(Map<String, Object> map) {
        return new LinkedHashMap<>(map);
    }
}
