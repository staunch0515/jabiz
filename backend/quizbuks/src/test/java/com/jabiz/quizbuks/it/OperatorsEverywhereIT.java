package com.jabiz.quizbuks.it;

import com.jabiz.job.JobDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.operator.OperatorCodes;
import com.jabiz.quizbuks.operator.OperatorHealth;
import com.jabiz.quizbuks.operator.OperatorProcesses;
import com.jabiz.quizbuks.operator.QbOperators;
import com.jabiz.quizbuks.report.QbReports;
import com.jabiz.quizbuks.report.ReportProcesses;
import com.jabiz.runtime.job.JobRunner;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-S1 (docs/quizbuks/first-launch-spec.md, docs/quizbuks/plans/impl/F-S1.md): every sponsor belongs to an operator;
 * an operator's first lead is its own sponsor, whose publications another member reviews; a direct sponsor is never
 * moved, and a moved sponsor's tasks go with them; the daily check finds what is wrong.
 */
// The sponsor entry takes registrations (the lead joins it to become their operator's sponsor), as in production.
@org.springframework.test.context.TestPropertySource(properties = "jabiz.security.entries.sponsor.self-registration=true")
class OperatorsEverywhereIT extends PlayItSupport {

    @Autowired
    JobRunner jobRunner;

    @Autowired
    @Qualifier("qbOperatorCheckJob")
    JobDefinition<OperatorHealth.RunInput> checkJob;

    private String hq() {
        return TestTokens.bearer(tokens, "fs1-hq", QbPermissions.OPERATORS_WRITE, QbPermissions.OPERATORS_READ);
    }

    private Map<String, Object> assignment(String userId) {
        return query("SELECT operator_id, assigned_by, fixed FROM qb_sponsor_operator WHERE user_id = ?", userId)
            .getFirst();
    }

    /** A submitted publication of the sponsor's waiting for a review; returns its approval request. */
    private String pendingReview(String sponsorId, String name) {
        autoApprove(false);
        try {
            String token = sponsorToken(sponsorId);
            String versionId = version(token, name + " quiz");
            Instant now = clock.instant();
            String publication = (String) run("QB_PUBLICATION_SAVE", token, complete(versionId, name, now,
                now.plus(Duration.ofDays(7)))).get("publicationId");
            Map<String, Object> submitted = run("QB_PUBLICATION_SUBMIT", token, body("publicationId", publication));
            assertThat(submitted.get("status")).isEqualTo("PENDING_REVIEW");
            return String.valueOf(query("SELECT approval_request_id FROM qb_publication_version"
                + " WHERE publication_id = ?::uuid ORDER BY version_no DESC LIMIT 1", publication).getFirst()
                .get("approval_request_id"));
        } finally {
            autoApprove(true);
        }
    }

    private List<Map<String, Object>> openTasks(String sourceKey) {
        return query("SELECT * FROM (SELECT DISTINCT ON (task_id) * FROM sys_task_version ORDER BY task_id, "
            + "version_no DESC) t WHERE source_key = ? AND status = 'OPEN' AND NOT is_deleted", sourceKey);
    }

    @Test
    void aSponsorWhoAppliesOnTheirOwnBelongsToTheDefaultOperatorAndTheirCardsCarryIt() {
        String sponsor = newSponsor(unique("s-fs1-own"));
        assertThat(assignment(sponsor)).containsEntry("operator_id", platformOperator())
            .containsEntry("assigned_by", QbOperators.BY_DEFAULT).containsEntry("fixed", false);
        credit(sponsor, 10_000);
        String publication = livePublication(sponsor, "FS1 card");
        assertThat(card(publication).get("operator_id")).isEqualTo(platformOperator());
        // The default operator is the only one that takes new sponsors.
        assertThat(query("SELECT count(*) AS n FROM qb_operator WHERE default_for_new").getFirst().get("n"))
            .isEqualTo(1L);
    }

    @Test
    void anOperatorsLeadIsItsOwnSponsorWhosePublicationsAnotherMemberReviews() {
        NewOperator operator = newOperatorWithLead(hq(), unique("Kanto"), null);
        String lead = operator.leadId();
        assertThat(query("SELECT direct_sponsor_id FROM qb_operator WHERE operator_id = ?", operator.operatorId())
            .getFirst()).containsEntry("direct_sponsor_id", lead);
        assertThat(assignment(lead)).containsEntry("operator_id", operator.operatorId())
            .containsEntry("assigned_by", QbOperators.BY_DIRECT).containsEntry("fixed", true);
        assertThat(latest("qb_sponsor_version", "onboarding_id", "user_id = '" + lead + "'")).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("review_status", "APPROVED"));
        assertThat(query("SELECT lead FROM qb_operator_member WHERE user_id = ?::uuid", lead)).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("lead", true));

        // The lead gets the sponsor role by joining the sponsor entry themself (the platform's join).
        client.post().uri("/api/auth/account/entries/sponsor/join")
            .header(HttpHeaders.AUTHORIZATION, taker(lead)).exchange().expectStatus().isNoContent();

        // Another member of the operator.
        String other = verifiedUser(unique("fs1-other"));
        run(OperatorProcesses.MEMBER_ADD, hq(), body("operatorId", operator.operatorId(), "userId", other,
            "lead", false));

        credit(lead, 10_000);
        String requestId = pendingReview(lead, "FS1 direct");
        assertThat(query("SELECT scope_name, scope_value FROM sys_approval_request_version WHERE request_id = ?::uuid",
            requestId).getFirst()).containsEntry("scope_name", QbOperators.SCOPE)
            .containsEntry("scope_value", operator.operatorId());
        // The lead prepared it: they do not decide it; another member does.
        assertThat(codes(refused("APPROVAL_DECIDE", memberSession(lead, operator.operatorId()), body("requestId",
            requestId, "decision", "APPROVE"), 422))).containsExactly("APPROVAL_OWN_REQUEST");
        run("APPROVAL_DECIDE", memberSession(other, operator.operatorId()), body("requestId", requestId,
            "decision", "APPROVE"));
        deliverEvents();
        assertThat(query("SELECT status FROM qb_publication_version WHERE approval_request_id = ?"
            + " ORDER BY version_no DESC LIMIT 1", requestId).getFirst().get("status")).isIn("LIVE", "SCHEDULED");

        // A lead named to an operator that has its own sponsor already is only a lead.
        String second = verifiedUser(unique("fs1-second-lead"));
        run(OperatorProcesses.MEMBER_ADD, hq(), body("operatorId", operator.operatorId(), "userId", second,
            "lead", true));
        assertThat(query("SELECT 1 FROM qb_sponsor_operator WHERE user_id = ?", second)).isEmpty();
    }

    private Map<String, Object> operatorRow(String operatorId) {
        return query("SELECT * FROM qb_operator WHERE operator_id = ?", operatorId).getFirst();
    }

    private Map<String, Object> change(String operatorId, Map<String, Object> extra) {
        Map<String, Object> row = operatorRow(operatorId);
        Map<String, Object> input = new java.util.LinkedHashMap<>();
        input.put("operatorId", operatorId);
        input.put("name", row.get("name"));
        input.put("contactName", row.get("contact_name"));
        input.put("contactEmail", row.get("contact_email"));
        input.put("status", row.get("status"));
        input.put("baseRevision", ((Number) row.get("revision")).longValue());
        input.putAll(extra);
        return input;
    }

    @Test
    void theDefaultOperatorHasItsOwnSponsorAndPassesToAnotherOnlyByMakingThatOneTheDefault() {
        // Created after installing, with its lead: its own sponsor, like every operator's.
        String first = platformOperator();
        assertThat(operatorRow(first).get("direct_sponsor_id")).isNotNull();

        // Another becomes the default: the first stops being it in the same transaction; one at most.
        NewOperator second = newOperatorWithLead(hq(), unique("Second default"), null);
        run(OperatorProcesses.SAVE, hq(), change(second.operatorId(), Map.of("defaultForNew", true)));
        assertThat(platformOperator()).isEqualTo(second.operatorId());
        assertThat(operatorRow(first)).containsEntry("default_for_new", false);
        assertThat(query("SELECT count(*) AS n FROM qb_operator WHERE default_for_new").getFirst().get("n"))
            .isEqualTo(1L);
        String sponsor = newSponsor(unique("s-fs1-second"));
        assertThat(assignment(sponsor)).containsEntry("operator_id", second.operatorId());

        // The default is not given up, nor suspended, but by making another the default.
        assertThat(codes(refused(OperatorProcesses.SAVE, hq(), change(second.operatorId(),
            Map.of("defaultForNew", false)), 422))).containsExactly(OperatorCodes.DEFAULT_KEEP);
        assertThat(codes(refused(OperatorProcesses.SAVE, hq(), change(second.operatorId(),
            Map.of("status", QbOperators.SUSPENDED)), 422))).containsExactly(OperatorCodes.DEFAULT_INACTIVE);
        assertThat(codes(refused(OperatorProcesses.SAVE, hq(), change(newOperator(hq(), unique("Stopped")),
            Map.of("status", QbOperators.SUSPENDED, "defaultForNew", true)), 422)))
            .containsExactly(OperatorCodes.DEFAULT_INACTIVE);

        // Back to the first, for the class's other tests.
        run(OperatorProcesses.SAVE, hq(), change(first, Map.of("defaultForNew", true)));
        assertThat(platformOperator()).isEqualTo(first);
    }

    @Test
    void withoutADefaultOperatorNobodySignsUpAsASponsorAndTheDailyCheckSaysSo() {
        String first = platformOperator();
        // An installation before its administrator created the default operator.
        execute("UPDATE qb_operator SET default_for_new = false WHERE operator_id = ?", first);
        try {
            assertThat(codes(refused("QB_SPONSOR_APPLY", sponsor(user(unique("s-fs1-early"))), body("displayName",
                "Early", "company", "Early Co", "acceptTerms", true), 422)))
                .containsExactly(OperatorCodes.DEFAULT_MISSING);
            assertThat(items(OperatorHealth.TEMPLATE, hq(), Map.of())).anySatisfy(row -> assertThat(row)
                .containsEntry("checkCode", OperatorHealth.NO_DEFAULT_OPERATOR));
        } finally {
            execute("UPDATE qb_operator SET default_for_new = true WHERE operator_id = ?", first);
        }
        assertThat(items(OperatorHealth.TEMPLATE, hq(), Map.of())).noneSatisfy(row -> assertThat(row)
            .containsEntry("checkCode", OperatorHealth.NO_DEFAULT_OPERATOR));
    }

    @Test
    void aDirectSponsorIsNeverMovedAndAMoveNeedsARunningOperatorThatTakesSponsors() {
        NewOperator kanto = newOperatorWithLead(hq(), unique("Kanto"), null);
        String kansai = newOperator(hq(), unique("Kansai"));
        assertThat(codes(refused(OperatorProcesses.ASSIGN, hq(), body("userId", kanto.leadId(), "operatorId", kansai,
            "reason", "Move"), 422))).containsExactly(OperatorCodes.DIRECT_SPONSOR_FIXED);
        String sponsor = newSponsor(unique("s-fs1-move"));
        // There is no going back to headquarters: an operator is required.
        refused(OperatorProcesses.ASSIGN, hq(), body("userId", sponsor, "reason", "Back"), 400);
        // A suspended operator takes no one.
        Map<String, Object> current = items("qb.admin.operators", hq(), Map.of("params", Map.of("operatorId",
            kansai))).getFirst();
        run(OperatorProcesses.SAVE, hq(), body("operatorId", kansai, "name", current.get("name"), "contactName",
            current.get("contactName"), "contactEmail", current.get("contactEmail"), "status",
            QbOperators.SUSPENDED, "baseRevision", ((Number) current.get("revision")).longValue()));
        assertThat(codes(refused(OperatorProcesses.ASSIGN, hq(), body("userId", sponsor, "operatorId", kansai,
            "reason", "Move"), 422))).containsExactly(OperatorCodes.SUSPENDED);
        // An operator that takes no sponsors (a shops' operator, later) takes none.
        execute("UPDATE qb_operator SET publisher_kinds = 'SHOPIFY_SHOP' WHERE operator_id = ?",
            kanto.operatorId());
        assertThat(codes(refused(OperatorProcesses.ASSIGN, hq(), body("userId", sponsor, "operatorId",
            kanto.operatorId(), "reason", "Move"), 422))).containsExactly(OperatorCodes.KIND_NOT_TAKEN);
        execute("UPDATE qb_operator SET publisher_kinds = 'SPONSOR' WHERE operator_id = ?", kanto.operatorId());
    }

    @Test
    void aSponsorWaitingForAReviewIsMovedOnceItIsDecidedAndHearsOfTheMove() {
        String sponsor = newSponsor(unique("s-fs1-wait"));
        credit(sponsor, 10_000);
        NewOperator kyushu = newOperatorWithLead(hq(), unique("Kyushu"), null);
        String requestId = pendingReview(sponsor, "FS1 waiting");
        assertThat(codes(refused(OperatorProcesses.ASSIGN, hq(), body("userId", sponsor, "operatorId",
            kyushu.operatorId(), "reason", "Move"), 422))).containsExactly(OperatorCodes.REVIEW_PENDING);

        decide(requestId, "APPROVE", null);
        assertThat(run(OperatorProcesses.ASSIGN, hq(), body("userId", sponsor, "operatorId", kyushu.operatorId(),
            "reason", "Move"))).containsEntry("changed", true);
        assertThat(assignment(sponsor)).containsEntry("operator_id", kyushu.operatorId())
            .containsEntry("assigned_by", "fs1-hq");
        deliverEvents();
        assertThat(InboxItSupport.messagesOf(sponsor)).filteredOn(m -> "OPERATOR_CHANGED".equals(m.get("kind")))
            .singleElement().satisfies(m -> assertThat(String.valueOf(m.get("body"))).contains("ops@"));
    }

    @Test
    void aMovedSponsorsReportTaskGoesToTheirNewOperator() {
        String sponsor = newSponsor(unique("s-fs1-report"));
        credit(sponsor, 10_000);
        String publication = livePublication(sponsor, "FS1 reported");
        String taker = newTaker(unique("t-fs1-report"), "JP");
        run("QB_VIEW", taker(taker), body("publicationId", publication));
        run(ReportProcesses.SUBMIT, taker(taker), body("publicationId", publication, "reason", QbReports.SPAM));
        String source = "qb.report:" + publication;
        assertThat(openTasks(source)).singleElement().satisfies(task -> assertThat(task)
            .containsEntry("scope_value", platformOperator())
            .containsEntry("assignee_permission", QbPermissions.OPERATOR_USE));

        String tohoku = newOperator(hq(), unique("Tohoku"));
        run(OperatorProcesses.ASSIGN, hq(), body("userId", sponsor, "operatorId", tohoku, "reason", "Move"));
        assertThat(openTasks(source)).singleElement().satisfies(task -> assertThat(task)
            .containsEntry("scope_value", tohoku).containsEntry("task_type", QbReports.TASK_TYPE)
            .containsEntry("subject_id", publication));
    }

    @Test
    void theDailyCheckFindsSponsorsWithoutOperatorsAndOperatorsShortOfReviewers() {
        String sponsor = newSponsor(unique("s-fs1-orphan"));
        // An inconsistency nothing in the processes makes: the assignment gone.
        execute("DELETE FROM qb_sponsor_operator WHERE user_id = ?", sponsor);
        String lonely = newOperator(hq(), unique("Lonely"));

        List<Map<String, Object>> rows = items(OperatorHealth.TEMPLATE, hq(), Map.of());
        assertThat(rows).anySatisfy(row -> assertThat(row).containsEntry("checkCode", OperatorHealth.NO_OPERATOR)
            .containsEntry("sponsorId", sponsor));
        // Its lead has not the role yet: nobody can review there.
        assertThat(rows).anySatisfy(row -> assertThat(row).containsEntry("checkCode", OperatorHealth.FEW_REVIEWERS)
            .containsEntry("operatorId", lonely));
        assertThat(rows).noneSatisfy(row -> assertThat(row).containsEntry("checkCode", OperatorHealth.DIRECT_SPONSOR)
            .containsEntry("operatorId", lonely));

        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        Instant at = clock.instant();
        assertThat(jobRunner.run(checkJob, at)).isEqualTo(JobRunner.Outcome.SUCCEEDED);
        assertThat(openTasks(OperatorHealth.source(at))).singleElement().satisfies(task -> assertThat(task)
            .containsEntry("assignee_permission", QbPermissions.OPERATORS_READ));
    }
}
