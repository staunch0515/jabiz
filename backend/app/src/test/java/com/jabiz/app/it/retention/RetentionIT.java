package com.jabiz.app.it.retention;

import com.jabiz.app.it.fixture.ItRetentionFixtures;
import com.jabiz.app.it.security.SecurityItSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Retention and legal holds (docs/design/21-audit-retention.md section 3; ROADMAP phase 14f-3): entries within their
 * retention period or under a hold in force cannot be deleted - plain rows nor temporal tombstones -, holds are
 * placed and released only by their processes, and the report counts what is past its retention. The clock stands
 * at 2026-01-31; the fiscal year ends in December.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class RetentionIT extends SecurityItSupport {

    private static final String RECORDS = "/api/datasets/" + ItRetentionFixtures.RECORD_DATASET + "/commit";
    private static final String DOCUMENTS = "/api/datasets/" + ItRetentionFixtures.DOCUMENT_DATASET + "/commit";

    @Test
    void entriesWithinTheirRetentionCannotBeDeleted() {
        // Booked in 2010: kept to 2017-12-31, long past. Booked now: kept to the end of 2033.
        String old = record("V1", "2010-05-01T00:00:00Z");
        String recent = record("V1", "2026-01-10T00:00:00Z");
        String undated = record("V1", null);

        delete(RECORDS, old, 1).expectStatus().isOk();
        Map<String, Object> refused = delete(RECORDS, recent, 1).expectStatus().isEqualTo(422).expectBody(MAP)
            .returnResult().getResponseBody();
        assertThat(ruleCode(refused)).isEqualTo("RETENTION_ACTIVE");
        assertThat(violations(refused).getFirst().get("message").toString()).contains("2033-12-31");
        // Without its date an entry is kept.
        assertThat(ruleCode(delete(RECORDS, undated, 1).expectStatus().isEqualTo(422).expectBody(MAP)
            .returnResult().getResponseBody())).isEqualTo("RETENTION_ACTIVE");

        // A temporal entry's tombstone likewise: issued two years ago it may go, issued last month it stays.
        String gone = document("V1", "2024-01-01T00:00:00Z");
        String kept = document("V1", "2025-12-20T00:00:00Z");
        delete(DOCUMENTS, gone, 1).expectStatus().isOk();
        assertThat(ruleCode(delete(DOCUMENTS, kept, 1).expectStatus().isEqualTo(422).expectBody(MAP)
            .returnResult().getResponseBody())).isEqualTo("RETENTION_ACTIVE");
        // Once the year is up, it may go.
        clock.advance(Duration.ofDays(366));
        delete(DOCUMENTS, kept, 1).expectStatus().isOk();
    }

    @Test
    void aLegalHoldKeepsWhatItNamesUntilItIsReleased() {
        String byId = record("V10", "2010-01-01T00:00:00Z");
        String byVendor = document("HOLD-V200", "2020-01-01T00:00:00Z");
        String other = document("HOLD-V201", "2020-01-01T00:00:00Z");
        String idHold = place(Map.of("name", "audit 2010", "reason", "tax audit", "entityType", "ItRecord",
            "ids", List.of(byId)));
        String vendorHold = place(Map.of("name", "vendor dispute", "reason", "claim by V200",
            "entityType", "ItDocument", "field", "vendor", "value", "HOLD-V200"));

        Map<String, Object> refused = delete(RECORDS, byId, 1).expectStatus().isEqualTo(422).expectBody(MAP)
            .returnResult().getResponseBody();
        assertThat(ruleCode(refused)).isEqualTo("LEGAL_HOLD");
        assertThat(violations(refused).getFirst().get("message").toString()).contains("audit 2010");
        assertThat(ruleCode(delete(DOCUMENTS, byVendor, 1).expectStatus().isEqualTo(422).expectBody(MAP)
            .returnResult().getResponseBody())).isEqualTo("LEGAL_HOLD");
        delete(DOCUMENTS, other, 1).expectStatus().isOk();

        release(idHold, "audit closed").expectStatus().isOk();
        release(vendorHold, "settled").expectStatus().isOk();
        delete(RECORDS, byId, 1).expectStatus().isOk();
        delete(DOCUMENTS, byVendor, 1).expectStatus().isOk();

        // Released once; the history keeps who placed and released it, and why.
        assertThat(ruleCode(release(idHold, "again").expectStatus().isEqualTo(422).expectBody(MAP).returnResult()
            .getResponseBody())).isEqualTo("LEGAL_HOLD_NOT_ACTIVE");
        List<Map<String, Object>> versions = query("SELECT status, reason, release_reason FROM sys_legal_hold_version"
            + " WHERE hold_id = ?::uuid ORDER BY version_no", idHold);
        assertThat(versions).extracting(v -> v.get("status")).containsExactly("ACTIVE", "RELEASED");
        assertThat(versions.getLast()).containsEntry("reason", "tax audit").containsEntry("release_reason",
            "audit closed");
    }

    @Test
    void holdsAreValidatedAndNeedTheirPermission() {
        String keeper = bearer("legal.hold.write");
        List<Map<String, Object>> problems = violations(post("/api/processes/LEGAL_HOLD_PLACE/latest", keeper,
            Map.of("name", "x", "reason", "y", "entityType", "NoSuchEntity", "ids", List.of("1"), "field", "f",
                "value", "v")).expectStatus().isBadRequest().expectBody(MAP).returnResult().getResponseBody());
        assertThat(problems).extracting(p -> p.get("field")).contains("entityType", "ids");
        assertThat(violations(post("/api/processes/LEGAL_HOLD_PLACE/latest", keeper, Map.of("name", "x",
            "reason", "y", "entityType", "ItRecord", "field", "nothing", "value", "v")).expectStatus().isBadRequest()
            .expectBody(MAP).returnResult().getResponseBody())).extracting(p -> p.get("field")).contains("field");

        post("/api/processes/LEGAL_HOLD_PLACE/latest", bearer("legal.hold.read"), Map.of("name", "x", "reason", "y",
            "entityType", "ItRecord", "ids", List.of("1"))).expectStatus().isForbidden();
        // Holds are written by their processes only.
        post("/api/datasets/urn:jabiz:dataset:platform:SysLegalHold/commit", admin(), Map.of("changes", List.of(
            Map.of("action", "INSERT", "attributes", Map.of("name", "x", "reason", "y", "entityType", "ItRecord",
                "status", "ACTIVE"))))).expectStatus().is4xxClientError();
        get("/api/retention", bearer("legal.hold.read")).expectStatus().isForbidden();
    }

    @Test
    @SuppressWarnings("unchecked")
    void theReportCountsWhatIsPastItsRetentionAndWhatIsHeld() {
        Map<String, Object> before = status("ItRecord");
        String expiredHeld = record("REPORT-A", "2012-03-01T00:00:00Z");
        record("REPORT-B", "2015-07-01T00:00:00Z");
        record("REPORT-C", "2025-07-01T00:00:00Z");
        place(Map.of("name", "report", "reason", "r", "entityType", "ItRecord", "ids", List.of(expiredHeld)));

        Map<String, Object> after = status("ItRecord");
        assertThat(count(after, "entries") - count(before, "entries")).isEqualTo(3);
        assertThat(count(after, "expired") - count(before, "expired")).isEqualTo(2);
        assertThat(count(after, "held") - count(before, "held")).isEqualTo(1);
        // Today 2026-01-31: entries of the fiscal year 2018 and before are past seven years.
        assertThat(after).containsEntry("expiredThrough", "2018-12-31").containsEntry("keep", "P7Y")
            .containsEntry("fromFiscalYearEnd", true);
        Map<String, Object> report = get("/api/retention", bearer("retention.read")).expectStatus().isOk()
            .expectBody(MAP).returnResult().getResponseBody();
        assertThat(report).containsEntry("today", "2026-01-31").containsEntry("fiscalYearEnd", 12);
        assertThat((List<Map<String, Object>>) report.get("policies")).extracting(p -> p.get("entity"))
            .contains("ItRecord", "ItDocument");
    }

    // ================= helpers =================

    private String record(String vendor, String bookedTime) {
        String id = unique("R");
        Map<String, Object> attributes = new HashMap<>(Map.of("recordId", id, "title", "t", "vendor", vendor));
        attributes.put("bookedTime", bookedTime);
        post(RECORDS, admin(), Map.of("changes", List.of(Map.of("action", "INSERT", "attributes", attributes))))
            .expectStatus().isOk();
        return id;
    }

    @SuppressWarnings("unchecked")
    private String document(String vendor, String issuedTime) {
        List<Map<String, Object>> saved = post(DOCUMENTS, admin(), Map.of("changes", List.of(Map.of("action",
            "INSERT", "attributes", Map.of("title", "d", "vendor", vendor, "issuedTime", issuedTime)))))
            .expectStatus().isOk().expectBody(LIST).returnResult().getResponseBody();
        return String.valueOf(saved.getFirst().get("id"));
    }

    private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec delete(String commit, String id,
        long version) {
        return post(commit, admin(), Map.of("changes", List.of(Map.of("action", "DELETE", "id", id,
            "version", version))));
    }

    @SuppressWarnings("unchecked")
    private String place(Map<String, Object> input) {
        Map<String, Object> result = post("/api/processes/LEGAL_HOLD_PLACE/latest", bearer("legal.hold.write"), input)
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        return (String) ((Map<String, Object>) result.get("output")).get("holdId");
    }

    private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec release(String holdId,
        String reason) {
        return post("/api/processes/LEGAL_HOLD_RELEASE/latest", bearer("legal.hold.write"),
            new LinkedHashMap<>(Map.of("holdId", holdId, "reason", reason)));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> status(String entity) {
        Map<String, Object> report = get("/api/retention", bearer("retention.read")).expectStatus().isOk()
            .expectBody(MAP).returnResult().getResponseBody();
        return ((List<Map<String, Object>>) report.get("policies")).stream()
            .filter(p -> entity.equals(p.get("entity"))).findFirst().orElseThrow();
    }

    private static long count(Map<String, Object> status, String name) {
        return ((Number) status.get(name)).longValue();
    }
}
