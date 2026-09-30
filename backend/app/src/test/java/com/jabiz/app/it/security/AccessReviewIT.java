package com.jabiz.app.it.security;

import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The access review (docs/design/10-security.md section 13.3, decision D28 item 9): the access report is issued with
 * REPORT_ISSUE as of the end of the period; the sign-off keeps a reference to it and its hash, the period's security
 * changes from the audit trail (count and hash), the conflicts, the reviewer and the comment. Signing needs its
 * permission and a recent second factor, a period that has ended, and the right report.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class AccessReviewIT extends SecurityItSupport {

    private static final String TEMPLATE = "jabiz.security.access_review";

    @SuppressWarnings("unchecked")
    private String issue(String templateId, Map<String, Object> params) {
        Map<String, Object> issued = post("/api/processes/REPORT_ISSUE/latest", bearer("report.issue",
            "security.access-review.read", "logistics.carrier.read"), Map.of("templateId", templateId,
            "params", params)).expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        return (String) ((Map<String, Object>) issued.get("output")).get("runId");
    }

    private static Map<String, Object> signOff(Instant from, Instant to, String runId, String comment) {
        return Map.of("periodFrom", from.toString(), "periodTo", to.toString(), "reportRunId", runId,
            "reviewComment", comment);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aReviewerSignsTheIssuedReportTheChangesAndTheConflictsOfAPeriod() {
        Instant from = clock.instant();
        clock.advance(Duration.ofMinutes(1));
        String userName = unique("reviewed");
        String userId = userWith(userName, "correct-horse-battery", "ledger.read");
        clock.advance(Duration.ofMinutes(1));
        Instant to = clock.instant();
        clock.advance(Duration.ofMinutes(1));
        // A change after the period is not part of it.
        userWith(unique("later"), "correct-horse-battery", "ledger.read");

        String runId = issue(TEMPLATE, Map.of("asOf", to.toString()));
        List<Map<String, Object>> rows = query("SELECT rows, content_hash FROM sys_report_run WHERE run_id = ?::uuid",
            runId);
        assertThat((String) rows.getFirst().get("rows")).contains(userName, "ledger.read");

        String reviewer = unique("reviewer");
        String read = TestTokens.bearer(tokens, reviewer, "security.access-review.read");
        Map<String, Object> changes = get("/api/security/access-reviews/changes?from=" + from + "&to=" + to, read)
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        List<Map<String, Object>> items = (List<Map<String, Object>>) changes.get("items");
        assertThat(items).extracting(item -> item.get("entityType"))
            .contains("SecUser", "SecRole", "SecRolePermission", "SecUserRole");
        assertThat(items).extracting(item -> item.get("entityId")).contains(userId);
        get("/api/security/access-reviews/conflicts", read).expectStatus().isOk();

        String signer = TestTokens.bearer(tokens, reviewer, "security.access-review.sign");
        Map<String, Object> signed = post("/api/processes/ACCESS_REVIEW_SIGN_OFF/latest", signer,
            signOff(from, to, runId, "Access as expected; no findings.")).expectStatus().isOk().expectBody(MAP)
            .returnResult().getResponseBody();
        Map<String, Object> output = (Map<String, Object>) signed.get("output");
        assertThat(output).containsEntry("reportHash", rows.getFirst().get("content_hash"))
            .containsEntry("changesCount", items.size()).containsEntry("changesHash", changes.get("hash"));

        List<Map<String, Object>> stored = query("SELECT * FROM sys_access_review WHERE review_id = ?::uuid",
            output.get("reviewId"));
        assertThat(stored).singleElement().satisfies(row -> assertThat(row)
            .containsEntry("reviewer", reviewer).containsEntry("review_comment", "Access as expected; no findings.")
            .containsEntry("report_hash", rows.getFirst().get("content_hash"))
            .containsEntry("changes_hash", changes.get("hash")));

        List<Map<String, Object>> reviews = get("/api/security/access-reviews", read).expectStatus().isOk()
            .expectBody(LIST).returnResult().getResponseBody();
        assertThat(reviews).extracting(review -> review.get("reviewId")).contains(output.get("reviewId"));

        assertThatThrownBy(() -> execute("UPDATE sys_access_review SET review_comment = 'x' WHERE reviewer = ?",
            reviewer)).hasMessageContaining("append-only table");
        assertThatThrownBy(() -> execute("DELETE FROM sys_access_review WHERE reviewer = ?", reviewer))
            .hasMessageContaining("append-only table");
    }

    @Test
    void signingNeedsItsPermissionASecondFactorAnEndedPeriodAndTheRightReport() {
        Instant from = clock.instant();
        clock.advance(Duration.ofMinutes(1));
        Instant to = clock.instant();
        String runId = issue(TEMPLATE, Map.of("asOf", to.toString()));
        clock.advance(Duration.ofMinutes(1));

        post("/api/processes/ACCESS_REVIEW_SIGN_OFF/latest", bearer("security.access-review.read"),
            signOff(from, to, runId, "ok")).expectStatus().isForbidden();
        post("/api/processes/ACCESS_REVIEW_SIGN_OFF/latest",
            TestTokens.withoutMfa(tokens, "no-mfa", "security.access-review.sign"), signOff(from, to, runId, "ok"))
            .expectStatus().isForbidden().expectBody(MAP)
            .value(body -> assertThat(body.toString()).contains("MFA_REQUIRED"));
        get("/api/security/access-reviews", bearer("security.access-review.sign")).expectStatus().isForbidden();

        String signer = bearer("security.access-review.sign");
        // The period has not ended yet.
        Map<String, Object> early = post("/api/processes/ACCESS_REVIEW_SIGN_OFF/latest", signer,
            signOff(from, clock.instant().plus(Duration.ofDays(1)), runId, "ok")).expectStatus().isEqualTo(422)
            .expectBody(MAP).returnResult().getResponseBody();
        assertThat(ruleCode(early)).isEqualTo("ACCESS_REVIEW_PERIOD");
        // The access report of another time, and another report.
        Map<String, Object> otherTime = post("/api/processes/ACCESS_REVIEW_SIGN_OFF/latest", signer,
            signOff(from.minusSeconds(60), from, runId, "ok")).expectStatus().isEqualTo(422).expectBody(MAP)
            .returnResult().getResponseBody();
        assertThat(ruleCode(otherTime)).isEqualTo("ACCESS_REVIEW_REPORT");
        String other = issue("it.carrier_accounts", Map.of("code", "NONE"));
        Map<String, Object> otherReport = post("/api/processes/ACCESS_REVIEW_SIGN_OFF/latest", signer,
            signOff(from, to, other, "ok")).expectStatus().isEqualTo(422).expectBody(MAP).returnResult()
            .getResponseBody();
        assertThat(ruleCode(otherReport)).isEqualTo("ACCESS_REVIEW_REPORT");
        // A comment is required.
        post("/api/processes/ACCESS_REVIEW_SIGN_OFF/latest", signer, signOff(from, to, runId, " "))
            .expectStatus().isBadRequest();

        post("/api/processes/ACCESS_REVIEW_SIGN_OFF/latest", signer, signOff(from, to, runId, "ok"))
            .expectStatus().isOk();
    }
}
