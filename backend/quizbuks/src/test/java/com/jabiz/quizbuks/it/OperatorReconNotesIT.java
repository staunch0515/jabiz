package com.jabiz.quizbuks.it;

import com.jabiz.context.EntryScope;
import com.jabiz.job.JobDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.fee.DistributionPrices;
import com.jabiz.quizbuks.operator.OperatorProcesses;
import com.jabiz.quizbuks.operator.QbOperators;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.quizbuks.wallet.ReconChecks;
import com.jabiz.quizbuks.wallet.Reconciliation;
import com.jabiz.runtime.context.Actor;
import com.jabiz.runtime.job.JobRunner;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M14b (operator notes): an operator reads the notes of the latest daily reconciliation about its own sponsors only
 * ({@code qb.operator.recon-notes}, D64 through the sponsor's assignment), never the other checks; a session of no
 * operator reads nothing; the template is {@code qb.operator.use}'s and the dataset under it is not an operator's.
 */
class OperatorReconNotesIT extends PrepaymentItSupport {

    static final String TEMPLATE = "qb.operator.recon-notes";

    @Autowired
    @Qualifier("qbReconcileJob")
    JobDefinition<?> reconcileJob;

    private String hq() {
        return TestTokens.bearer(tokens, "fm14b-notes-hq", QbPermissions.OPERATORS_WRITE, QbPermissions.OPERATORS_READ);
    }

    private static String operatorOf(String sponsor) {
        return String.valueOf(query("SELECT operator_id FROM qb_sponsor_operator WHERE user_id = ?", sponsor)
            .getFirst().get("operator_id"));
    }

    /** A sponsor of its own operator whose distribution floor (8) is above what a start comes to: charged the floor. */
    private String flooredSponsor(String name) {
        String sponsor = pricedSponsor(name);
        String operator = operatorOf(sponsor);
        run(DistributionPrices.SET, TestTokens.bearer(tokens, "fm14b-notes-fee", QbPermissions.FEE_MANAGE),
            body("operatorId", operator, "rate", "0.05", "unitFloor", "1", "unitCeiling", "20", "minimumDeposit", 500,
                "reason", "F-M14b"));
        execute("UPDATE qb_distribution_price SET unit_floor = 8 WHERE operator_id = ?", operator);
        credit(sponsor, 5_000);
        // The platform fee account sets aside what the publications' fees come to (264 each).
        cash(sponsor, 2_000);
        return sponsor;
    }

    private String publication(String sponsor, String name) {
        Map<String, Object> overrides = new HashMap<>(Map.of("maxWinners", 30, "regions", List.of("GLOBAL")));
        return livePublication(sponsor, name, overrides);
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
        run(OperatorProcesses.MEMBER_ADD, hq(), body("operatorId", operatorId, "userId", userId, "lead", false));
        return userId;
    }

    /** A session of the operators' entry, as its sign-in issues it; {@code operatorId} null: one with no scope. */
    private String operatorSession(String userId, String operatorId) {
        Actor actor = new Actor(userId, null, Set.of(QbRoles.OPERATOR),
            Set.copyOf(QbRoles.of(QbRoles.OPERATOR).permissions()), clock.instant(), null, QbOperators.ENTRY, true);
        return "Bearer " + tokens.issue(operatorId == null ? actor
            : actor.inScope(new EntryScope(QbOperators.SCOPE, operatorId))).token();
    }

    private void reconcile() {
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        assertThat(jobs.run(reconcileJob, clock.instant().plus(Duration.ofMinutes(1))))
            .isEqualTo(JobRunner.Outcome.SUCCEEDED);
    }

    private List<Map<String, Object>> notes(String token) {
        return items(TEMPLATE, token, Map.of("limit", 500));
    }

    @Test
    void anOperatorReadsTheLatestRunsNotesOfItsOwnSponsorsOnly() {
        param(QbParams.DISTRIBUTION_BILLING_ENABLED, "true");
        String northSponsor = flooredSponsor("fm14b-notes-north");
        String southSponsor = flooredSponsor("fm14b-notes-south");
        String north = operatorOf(northSponsor);
        String south = operatorOf(southSponsor);
        publication(northSponsor, "FM14b north at the floor");
        publication(northSponsor, "FM14b north at the floor again");
        publication(southSponsor, "FM14b south at the floor");
        String northSession = operatorSession(member(north, unique("fm14b-n")), north);
        String southSession = operatorSession(member(south, unique("fm14b-s")), south);
        String idle = newOperator(hq(), unique("fm14b idle"));
        String idleSession = operatorSession(member(idle, unique("fm14b-i")), idle);
        // Before any run there is nothing to read.
        assertThat(notes(northSession)).isEmpty();

        reconcile();

        assertThat(notes(northSession)).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("checkCode", ReconChecks.DISTRIBUTION_AT_FLOOR)
                .containsEntry("sponsorId", northSponsor);
            assertThat(new BigDecimal(String.valueOf(row.get("publications")))).isEqualByComparingTo("2");
            assertThat(row.get("scheduledTime")).isNotNull();
        });
        assertThat(notes(southSession)).extracting(row -> row.get("sponsorId")).containsExactly(southSponsor);
        // An operator with no sponsors reads nothing. The operators' entry has no session without an operator; a
        // session of another entry, which has no operator scope, reads nothing either, even with the permission.
        assertThat(notes(idleSession)).isEmpty();
        client.post().uri("/api/queries/" + TEMPLATE).contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, operatorSession(createUser(unique("fm14b-none")), null))
            .bodyValue(Map.of()).exchange().expectStatus().isUnauthorized();
        assertThat(notes(TestTokens.bearer(tokens, createUser(unique("fm14b-hq-user")), QbPermissions.OPERATOR_USE)))
            .isEmpty();
        // Whatever else the run found about these sponsors, an operator reads only the two notes.
        assertThat(notes(northSession)).allSatisfy(row -> assertThat(row.get("checkCode"))
            .isIn(ReconChecks.DISTRIBUTION_AT_FLOOR, ReconChecks.EXPOSURE_UNPURCHASED));
    }

    @Test
    void theTemplateIsTheOperatorsAndTheDatasetUnderItIsNot() {
        String operator = newOperator(hq(), unique("fm14b access"));
        String session = operatorSession(member(operator, unique("fm14b-a")), operator);
        // Finance reconciles but is no operator: the template is refused.
        client.post().uri("/api/queries/" + TEMPLATE).contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(tokens, "fm14b-finance", QbPermissions.RECONCILE))
            .bodyValue(Map.of()).exchange().expectStatus().isForbidden();
        // The operator reads the notes only through the template, never the other items through the dataset API.
        client.post().uri("/api/datasets/{id}/query", Reconciliation.OPERATOR_ITEM_DATASET)
            .contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, session)
            .bodyValue(Map.of()).exchange().expectStatus().isForbidden();
        client.post().uri("/api/queries/" + TEMPLATE).contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, session).bodyValue(Map.of()).exchange().expectStatus().isOk();
    }
}
