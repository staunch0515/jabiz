package com.jabiz.app.it.ledger;

import com.jabiz.ledger.Direction;
import com.jabiz.runtime.ledger.LedgerEntities;
import com.jabiz.runtime.report.ReportProcesses;
import com.jabiz.runtime.security.SecurityEntities;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The open-format export (docs/design/21-audit-retention.md section 4; ROADMAP phase 14f-3, CT-021): a ZIP readable
 * without the system - the trial balance recomputed from its CSV equals the system's -, every file listed in the
 * manifest with its hash and rows, secrets left out, and only what the caller may read.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class DataExportIT extends LedgerItSupport {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    @SuppressWarnings("unchecked")
    void theTrialBalanceRecomputedFromTheExportEqualsTheSystems() throws IOException {
        String prefix = prefix();
        openAccounts(prefix, "1100", "1200", "4100");
        post(prefix, "1100", Direction.DEBIT, "100", "4100", Direction.CREDIT, "100");
        post(prefix, "1200", Direction.DEBIT, "12", "4100", Direction.CREDIT, "12");
        String reversed = post(prefix, "1100", Direction.DEBIT, "7", "4100", Direction.CREDIT, "7")
            .transactionId();
        reverse(reversed);
        ReportProcesses.IssueOutput issued = run(ReportProcesses.ISSUE, new ReportProcesses.IssueInput(
            "jabiz.ledger.account_balances", Map.of("asOf", clock.instant().plus(Duration.ofDays(1)).toString()),
            null, null, null));
        clock.advance(Duration.ofMinutes(1));

        Map<String, byte[]> files = export(bearer("data.export", "ledger.read", "ledger.account.read",
            "report.archive.read"), Map.of("datasets", List.of(LedgerEntities.ACCOUNT_DATASET,
                LedgerEntities.ENTRY_DATASET), "reports", true,
            "reportsFrom", clock.instant().minus(Duration.ofHours(1)).toString()));

        // Every file is in the manifest with its hash, and every CSV with its rows.
        Map<String, Object> manifest = JSON.readValue(files.get("manifest.json"), new TypeReference<>() {});
        List<Map<String, Object>> listed = (List<Map<String, Object>>) manifest.get("files");
        assertThat(listed).extracting(f -> f.get("path")).containsExactlyInAnyOrderElementsOf(
            files.keySet().stream().filter(name -> !name.equals("manifest.json")).toList());
        for (Map<String, Object> file : listed) {
            byte[] content = files.get((String) file.get("path"));
            assertThat(sha256(content)).as("hash of %s", file.get("path")).isEqualTo(file.get("sha256"));
            if (file.containsKey("rows")) {
                assertThat(csv(content)).hasSize(((Number) file.get("rows")).intValue() + 1);
            }
        }
        assertThat(manifest).containsEntry("exportedBy", "it-accountant").containsKey("platformVersion");
        assertThat((Map<String, Object>) manifest.get("parameters")).containsEntry("reports", true);

        // The trial balance, from the files alone.
        List<List<String>> accounts = csv(files.get("data/urn_jabiz_dataset_platform_LedgerAccount.csv"));
        List<List<String>> entries = csv(files.get("data/urn_jabiz_dataset_platform_LedgerEntry.csv"));
        Map<String, String> codeById = new HashMap<>();
        int accountId = accounts.getFirst().indexOf("accountId");
        int accountCode = accounts.getFirst().indexOf("accountCode");
        for (List<String> row : accounts.subList(1, accounts.size())) {
            codeById.put(row.get(accountId), row.get(accountCode));
        }
        List<String> header = entries.getFirst();
        Map<String, BigDecimal> recomputed = new LinkedHashMap<>();
        for (List<String> row : entries.subList(1, entries.size())) {
            String code = codeById.get(row.get(header.indexOf("accountId")));
            if (code == null || !code.startsWith(prefix)) {
                continue;
            }
            BigDecimal amount = new BigDecimal(row.get(header.indexOf("amount")));
            boolean debit = "DEBIT".equals(row.get(header.indexOf("direction")));
            // The system reports codes without the test prefix.
            recomputed.merge(code.substring(prefix.length()), debit ? amount : amount.negate(), BigDecimal::add);
        }
        Map<String, BigDecimal> system = balances(prefix, clock.instant());
        assertThat(recomputed.keySet()).containsExactlyInAnyOrderElementsOf(system.keySet());
        system.forEach((code, balance) -> assertThat(recomputed.get(code)).as(code).isEqualByComparingTo(balance));

        // The columns are described, the issued report is there as issued.
        Map<String, Object> schema = JSON.readValue(files.get("schema.json"), new TypeReference<>() {});
        Map<String, Object> entry = ((List<Map<String, Object>>) schema.get("entities")).stream()
            .filter(e -> LedgerEntities.ENTRY.equals(e.get("entity"))).findFirst().orElseThrow();
        assertThat((List<Map<String, Object>>) entry.get("columns")).extracting(c -> c.get("name"))
            .containsAll(List.of("accountId", "direction", "amount"));
        assertThat(files.keySet()).anyMatch(name -> name.startsWith("reports/" + issued.runId())
            && name.endsWith(".pdf"));
        byte[] pdf = files.entrySet().stream().filter(e -> e.getKey().startsWith("reports/")).findFirst()
            .orElseThrow().getValue();
        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");

        // Who exported what is an operation.
        assertThat(query("SELECT actor_id, input_summary::text AS summary FROM op_process"
            + " WHERE process_name = 'DATA_EXPORT'")).anySatisfy(row -> {
                assertThat(row).containsEntry("actor_id", "it-accountant");
                assertThat((String) row.get("summary")).contains(LedgerEntities.ENTRY_DATASET);
            });
    }

    @Test
    void secretsStayOutAndOnlyWhatTheCallerMayReadGoesIn() throws IOException {
        Map<String, byte[]> files = export(TestTokens.bearer(tokens, "it-admin", "*"),
            Map.of("datasets", List.of(SecurityEntities.USER_DATASET)));
        List<List<String>> users = csv(files.get("data/urn_jabiz_dataset_platform_SecUser.csv"));
        assertThat(users.getFirst()).contains("userName").doesNotContain("passwordHash");
        assertThat(new String(files.get("schema.json"), StandardCharsets.UTF_8)).doesNotContain("passwordHash");

        Map<String, Object> ledger = Map.of("datasets", List.of(LedgerEntities.ENTRY_DATASET));
        request(bearer("ledger.read"), ledger).expectStatus().isForbidden();
        request(bearer("data.export"), ledger).expectStatus().isForbidden();
        request(bearer("data.export", "ledger.read"), Map.of("datasets", List.of(LedgerEntities.ENTRY_DATASET),
            "reports", true)).expectStatus().isForbidden();
        request(bearer("data.export"), Map.of("datasets", List.of())).expectStatus().isBadRequest();
        request(bearer("data.export"), Map.of("datasets", List.of("urn:jabiz:dataset:none"))).expectStatus()
            .isNotFound();
    }

    // ================= helpers =================

    private WebTestClient.ResponseSpec request(String authorization, Map<String, Object> body) {
        return client().post().uri("/api/exports/data").header(HttpHeaders.AUTHORIZATION, authorization)
            .contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
    }

    private Map<String, byte[]> export(String authorization, Map<String, Object> body) throws IOException {
        byte[] zip = request(authorization, body).expectStatus().isOk()
            .expectHeader().contentType("application/zip")
            .expectHeader().value(HttpHeaders.CONTENT_DISPOSITION, disposition -> assertThat(disposition)
                .contains("attachment").contains(".zip"))
            .expectBody(byte[].class).returnResult().getResponseBody();
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                files.put(entry.getName(), in.readAllBytes());
            }
        }
        return files;
    }

    /** RFC 4180, as far as the export writes it. */
    private static List<List<String>> csv(byte[] content) {
        String text = new String(content, StandardCharsets.UTF_8);
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                row.add(cell.toString());
                cell.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
                i++;
            } else {
                cell.append(c);
            }
        }
        return rows;
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
