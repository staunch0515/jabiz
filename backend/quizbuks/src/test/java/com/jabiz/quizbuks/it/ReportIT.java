package com.jabiz.quizbuks.it;

import com.jabiz.context.EntryScope;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.operator.OperatorProcesses;
import com.jabiz.quizbuks.operator.QbOperators;
import com.jabiz.quizbuks.report.QbReports;
import com.jabiz.quizbuks.report.ReportCodes;
import com.jabiz.quizbuks.report.ReportProcesses;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.runtime.context.Actor;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Q49a (docs/quizbuks/plans/Q49a-contest-report.md; Apple App Review Guideline 1.2): a taker reports a quiz, once at
 * a time and at most so many a day; its first open report asks headquarters and the sponsor's operator; an operator
 * sees and closes its own sponsors' reports only; closing them closes the tasks.
 */
class ReportIT extends PlayItSupport {

    private String reportReviewer() {
        return TestTokens.bearer(tokens, "q49a-content", QbPermissions.ADMIN_CONTENT_WRITE,
            QbPermissions.REVIEW_PUBLICATION);
    }

    private String hq() {
        return TestTokens.bearer(tokens, "q49a-hq", QbPermissions.OPERATORS_WRITE, QbPermissions.OPERATORS_READ);
    }

    /** A session of the operators' entry, as its sign-in issues it: the role's permissions, in its operator. */
    private String operatorSession(String userId, String operatorId) {
        Actor actor = new Actor(userId, null, Set.of(QbRoles.OPERATOR),
            Set.copyOf(QbRoles.of(QbRoles.OPERATOR).permissions()), clock.instant(), null, QbOperators.ENTRY, true)
            .inScope(new EntryScope(QbOperators.SCOPE, operatorId));
        return "Bearer " + tokens.issue(actor).token();
    }

    private String newOperator(String name) {
        return (String) run(OperatorProcesses.SAVE, hq(), body("name", name, "contactName", "Owner of " + name,
            "contactEmail", "ops@" + name.toLowerCase() + ".example")).get("operatorId");
    }

    /** A verified user made a member of the operator; returns the user id. */
    private String member(String operatorId, String name) {
        String userId = createUser(name);
        List<String> columns = query("SELECT column_name FROM information_schema.columns WHERE table_schema = "
            + "current_schema() AND table_name = 'sec_user_version' AND column_name NOT IN ('row_id', 'version_no', "
            + "'verified_email') ORDER BY ordinal_position").stream().map(row -> (String) row.get("column_name"))
            .toList();
        String list = String.join(", ", columns);
        execute("INSERT INTO sec_user_version (" + list + ", version_no, verified_email) SELECT " + list
            + ", version_no + 1, email FROM sec_user_version WHERE user_id = ?::uuid ORDER BY version_no DESC LIMIT 1",
            userId);
        run(OperatorProcesses.MEMBER_ADD, hq(), body("operatorId", operatorId, "userId", userId, "lead", true));
        return userId;
    }

    /** The taker opens the quiz (QB_VIEW, as its page does) and reports it. */
    private Map<String, Object> report(String taker, String publicationId, String reason, String note) {
        run("QB_VIEW", taker(taker), body("publicationId", publicationId));
        return run(ReportProcesses.SUBMIT, taker(taker), body("publicationId", publicationId, "reason", reason,
            "note", note));
    }

    private static List<Map<String, Object>> reportsOf(String publicationId) {
        return query("SELECT * FROM qb_report WHERE publication_id = ?::uuid ORDER BY created_at", publicationId);
    }

    private static List<Map<String, Object>> openTasks(String publicationId) {
        return query("SELECT * FROM (SELECT DISTINCT ON (task_id) * FROM sys_task_version ORDER BY task_id, "
            + "version_no DESC) t WHERE source_key = ? AND status = 'OPEN' AND NOT is_deleted",
            "qb.report:" + publicationId);
    }

    @SuppressWarnings("unchecked")
    private List<Object> listed(String token) {
        return items("qb.admin.reports", token, Map.of()).stream().map(row -> row.get("publicationId"))
            .map(String::valueOf).map(Object.class::cast).toList();
    }

    @Test
    void aTakerReportsAQuizOnceAtATimeAndItsReviewersAreAsked() {
        String sponsor = newSponsor(unique("s-q49a"));
        credit(sponsor, 10_000);
        String publication = livePublication(sponsor, "Reported quiz");
        String ann = newTaker(unique("t-q49a-ann"), "JP");
        String bob = newTaker(unique("t-q49a-bob"), "JP");

        report(ann, publication, QbReports.MISLEADING, "  The prize does not exist  ");
        assertThat(reportsOf(publication)).singleElement().satisfies(row -> assertThat(row)
            .containsEntry("reporter_id", ann).containsEntry("reason", QbReports.MISLEADING)
            .containsEntry("note", "The prize does not exist").containsEntry("status", QbReports.OPEN)
            .containsEntry("publisher_id", sponsor));
        // Headquarters is asked; no operator looks after this sponsor.
        assertThat(openTasks(publication)).singleElement().satisfies(task -> assertThat(task)
            .containsEntry("assignee_permission", QbPermissions.ADMIN_CONTENT_WRITE));

        // One open report of theirs at a time; another taker's is another; no second task.
        assertThat(codes(refused(ReportProcesses.SUBMIT, taker(ann), body("publicationId", publication, "reason",
            QbReports.SPAM), 422))).contains(ReportCodes.ALREADY_REPORTED);
        report(bob, publication, QbReports.INAPPROPRIATE, null);
        assertThat(reportsOf(publication)).hasSize(2);
        assertThat(openTasks(publication)).hasSize(1);
        assertThat(codes(refused(ReportProcesses.SUBMIT, taker(bob), body("publicationId", UUID.randomUUID(),
            "reason", QbReports.SPAM), 404))).isNotNull();
        // A quiz the taker never opened is as good as none.
        String unseen = newTaker(unique("t-q49a-unseen"), "JP");
        refused(ReportProcesses.SUBMIT, taker(unseen), body("publicationId", publication, "reason", QbReports.SPAM),
            404);
        // Finance reads publications, not the reports' notes and reporters.
        client.post().uri("/api/datasets/" + QbReports.DEFAULT_DATASET + "/query")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, tokenOf("q49a-finance", QbRoles.ADMIN_FINANCE))
            .bodyValue(Map.of("limit", 10)).exchange().expectStatus().isForbidden();
        assertThat(codes(refused(ReportProcesses.SUBMIT, taker(bob), body("publicationId", publication, "reason",
            "BORING"), 422))).contains(ReportCodes.REASON);

        // The reviewers' list counts them by reason.
        assertThat(items("qb.admin.reports", reportReviewer(), Map.of()).stream()
            .filter(row -> publication.equals(String.valueOf(row.get("publicationId")))).findFirst().orElseThrow())
            .satisfies(row -> {
                assertThat(((Number) row.get("openCount")).intValue()).isEqualTo(2);
                assertThat(((Number) row.get("misleading")).intValue()).isEqualTo(1);
                assertThat(((Number) row.get("inappropriate")).intValue()).isEqualTo(1);
            });

        // Closing them needs a second factor, and closes the task; a taker may report again afterwards.
        refused(ReportProcesses.RESOLVE, TestTokens.withoutMfa(tokens, "q49a-content",
            QbPermissions.ADMIN_CONTENT_WRITE), body("publicationId", publication, "outcome", QbReports.DISMISSED), 403);
        assertThat(codes(refused(ReportProcesses.RESOLVE, reportReviewer(), body("publicationId", publication,
            "outcome", QbReports.OPEN), 422))).contains(ReportCodes.OUTCOME);
        assertThat(run(ReportProcesses.RESOLVE, reportReviewer(), body("publicationId", publication, "outcome",
            QbReports.DISMISSED, "resolution", "Checked the prize"))).containsEntry("resolved", 2);
        assertThat(reportsOf(publication)).allSatisfy(row -> assertThat(row)
            .containsEntry("status", QbReports.DISMISSED).containsEntry("resolved_by", "q49a-content")
            .containsEntry("resolution", "Checked the prize"));
        assertThat(openTasks(publication)).isEmpty();
        refused(ReportProcesses.RESOLVE, reportReviewer(), body("publicationId", publication, "outcome",
            QbReports.DISMISSED), 404);
        report(ann, publication, QbReports.REWARD_NOT_GIVEN, null);
        assertThat(openTasks(publication)).hasSize(1);
    }

    @Test
    void aTakerMakesSoManyReportsADay() {
        String sponsor = newSponsor(unique("s-q49a-limit"));
        credit(sponsor, 100_000);
        String carl = newTaker(unique("t-q49a-carl"), "JP");
        for (int i = 0; i < QbReports.DAILY_LIMIT; i++) {
            report(carl, livePublication(sponsor, "Quiz " + i), QbReports.SPAM, null);
        }
        String one = livePublication(sponsor, "One too many");
        run("QB_VIEW", taker(carl), body("publicationId", one));
        assertThat(codes(refused(ReportProcesses.SUBMIT, taker(carl), body("publicationId", one, "reason",
            QbReports.SPAM), 422))).contains(ReportCodes.DAILY_LIMIT);
    }

    @Test
    void anOperatorSeesAndClosesItsOwnSponsorsReportsOnly() {
        String north = newOperator(unique("North"));
        String south = newOperator(unique("South"));
        String northSession = operatorSession(member(north, unique("q49a-n")), north);
        String southSession = operatorSession(member(south, unique("q49a-s")), south);
        String ann = newSponsor(unique("s-q49a-north"));
        run(OperatorProcesses.ASSIGN, hq(), body("userId", ann, "operatorId", north, "reason", "Q49a"));
        credit(ann, 10_000);
        String publication = livePublication(ann, "North's quiz");
        String dan = newTaker(unique("t-q49a-dan"), "JP");

        report(dan, publication, QbReports.INAPPROPRIATE, "Rude words");
        // The operator's task, in its scope: another operator does not see it; headquarters has the list.
        assertThat(openTasks(publication)).extracting(task -> task.get("assignee_permission"))
            .containsExactly(QbPermissions.OPERATOR_USE);
        assertThat(taskSubjects(northSession)).contains(publication);
        assertThat(taskSubjects(southSession)).doesNotContain(publication);

        assertThat(listed(northSession)).contains(publication);
        assertThat(listed(southSession)).doesNotContain(publication);
        refused(ReportProcesses.OPERATOR_RESOLVE, southSession, body("publicationId", publication, "outcome",
            QbReports.ACTIONED), 404);
        assertThat(run(ReportProcesses.OPERATOR_RESOLVE, northSession, body("publicationId", publication,
            "outcome", QbReports.ACTIONED))).containsEntry("resolved", 1);
        assertThat(openTasks(publication)).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private List<Object> taskSubjects(String token) {
        Map<String, Object> result = client.get().uri("/api/tasks/mine").header(HttpHeaders.AUTHORIZATION, token)
            .exchange().expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        return ((List<Map<String, Object>>) result.get("tasks")).stream().map(task -> task.get("subjectId"))
            .map(String::valueOf).map(Object.class::cast).toList();
    }
}
