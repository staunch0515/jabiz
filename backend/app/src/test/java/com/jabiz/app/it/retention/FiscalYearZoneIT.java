package com.jabiz.app.it.retention;

import com.jabiz.app.it.fixture.ItRetentionFixtures;
import com.jabiz.app.it.security.SecurityItSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fiscal year's zone (decision D48 item 2; docs/design/21-audit-retention.md section 3.1): with
 * {@code jabiz.fiscal-year-zone=Asia/Tokyo} a record booked in the first nine hours of 1 January (Tokyo time), which
 * is still 31 December in UTC, belongs to the new fiscal year and is kept a year longer; one booked a second before
 * midnight in Tokyo belongs to the old one. {@code ItRecord} is kept seven years from the end of its fiscal year
 * (December); the clock stands at 2026-01-31, so the fiscal year 2018 has expired and 2019 has not.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = "jabiz.fiscal-year-zone=Asia/Tokyo")
class FiscalYearZoneIT extends SecurityItSupport {

    private static final String RECORDS = "/api/datasets/" + ItRetentionFixtures.RECORD_DATASET + "/commit";

    @Test
    void recordsBelongToTheFiscalYearOfTheirDayInTheZone() {
        // 2018-12-31 23:59:59 in Tokyo: the fiscal year 2018, expired.
        String lastSecond = record("2018-12-31T14:59:59Z");
        // 2019-01-01 00:00 and 08:59:59 in Tokyo (still 2018 in UTC): the fiscal year 2019, kept to 2026-12-31.
        String midnight = record("2018-12-31T15:00:00Z");
        String beforeNine = record("2018-12-31T23:59:59Z");

        delete(lastSecond).expectStatus().isOk();
        for (String kept : List.of(midnight, beforeNine)) {
            Map<String, Object> refused = delete(kept).expectStatus().isEqualTo(422).expectBody(MAP).returnResult()
                .getResponseBody();
            assertThat(ruleCode(refused)).isEqualTo("RETENTION_ACTIVE");
            assertThat(violations(refused).getFirst().get("message").toString()).contains("2026-12-31");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void theReportCountsTheExpiredByTheirDayInTheZone() {
        Map<String, Object> before = status();
        record("2018-12-31T14:59:59Z");
        record("2018-12-31T15:00:00Z");
        record("2018-12-31T23:59:59Z");

        Map<String, Object> after = status();
        assertThat(after).containsEntry("expiredThrough", "2018-12-31");
        assertThat(count(after, "entries") - count(before, "entries")).isEqualTo(3);
        assertThat(count(after, "expired") - count(before, "expired")).isEqualTo(1);
    }

    @Test
    void todayIsTheDayInTheZone() {
        // 2025-12-31 00:30 in Tokyo, still 2025-12-30 in UTC: a record of the fiscal year 2018 (kept until
        // 2025-12-31) may already go, which it could not in UTC.
        clock.set(Instant.parse("2025-12-30T15:30:00Z"));
        String expired = record("2018-06-30T00:00:00Z");
        Map<String, Object> report = get("/api/retention", bearer("retention.read")).expectStatus().isOk()
            .expectBody(MAP).returnResult().getResponseBody();
        assertThat(report).containsEntry("today", "2025-12-31");
        delete(expired).expectStatus().isOk();
    }

    // ================= helpers =================

    private String record(String bookedTime) {
        String id = unique("Z");
        Map<String, Object> attributes = new HashMap<>(Map.of("recordId", id, "title", "t", "vendor", "ZONE"));
        attributes.put("bookedTime", bookedTime);
        post(RECORDS, admin(), Map.of("changes", List.of(Map.of("action", "INSERT", "attributes", attributes))))
            .expectStatus().isOk();
        return id;
    }

    private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec delete(String id) {
        return post(RECORDS, admin(), Map.of("changes", List.of(Map.of("action", "DELETE", "id", id, "version", 1))));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> status() {
        Map<String, Object> report = get("/api/retention", bearer("retention.read")).expectStatus().isOk()
            .expectBody(MAP).returnResult().getResponseBody();
        return ((List<Map<String, Object>>) report.get("policies")).stream()
            .filter(p -> "ItRecord".equals(p.get("entity"))).findFirst().orElseThrow();
    }

    private static long count(Map<String, Object> status, String name) {
        return ((Number) status.get(name)).longValue();
    }
}
