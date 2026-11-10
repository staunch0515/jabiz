package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.certification.CertificateProcesses;
import com.jabiz.quizbuks.certification.QbCertificates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Erasing an account erases its certificates of nodes (F-S5a, docs/quizbuks/plans/impl/F-S5.md §2.4; platform decision
 * D62): the rows and the PDFs issued of them go, the verify page then finds no such certificate and shows nothing of the
 * holder; the lit records stay holding ids only. A revoked or unshared certificate shows the same after.
 */
class CertificateErasureIT extends NodeCertificateItSupport {

    @DynamicPropertySource
    static void maintenance(DynamicPropertyRegistry registry) {
        // The tests' login is the maintenance login too (a member of jabiz_maintenance, granted below), for the purge.
        registry.add("jabiz.erasure.datasource.url", () -> DB.r2dbcUrl() + "?schema=" + schema());
        registry.add("jabiz.erasure.datasource.username", DB::username);
        registry.add("jabiz.erasure.datasource.password", DB::password);
    }

    @BeforeEach
    void maintenanceRole() {
        execute("DO $$ BEGIN CREATE ROLE jabiz_maintenance; EXCEPTION WHEN duplicate_object THEN NULL; END $$");
        execute("DO $$ BEGIN EXECUTE 'GRANT jabiz_maintenance TO ' || quote_ident(current_user);"
            + " EXCEPTION WHEN OTHERS THEN NULL; END $$");
    }

    /** Every text or JSON column of the schema that holds the value, as {@code table.column}. */
    private static List<String> occurrences(String needle) {
        List<String> found = new ArrayList<>();
        for (Map<String, Object> column : query("SELECT table_name::text AS t, column_name::text AS c"
            + " FROM information_schema.columns WHERE table_schema = current_schema()"
            + " AND data_type IN ('text', 'character varying', 'character', 'jsonb', 'json')")) {
            String sql = "SELECT count(*) AS n FROM \"" + column.get("t") + "\" WHERE \"" + column.get("c")
                + "\"::text ILIKE ?";
            if (((Number) query(sql, "%" + needle + "%").getFirst().get("n")).longValue() > 0) {
                found.add(column.get("t") + "." + column.get("c"));
            }
        }
        return found;
    }

    @Test
    void anErasedTakersCertificatesAndTheirPdfsGoAndTheVerifyPageFindsNone() {
        Gate gate = gate(names("Erasable"));
        Gate revokedGate = gate(names("Erasable too"));
        String cat = newTaker("fs5a-erase-cat-" + UUID.randomUUID().toString().substring(0, 8), "JP");
        String name = "Cat FS5a " + UUID.randomUUID().toString().substring(0, 8);
        String handle = "cat-" + UUID.randomUUID().toString().substring(0, 8);
        run("QB_PROFILE_SAVE", taker(cat), body("displayName", name, "countryCode", "JP", "preferredCurrency", "JPY"));
        light(gate, cat);
        clock.set(clock.instant().plus(java.time.Duration.ofMinutes(1)));
        light(revokedGate, cat);
        run("QB_SETTINGS_SAVE", taker(cat), body("publicHandle", handle, "publicPage", true));
        List<Map<String, Object>> rows = certificateRows(cat);
        assertThat(rows).hasSize(2).allSatisfy(row -> assertThat(row).containsEntry("holder_name", name));
        String shown = (String) rows.get(0).get("verify_key");
        String revoked = (String) rows.get(1).get("verify_key");
        Map<String, Object> issued = issue(CertificateProcesses.ISSUE, taker(cat), rows.get(0).get("certificate_id"),
            "en");
        revoke(rows.get(1).get("lit_id"));
        assertThat(verify(shown)).containsEntry("holderName", name);
        assertThat(verify(revoked).get("revokedAt")).isNotNull();
        assertThat(publicItems("qb.public.taker-page", "p.handle=" + handle)).hasSize(1);
        Object litId = rows.get(0).get("lit_id");

        runAsSystem("ACCOUNT_ERASE", Map.of("userId", cat, "requestedBy", "it-support"));
        deliverEvents();

        // The rows are gone, the pages find nothing: no such certificate (not even a revoked or unshared one), and nothing
        // of the holder on the public certification page.
        assertThat(query("SELECT 1 FROM qb_certificate WHERE user_id = ?", cat)).isEmpty();
        assertThat(verify(shown)).isNull();
        assertThat(verify(revoked)).isNull();
        assertThat(publicItems("qb.public.taker-page", "p.handle=" + handle)).isEmpty();
        // The PDF is emptied, and her name, her page name are found nowhere.
        assertThat(query("SELECT octet_length(pdf) AS size FROM sys_document_run WHERE run_id = ?::uuid",
            String.valueOf(issued.get("runId")))).singleElement()
            .satisfies(row -> assertThat(((Number) row.get("size")).longValue()).isZero());
        for (String needle : List.of(name, handle)) {
            assertThat(occurrences(needle)).as("where %s still is", needle).isEmpty();
        }
        // The keys lead to nobody: only the document's number and its issued event may still hold one.
        for (String key : List.of(shown, revoked)) {
            assertThat(occurrences(key)).allMatch(where -> where.equals("sys_document_run.document_no")
                || where.equals("sys_outbox_event.payload"));
        }
        // The lit record stays, holding ids only: the nodes' counts rest on it.
        assertThat(query("SELECT 1 FROM qb_tree_lit_version WHERE lit_id = ?::uuid", String.valueOf(litId))).hasSize(1);
        // And the job makes none again from the lit records that stay (see below).
        runRetry();
        assertThat(query("SELECT 1 FROM qb_certificate WHERE user_id = ?", cat)).isEmpty();
    }

    @Test
    void anErasedSponsorsCertificateAndItsPdfGoToo() {
        Map<String, Object> start = node(null, "start", "Getting started", all(passed()));
        start.put("certificateNames", names("Getting started"));
        Copy copy = copy(List.of(start, node(null, "next", "Running a quiz", all(lit("start", true), passed()))));
        String sponsor = sponsorOf(copy.operator(), "s-fs5a-erase");
        pass(sponsor, liveBound(copy.operator().leadId(), unique("FS5a erase"), copy.treeId(), copy.start(),
            Map.of("tiers", List.of(body("fromScore", 0, "toScore", 5, "reward", 0)))));
        Map<String, Object> row = only(certificateRows(sponsor));
        String key = (String) row.get("verify_key");
        String company = (String) row.get("holder_name");
        Map<String, Object> issued = issue(CertificateProcesses.SPONSOR_ISSUE, sponsorToken(sponsor),
            row.get("certificate_id"), "en");
        assertThat(verify(key)).containsEntry("holderName", company);
        assertThat(query("SELECT 1 FROM sys_document_run WHERE layout_id = ?", QbCertificates.NODE_SPONSOR_LAYOUT_ID))
            .isNotEmpty();

        runAsSystem("ACCOUNT_ERASE", Map.of("userId", sponsor, "requestedBy", "it-support"));
        deliverEvents();

        assertThat(query("SELECT 1 FROM qb_certificate WHERE user_id = ?", sponsor)).isEmpty();
        assertThat(verify(key)).isNull();
        assertThat(query("SELECT octet_length(pdf) AS size FROM sys_document_run WHERE run_id = ?::uuid",
            String.valueOf(issued.get("runId")))).singleElement()
            .satisfies(r -> assertThat(((Number) r.get("size")).longValue()).isZero());
        // (the company name is the onboarding's, found where the approvals and tasks of it are; not in a certificate's)
        assertThat(occurrences(company)).as("where the company still is")
            .noneMatch(where -> where.startsWith("sys_document_run.") || where.startsWith("qb_certificate."));
    }

    @Test
    void theJobWritesNoCertificateAgainForAnErasedPerson() {
        // A lit record of an erased person is not revoked, and the certificate it asked for is gone: it must not come
        // back with a name from nowhere.
        Gate gate = gate(names("Not again"));
        String dan = newTaker("fs5a-erase-dan-" + UUID.randomUUID().toString().substring(0, 8), "JP");
        light(gate, dan);
        String key = (String) only(certificateRows(dan)).get("verify_key");
        runAsSystem("ACCOUNT_ERASE", Map.of("userId", dan, "requestedBy", "it-support"));
        deliverEvents();
        runRetry();
        assertThat(query("SELECT 1 FROM qb_certificate WHERE user_id = ?", dan)).isEmpty();
        assertThat(verify(key)).isNull();
        // The check of the maps does not count it missing either.
        assertThat(items("qb.admin.tree-health", com.jabiz.runtime.test.TestTokens.bearer(tokens, "fs5a-erase-health",
            com.jabiz.quizbuks.QbPermissions.TREES_READ), Map.of("params", Map.of("at", clock.instant().toString()))))
            .extracting(row -> row.get("checkCode")).doesNotContain("CERTIFICATE_MISSING");
        assertThat(items("qb.system.tree-certificates-due", com.jabiz.runtime.test.TestTokens.bearer(tokens,
            "fs5a-erase-items", com.jabiz.quizbuks.QbPermissions.CERTIFICATE_SYSTEM), Map.of("limit", 500)))
            .extracting(row -> row.get("subjectId")).doesNotContain(dan);
    }

    @Test
    void aCertificateWrittenAfterTheErasureIsReportedAndRemovedByTheJob() {
        // The race: a settlement read before ACCOUNT_ERASE (which takes no lock of ours) and inserted after it. Simulated
        // by writing the row of an erased person as that settlement would have.
        Gate gate = gate(names("Raced"));
        String eve = newTaker("fs5a-race-eve-" + UUID.randomUUID().toString().substring(0, 8), "JP");
        light(gate, eve);
        Map<String, Object> row = only(certificateRows(eve));
        runAsSystem("ACCOUNT_ERASE", Map.of("userId", eve, "requestedBy", "it-support"));
        deliverEvents();
        assertThat(certificateRows(eve)).isEmpty();
        String key = java.util.HexFormat.of().formatHex(java.nio.ByteBuffer.allocate(16)
            .putLong(UUID.randomUUID().getMostSignificantBits()).putLong(UUID.randomUUID().getLeastSignificantBits())
            .array());
        execute("INSERT INTO qb_certificate (certificate_id, user_id, held_from, holder_name, verify_key, issued_at, "
            + "source, subject_kind, lit_id, tree_id, node_id, certificate_names, issuer_name, shared, version) "
            + "VALUES (?, ?, now(), 'Late Name', ?, now(), 'NODE', 'USER', ?, ?, ?, '{\"en\":\"Raced\"}'::jsonb, "
            + "'Issuer', true, 1)", UUID.randomUUID().toString(), eve, key, UUID.randomUUID().toString(),
            row.get("tree_id"), row.get("node_id"));
        assertThat(verify(key)).isNotNull();
        String token = com.jabiz.runtime.test.TestTokens.bearer(tokens, "fs5a-race", 
            com.jabiz.quizbuks.QbPermissions.TREES_READ, com.jabiz.quizbuks.QbPermissions.CERTIFICATE_SYSTEM);
        Map<String, Object> at = Map.of("params", Map.of("at", clock.instant().toString()));
        assertThat(items("qb.admin.tree-health", token, at)).extracting(r -> r.get("checkCode"))
            .contains("CERTIFICATE_OF_ERASED");
        assertThat(items("qb.system.tree-certificates-due", token, Map.of("limit", 500)))
            .extracting(r -> r.get("subjectId")).contains(eve);

        runRetry();
        assertThat(query("SELECT 1 FROM qb_certificate WHERE user_id = ?", eve)).isEmpty();
        assertThat(verify(key)).isNull();
        // (a day later: a token of its own)
        String later = com.jabiz.runtime.test.TestTokens.bearer(tokens, "fs5a-race-later",
            com.jabiz.quizbuks.QbPermissions.TREES_READ);
        assertThat(items("qb.admin.tree-health", later, Map.of("params", Map.of("at", clock.instant().toString()))))
            .extracting(r -> r.get("checkCode")).doesNotContain("CERTIFICATE_OF_ERASED");
        assertThat(occurrences("Late Name")).isEmpty();
    }
}
