package com.jabiz.finance.it;

import com.jabiz.finance.ap.BillEntities;
import com.jabiz.finance.ap.BillProcesses;
import com.jabiz.finance.audit.AuditProcesses;
import com.jabiz.finance.bank.ReconciliationEntities;
import com.jabiz.finance.bank.ReconciliationProcesses;
import com.jabiz.finance.gl.JournalEntities;
import com.jabiz.finance.setup.FinanceRoles;
import com.jabiz.finance.setup.SetupProcesses;
import com.jabiz.runtime.approval.ApprovalEntities;
import com.jabiz.runtime.ledger.LedgerEntities;
import com.jabiz.runtime.security.SecurityEntities;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * FIN-SCN-12, audit support and integrity (FIN-CT-010, CT-011, CT-012, CT-020, CT-021, FIN-SC-003; ROADMAP F10b), on
 * January's books, closed:
 * <ol>
 *   <li>the auditor asks for the manual entries of January above 10,000.00 with their approvals, the user-access
 *       report and January's bank reconciliation; the controller issues them as one package (FIN_AUDIT_PACKAGE) and
 *       exports it; {@code tools/finance/verify-package.py} finds it intact without the system, and finds a file of it
 *       changed afterwards; the access review is signed off on the package's report;</li>
 *   <li>the integrity verification finds the books intact; a posted ledger line changed past the application's guard
 *       is reported, and intact again once changed back;</li>
 *   <li>under a legal hold on V200's bills, deleting V200's 2026 bills is refused;</li>
 *   <li>the books are exported to the open archive and {@code tools/finance/trial-balance-from-archive.py} recomputes
 *       FIN-EXP-03 from the files alone.</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class FinScn12IT extends JanuaryBooks {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Path TOOLS = SAMPLE_COMPANY.resolve("../../../tools/finance").normalize();
    /** The ExternalAuditor's assignment: fiscal 2026 in Chicago. */
    private static final Instant FY2026_FROM = Instant.parse("2026-01-01T06:00:00Z");
    private static final Instant FY2026_TO = Instant.parse("2027-01-01T06:00:00Z");

    @Test
    @SuppressWarnings("unchecked")
    void auditSupportAndIntegrity() throws IOException {
        januaryPostings();
        closeJanuary();
        String admin = as("admin", "*");
        String auditorUser = user(admin, "ext-auditor");
        assign(admin, auditorUser, FinanceRoles.EXTERNAL_AUDITOR, FY2026_FROM, FY2026_TO);
        assign(admin, user(admin, "ctl-anna"), FinanceRoles.CONTROLLER, null, null);
        Map<String, Object> rec = find(ReconciliationEntities.RECONCILIATION_DATASET, "bankCode", "OPERATING")
            .getFirst();
        ok(ReconciliationProcesses.ISSUE_REPORT, accountant, Map.of("reconciliationId", rec.get("reconciliationId")));
        String archivedRec = (String) read(ReconciliationEntities.RECONCILIATION_DATASET, rec.get("reconciliationId"))
            .get("reportHash");

        // Step 1: the auditor's request, issued as one package by the controller a minute later.
        clock.advance(java.time.Duration.ofMinutes(1));
        people();
        Instant reviewedTo = clock.instant();
        Map<String, Object> pkg = ok(AuditProcesses.PACKAGE, controller, Map.of("request",
            "All manual entries above 10,000.00 in January with approvals, the user-access report and the January"
                + " bank reconciliation", "from", "2026-01-01", "to", "2026-01-31", "minAmount", "10000.00",
            "accessReviewAsOf", reviewedTo.toString(), "bankCode", "OPERATING", "statementDate", "2026-01-31"));
        List<Map<String, Object>> reports = (List<Map<String, Object>>) pkg.get("reports");
        assertThat(reports).extracting(r -> r.get("templateId")).containsExactly(AuditProcesses.MANUAL_ENTRIES,
            AuditProcesses.ACCESS_REVIEW, AuditProcesses.RECONCILIATION);
        // The package's reconciliation is the one archived at its issue, to the hash.
        assertThat(reports.get(2)).containsEntry("contentHash", archivedRec);

        // The manual entries are JE-0001 and JE-0002, each with its approver and the rule version that asked for it.
        String ruleId = (String) find(ApprovalEntities.RULE_DATASET, "ruleCode", SetupProcesses.APPROVAL_RULE).getFirst()
            .get("ruleId");
        List<Map<String, Object>> entries = report(AuditProcesses.MANUAL_ENTRIES, controller, Map.of("from",
            "2026-01-01", "to", "2026-01-31", "minAmount", "10000.00"));
        assertThat(entries).extracting(e -> e.get("journalNo")).containsExactly("JE-0001", "JE-0002");
        assertThat(entries).allSatisfy(e -> {
            assertThat(e).containsEntry("preparer", "accountant").containsEntry("approver", "controller")
                .containsEntry("outcome", "PENDING");
            assertThat((String) e.get("ruleVersions")).contains(ruleId + ":1");
            assertThat(e.get("approvedTime")).isNotNull();
        });
        assertThat(((Number) reports.get(0).get("rows")).intValue()).isEqualTo(2);
        // The package's own report, as archived: the same two entries and their approver.
        String issuedEntries = new String(get("/api/reports/runs/" + reports.get(0).get("runId")
            + "/export?format=csv", controller).expectStatus().isOk().expectBody(byte[].class).returnResult()
            .getResponseBody(), StandardCharsets.UTF_8);
        assertThat(issuedEntries).contains("JE-0001", "JE-0002", "controller").doesNotContain("JE-0003")
            .doesNotContain("PAYROLL-2601");

        // The package exported as the answer says; verified without the system.
        Map<String, Object> exportRequest = (Map<String, Object>) pkg.get("export");
        byte[] zip = exportZip(controller, exportRequest);
        Map<String, byte[]> files = unzip(zip);
        for (Map<String, Object> r : reports) {
            assertThat(files.keySet()).as((String) r.get("templateId"))
                .anyMatch(n -> n.startsWith("reports/" + r.get("runId") + "-") && n.endsWith(".pdf"));
        }
        assertThat(files.keySet().stream().filter(n -> n.startsWith("reports/"))).hasSize(3);
        assertThat(new String(files.get("data/urn_jabiz_dataset_default_FinJournal.csv"), StandardCharsets.UTF_8))
            .contains("JE-0001", "JE-0002");
        Path dir = Files.createTempDirectory("fin-scn-12");
        Path packageZip = Files.write(dir.resolve("package.zip"), zip);
        Path answer = Files.write(dir.resolve("package.json"), JSON.writeValueAsBytes(pkg));
        String manifestHash = sha256(files.get("manifest.json"));
        Tool verified = python("verify-package.py", packageZip.toString(), "--expect", answer.toString(),
            "--manifest-sha256", manifestHash);
        assertThat(verified.exit()).as(verified.output()).isZero();
        assertThat(verified.output()).startsWith("OK ").contains("manifest SHA-256 " + manifestHash);
        // A changed file, a file added: both found.
        Map<String, byte[]> changed = new LinkedHashMap<>(files);
        changed.put("data/urn_jabiz_dataset_default_FinJournal.csv", new String(files.get(
            "data/urn_jabiz_dataset_default_FinJournal.csv"), StandardCharsets.UTF_8).replace("JE-0002", "JE-0009")
            .getBytes(StandardCharsets.UTF_8));
        changed.put("data/extra.csv", "a\r\n1\r\n".getBytes(StandardCharsets.UTF_8));
        Tool tampered = python("verify-package.py", Files.write(dir.resolve("tampered.zip"), zip(changed)).toString(),
            "--expect", answer.toString());
        assertThat(tampered.exit()).isEqualTo(1);
        assertThat(tampered.output()).contains(
            "FAIL data/urn_jabiz_dataset_default_FinJournal.csv: SHA-256 differs from the manifest",
            "FAIL data/extra.csv: not in the manifest");
        // A file changed with its manifest entry matches the manifest, but not the manifest's hash handed over.
        String journalCsv = "data/urn_jabiz_dataset_default_FinJournal.csv";
        byte[] forged = changed.get(journalCsv);
        Map<String, Object> manifest = JSON.readValue(files.get("manifest.json"), Map.class);
        for (Map<String, Object> f : (List<Map<String, Object>>) manifest.get("files")) {
            if (journalCsv.equals(f.get("path"))) {
                f.put("sha256", sha256(forged));
                f.put("bytes", forged.length);
            }
        }
        Map<String, byte[]> rewritten = new LinkedHashMap<>(files);
        rewritten.put(journalCsv, forged);
        rewritten.put("manifest.json", JSON.writeValueAsBytes(manifest));
        Path rewrittenZip = Files.write(dir.resolve("rewritten.zip"), zip(rewritten));
        assertThat(python("verify-package.py", rewrittenZip.toString()).exit()).isZero();
        Tool caught = python("verify-package.py", rewrittenZip.toString(), "--manifest-sha256", manifestHash);
        assertThat(caught.exit()).isEqualTo(1);
        assertThat(caught.output()).contains("FAIL manifest.json: SHA-256 differs from the one handed over");
        // The whole package's hash, as the evidence package page shows it, catches it too.
        assertThat(python("verify-package.py", packageZip.toString(), "--package-sha256", sha256(zip)).exit()).isZero();
        Tool other = python("verify-package.py", rewrittenZip.toString(), "--package-sha256", sha256(zip));
        assertThat(other.exit()).isEqualTo(1);
        assertThat(other.output()).contains("SHA-256 differs from the one handed over");
        // A second entry of a listed name is refused.
        Tool duplicated = python("verify-package.py", Files.write(dir.resolve("twice.zip"),
            appendEntry(zip, journalCsv, forged)).toString());
        assertThat(duplicated.exit()).isEqualTo(1);
        assertThat(duplicated.output()).contains("FAIL " + journalCsv + ": in the package more than once");
        // A report left out of the package is missed too.
        Map<String, byte[]> without = new LinkedHashMap<>(files);
        without.keySet().removeIf(n -> n.startsWith("reports/" + reports.get(1).get("runId")));
        Tool incomplete = python("verify-package.py", Files.write(dir.resolve("incomplete.zip"), zip(without))
            .toString(), "--expect", answer.toString());
        assertThat(incomplete.exit()).isEqualTo(1);
        assertThat(incomplete.output()).contains("report " + AuditProcesses.ACCESS_REVIEW);

        // FIN-SC-003: the access report lists every user with their roles, the auditor's limited to fiscal 2026;
        // the reviewer signs off on the package's report and the sign-off is kept.
        String reviewRun = (String) reports.get(1).get("runId");
        List<Map<String, Object>> access = report(AuditProcesses.ACCESS_REVIEW, as("reviewer",
            "security.access-review.read"), Map.of("asOf", reviewedTo.toString()));
        assertThat(access).extracting(a -> a.get("userName") + " " + a.get("roleCode")).contains(
            "ext-auditor " + FinanceRoles.EXTERNAL_AUDITOR, "ctl-anna " + FinanceRoles.CONTROLLER);
        assertThat(access).filteredOn(a -> "ext-auditor".equals(a.get("userName"))).allSatisfy(a -> assertThat(a)
            .containsEntry("dataFrom", FY2026_FROM.toString()).containsEntry("dataTo", FY2026_TO.toString()));
        clock.advance(java.time.Duration.ofMinutes(5));
        people();
        Map<String, Object> signed = ok("ACCESS_REVIEW_SIGN_OFF", as("sysadmin", "security.access-review.sign",
            "security.access-review.read"), Map.of("periodFrom", FY2026_FROM.toString(), "periodTo",
                reviewedTo.toString(), "reportRunId", reviewRun, "reviewComment", "January access reviewed"));
        assertThat(signed).containsEntry("reportHash", reports.get(1).get("contentHash"));
        assertThat(query("SELECT reviewer FROM sys_access_review WHERE report_run_id::text = ?", reviewRun))
            .singleElement().satisfies(r -> assertThat(r).containsEntry("reviewer", "sysadmin"));

        // Step 2: intact; a posted line of JE-0002 changed past the guard is found, and intact once changed back.
        String integrity = as("admin", "integrity.seal", "integrity.verify", "integrity.read");
        integrity("INTEGRITY_SEAL", integrity);
        assertThat(integrity("INTEGRITY_VERIFY", integrity)).containsEntry("intact", true);
        Object transactionId = find(JournalEntities.JOURNAL_DATASET, "journalNo", "JE-0002").getFirst()
            .get("transactionId");
        Map<String, Object> line = query("SELECT row_id FROM ledger_entry_version"
            + " WHERE transaction_id = CAST(? AS uuid) ORDER BY line_no LIMIT 1", transactionId).getFirst();
        bypassingTheGuard("UPDATE ledger_entry_version SET amount = amount + 1000 WHERE row_id = " + line.get("row_id"));
        Map<String, Object> broken = integrity("INTEGRITY_VERIFY", integrity);
        assertThat(broken).containsEntry("intact", false);
        Map<String, Object> check = get("/api/integrity/checks/" + broken.get("checkNo"), integrity).expectStatus()
            .isOk().expectBody(MAP).returnResult().getResponseBody();
        assertThat((List<Map<String, Object>>) check.get("problems")).singleElement().satisfies(p -> {
            assertThat(p).containsEntry("kind", "MODIFIED").containsEntry("table", "ledger_entry_version");
            // The version table's own key: the version row of that line.
            assertThat(p).containsEntry("key", "[" + line.get("row_id") + "]");
        });
        bypassingTheGuard("UPDATE ledger_entry_version SET amount = amount - 1000 WHERE row_id = " + line.get("row_id"));
        assertThat(integrity("INTEGRITY_VERIFY", integrity)).containsEntry("intact", true);

        // Step 3: a legal hold on V200's bills; neither the posted 2026 bill nor a draft of V200 is deleted.
        String draft = (String) ok(BillProcesses.SAVE, apClerk, Map.of("vendorCode", "V200", "vendorInvoiceNo",
            "DC-HOLD", "invoiceDate", "2026-01-31", "lines", List.of(Map.of("description", "Held", "amount",
                "10.00", "account", "6400")))).get("billId");
        ok("LEGAL_HOLD_PLACE", as("counsel", "legal.hold.write"), Map.of("name", "V200 dispute", "reason",
            "Claim by V200", "entityType", BillEntities.BILL, "field", "vendorCode", "value", "V200"));
        Map<String, Object> posted = find(BillEntities.BILL_DATASET, "vendorInvoiceNo", "DC-2026-01").getFirst();
        assertThat(refused(BillProcesses.DELETE, apClerk, Map.of("billId", posted.get("billId")), 422))
            .isEqualTo(BillProcesses.NOT_DRAFT);
        assertThat(refused(BillProcesses.DELETE, apClerk, Map.of("billId", draft), 422)).isEqualTo("LEGAL_HOLD");

        // Step 4: the books in the open archive; the trial balance from the files alone is FIN-EXP-03.
        byte[] archive = exportZip(as("archivist", "data.export", "ledger.read", "ledger.account.read",
            "fin.journal.read", "report.archive.read"), Map.of("datasets", List.of(LedgerEntities.ACCOUNT_DATASET,
                LedgerEntities.ENTRY_DATASET, JournalEntities.POSTING_DATASET), "reports", true));
        assertThat(unzip(archive).keySet()).contains("schema.json", "manifest.json")
            .anyMatch(n -> n.startsWith("reports/") && n.endsWith(".pdf"));
        Path archiveZip = Files.write(dir.resolve("archive.zip"), archive);
        assertThat(python("verify-package.py", archiveZip.toString()).exit()).isZero();
        assertThat(python("trial-balance-from-archive.py", archiveZip.toString(), "--through", "2026-1-31").exit())
            .isEqualTo(2);
        Tool recomputed = python("trial-balance-from-archive.py", archiveZip.toString(), "--through", "2026-01-31");
        assertThat(recomputed.exit()).as(recomputed.output()).isZero();
        Map<String, String> trialBalance = new TreeMap<>();
        String total = null;
        for (String row : recomputed.output().lines().skip(1).toList()) {
            String[] cells = row.split(",");
            if ("TOTAL".equals(cells[0])) {
                total = cells[1] + " " + cells[2];
            } else {
                trialBalance.put(cells[0], cells[1] + " " + cells[2]);
            }
        }
        Map<String, String> expected = new TreeMap<>();
        for (String[] row : expectedRows("FIN-EXP-03")) {
            if (row[0].matches("\\d{4}")) {
                expected.put(row[0], money(row[2]).setScale(2).toPlainString() + " "
                    + money(row[3]).setScale(2).toPlainString());
            }
        }
        assertThat(trialBalance).isEqualTo(expected);
        assertThat(total).isEqualTo("826012.90 826012.90");
    }

    /** Sealing and verifying go through every append-only row of the books: longer than a request usually takes. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> integrity(String process, String authorization) {
        Map<String, Object> result = client.mutate().responseTimeout(java.time.Duration.ofMinutes(5)).build().post()
            .uri("/api/processes/" + process + "/latest").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .header(org.springframework.http.HttpHeaders.AUTHORIZATION, authorization).bodyValue(Map.of()).exchange()
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        return (Map<String, Object>) result.get("output");
    }

    private String user(String admin, String name) {
        return (String) ok("SEC_USER_CREATE", admin, Map.of("userName", name, "displayName", name,
            "password", "password-123")).get("userId");
    }

    private void assign(String admin, String userId, String role, Instant from, Instant to) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("userId", userId);
        attributes.put("roleId", find(SecurityEntities.ROLE_DATASET, "roleCode", role).getFirst().get("roleId"));
        if (from != null) {
            attributes.put("dataFrom", from.toString());
            attributes.put("dataTo", to.toString());
        }
        post("/api/datasets/" + SecurityEntities.USER_ROLE_DATASET + "/commit", admin, Map.of("changes",
            List.of(Map.of("action", "INSERT", "attributes", attributes)))).expectStatus().isOk();
    }

    /** What a tool printed and how it ended. */
    record Tool(int exit, String output) {}

    /** Runs a script of tools/finance with the Python found on the path. */
    private static Tool python(String script, String... args) {
        List<String> command = new ArrayList<>(List.of("python3", TOOLS.resolve(script).toString()));
        command.addAll(List.of(args));
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return new Tool(process.waitFor(), output);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static byte[] zip(Map<String, byte[]> files) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                out.putNextEntry(new ZipEntry(file.getKey()));
                out.write(file.getValue());
                out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    /** The ZIP with one more entry of a name it already has, which {@link ZipOutputStream} refuses to write. */
    private static byte[] appendEntry(byte[] zip, String name, byte[] content) throws IOException {
        Path file = Files.createTempFile("twice", ".zip");
        Files.write(file, zip);
        // Python's zipfile appends an entry of an existing name (with a warning), as a forger's tool would.
        Process process = new ProcessBuilder("python3", "-c", "import sys, zipfile, warnings\n"
            + "warnings.simplefilter('ignore')\n"
            + "zipfile.ZipFile(sys.argv[1], 'a').writestr(sys.argv[2], open(sys.argv[3], 'rb').read())",
            file.toString(), name, Files.write(Files.createTempFile("entry", ".csv"), content).toString())
            .redirectErrorStream(true).start();
        try {
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(process.waitFor()).as(output).isZero();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        return Files.readAllBytes(file);
    }

    /** As a database administrator could: with the triggers of this session switched off. */
    private static void bypassingTheGuard(String sql) {
        try (Connection connection = DB.connect(schema()); Statement statement = connection.createStatement()) {
            statement.execute("SET session_replication_role = replica");
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
