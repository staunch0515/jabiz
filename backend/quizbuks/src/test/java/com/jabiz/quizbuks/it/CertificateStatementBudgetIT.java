package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.certification.CertificateProcesses;
import com.jabiz.quizbuks.certification.NodeCertificates;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The statement budget of the certificates of lit nodes (F-S5a; platform decision D46 rule 7, as
 * {@code AttemptStatementBudgetIT}): how many statements the settlement of a person's certificates ({@code
 * QB_TREE_LIT_CERTIFICATE}, which the lighting's event and the daily job run), the holder's first download and the
 * ones after send, in their transaction. A change in either direction is made on purpose, together with the numbers
 * here. The lighting itself sends one statement more only for a node that issues a certificate (its event); the numbers of
 * {@link AttemptStatementBudgetIT} (an answer's start and submission) do not change.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = "it.sql-log.enabled=true")
class CertificateStatementBudgetIT extends NodeCertificateItSupport {

    /**
     * {@code QB_TREE_LIT_CERTIFICATE} writing one certificate: the operation, the issuer's name, the person's lock, one
     * read of the person's lit records, revocations, certificates, profile, Sponsor row and account, one of the maps of
     * the lit records, the certificate (with its audit record; the operators and Sponsors owning the maps are read in one
     * statement more only when a map has such an owner), {@code COMMIT}.
     */
    static final int SETTLE_WRITING = 7;
    /** The same when it finds nothing to write (a redelivered event, the daily job): no map read, no write. */
    static final int SETTLE_NOTHING = 5;
    /**
     * The holder's first download: the operation, banned?, the certificate's lock, the certificate, its revocations, the
     * document's operation, its templates, the document, then in one statement the document's event and the run's id
     * on the certificate, {@code COMMIT}.
     */
    static final int ISSUE_FIRST = 10;
    /**
     * Every download after: the operation, banned?, the lock, the certificate (no revocation is read for a document
     * issued), the idempotency result, {@code COMMIT}.
     */
    static final int ISSUE_AGAIN = 6;

    private static List<String> own(List<List<String>> transactions) {
        List<List<String>> own = transactions.stream()
            .filter(t -> t.stream().anyMatch(sql -> sql.startsWith("INSERT INTO op_process ("))).toList();
        assertThat(own).as("one transaction").hasSize(1);
        return own.getFirst();
    }

    private static String listing(List<String> statements) {
        return IntStream.range(0, statements.size())
            .mapToObj(i -> String.format("%3d  %s", i + 1, abbreviated(statements.get(i))))
            .collect(Collectors.joining("\n"));
    }

    /** A statement's first words: enough to tell which it is (the reads of several entities in one are long). */
    private static String abbreviated(String sql) {
        String flat = sql.replaceAll("\\s+", " ");
        return flat.length() <= 140 ? flat : flat.substring(0, 140) + " ...";
    }

    private void assertBudget(String name, List<String> statements, int total) {
        System.out.println("[statement-budget] " + name + ": " + statements.size() + "\n" + listing(statements));
        assertThat(statements).as(name + ":\n" + listing(statements)).hasSize(total);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> keyed(String process, String token, Map<String, Object> input) {
        var result = client.post().uri("/api/processes/" + process + "/latest").contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, token).header("Idempotency-Key", "budget-" + UUID.randomUUID())
            .bodyValue(input).exchange().expectBody(MAP).returnResult();
        assertThat(result.getStatus().value()).as(process + " answered " + result.getResponseBody()).isEqualTo(200);
        return (Map<String, Object>) result.getResponseBody().get("output");
    }

    @Test
    void settlingAPersonsCertificatesAndIssuingTheirDocumentSendTheBudgetedStatements() {
        Gate gate = gate(names("Budgeted"));
        // Warm: the first of each kind runs once before it is counted, as in production.
        String warm = newTaker("fs5a-budget-warm", "JP");
        light(gate, warm);
        issue(CertificateProcesses.ISSUE, taker(warm), only(certificateRows(warm)).get("certificate_id"), "en");

        String ann = newTaker("fs5a-budget-ann", "JP");
        // Lit without the event delivered: the settlement is run by hand.
        assertThat(play(ann, gate.publicationId(), true, true)).containsEntry("passed", true);
        deliverEvents();
        execute("DELETE FROM qb_certificate WHERE user_id = ?", ann);

        SqlStatementLog.clear();
        runAsSystem(NodeCertificates.SETTLE, body("subjectId", ann));
        assertBudget("QB_TREE_LIT_CERTIFICATE writing", own(SqlStatementLog.transactions()), SETTLE_WRITING);
        assertThat(certificateRows(ann)).hasSize(1);

        SqlStatementLog.clear();
        runAsSystem(NodeCertificates.SETTLE, body("subjectId", ann));
        assertBudget("QB_TREE_LIT_CERTIFICATE nothing", own(SqlStatementLog.transactions()), SETTLE_NOTHING);

        Object id = only(certificateRows(ann)).get("certificate_id");
        SqlStatementLog.clear();
        keyed(CertificateProcesses.ISSUE, taker(ann), Map.of("certificateId", id, "language", "en"));
        assertBudget("QB_CERTIFICATE_ISSUE first", own(SqlStatementLog.transactions()), ISSUE_FIRST);
        SqlStatementLog.clear();
        keyed(CertificateProcesses.ISSUE, taker(ann), Map.of("certificateId", id, "language", "en"));
        assertBudget("QB_CERTIFICATE_ISSUE again", own(SqlStatementLog.transactions()), ISSUE_AGAIN);
    }
}
