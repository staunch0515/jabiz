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
 * F-M14b (operator notes): an operator reads the notes of the latest daily reconciliation about the sponsors assigned
 * to it now ({@code qb.operator.recon-notes}, D64 through the sponsor's assignment), never the other checks nor their
 * amounts; a sponsor moved to another operator is that operator's, with its earlier publications; a session of no
 * operator reads nothing; the template is {@code qb.operator.use}'s and the dataset under it is not an operator's.
 * Finance's list of a run's differences asks the server for them as the reconciliation page does (one {@code ne} a
 * check for attention or a note).
 */
class OperatorReconNotesIT extends ExposureFeeItSupport {

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

    /**
     * A run two minutes after the clock (the clock moved there, so that each run of this class has its own time; the
     * sessions issued before stay valid).
     */
    private String reconcile() {
        clock.set(clock.instant().plus(Duration.ofMinutes(2)));
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        assertThat(jobs.run(reconcileJob, clock.instant())).isEqualTo(JobRunner.Outcome.SUCCEEDED);
        return String.valueOf(query("SELECT run_id FROM qb_recon_run_version WHERE scheduled_time = ?",
            java.sql.Timestamp.from(clock.instant())).getFirst().get("run_id"));
    }

    /** The notes (a run that noted nothing about the operator's sponsors gives one row with its time and no note). */
    private List<Map<String, Object>> notes(String token) {
        return items(TEMPLATE, token, Map.of("limit", 500)).stream().filter(row -> row.get("checkCode") != null)
            .toList();
    }

    private List<Map<String, Object>> rows(String token) {
        return items(TEMPLATE, token, Map.of("limit", 500));
    }

    /** The template's columns: no amount, no other check's anything. */
    private static final Set<String> COLUMNS = Set.of("checkCode", "sponsorId", "company", "contactName",
        "publications", "scheduledTime");

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
        // A real difference of another check about the north's sponsor (its wallet's cache, behind the processes'
        // back): the run finds it, the operator never reads it.
        Map<String, Object> wallet = wallet(northSponsor);
        execute("UPDATE qb_wallet SET balance = balance + 7 WHERE wallet_id = ?", wallet.get("wallet_id"));

        String runId = reconcile();
        execute("UPDATE qb_wallet SET balance = balance - 7 WHERE wallet_id = ?", wallet.get("wallet_id"));

        // Finance reads the run's differences as the reconciliation page asks for them (review H1): one ne a check for
        // attention or a note, joined by AND; the wallet's difference is there, no note and no item for attention.
        List<Map<String, Object>> filters = new java.util.ArrayList<>();
        for (String check : ReconChecks.ALL) {
            if (ReconChecks.level(check) != ReconChecks.Level.DIFFERENCE) {
                filters.add(Map.of("field", "checkCode", "op", "ne", "value", check));
            }
        }
        List<Map<String, Object>> differences = items("qb.admin.recon-items",
            TestTokens.bearer(tokens, "fm14b-reconciler", QbPermissions.RECONCILE),
            Map.of("params", Map.of("runId", runId), "filters", filters, "limit", 500));
        assertThat(differences).anySatisfy(row -> assertThat(row).containsEntry("checkCode", ReconChecks.SPONSOR_WALLET)
            .containsEntry("partyId", northSponsor));
        assertThat(differences).allSatisfy(row -> assertThat(ReconChecks.level(String.valueOf(row.get("checkCode"))))
            .isEqualTo(ReconChecks.Level.DIFFERENCE));
        assertThat(items("qb.admin.recon-items", TestTokens.bearer(tokens, "fm14b-reconciler", QbPermissions.RECONCILE),
            Map.of("params", Map.of("runId", runId), "limit", 500)))
            .anySatisfy(row -> assertThat(row).containsEntry("checkCode", ReconChecks.DISTRIBUTION_AT_FLOOR)
                .containsEntry("partyId", northSponsor));

        assertThat(rows(northSession)).singleElement().satisfies(row -> {
            assertThat(row.keySet()).containsExactlyInAnyOrderElementsOf(COLUMNS);
            assertThat(row).containsEntry("checkCode", ReconChecks.DISTRIBUTION_AT_FLOOR)
                .containsEntry("sponsorId", northSponsor);
            assertThat(new BigDecimal(String.valueOf(row.get("publications")))).isEqualByComparingTo("2");
            assertThat(row.get("scheduledTime")).isNotNull();
        });
        assertThat(notes(southSession)).extracting(row -> row.get("sponsorId")).containsExactly(southSponsor);
        // An operator with no sponsors reads no note, only when the check looked. The operators' entry has no session
        // without an operator; a session of another entry, which has no operator scope, reads no note either, even
        // with the permission.
        assertThat(rows(idleSession)).singleElement().satisfies(row -> {
            assertThat(row.get("checkCode")).isNull();
            assertThat(row.get("scheduledTime")).isNotNull();
        });
        client.post().uri("/api/queries/" + TEMPLATE).contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, operatorSession(createUser(unique("fm14b-none")), null))
            .bodyValue(Map.of()).exchange().expectStatus().isUnauthorized();
        assertThat(notes(TestTokens.bearer(tokens, createUser(unique("fm14b-hq-user")), QbPermissions.OPERATOR_USE)))
            .isEmpty();
    }

    /**
     * Review M2 and M3: a publication of another sponsor's video that bought nothing is noted to the operator its
     * sponsor is assigned to, not to another; moved to another operator, the sponsor's notes (its earlier
     * publications too) are the new operator's at once and no longer the old one's.
     */
    @Test
    void theNoteOfAnUnboughtVideoFollowsTheSponsorsOperator() {
        autoApprove(true);
        // Submitted while the switch was off: it bought nothing (as in ExposureSettleIT).
        param(QbParams.EXPOSURE_BILLING_ENABLED, "false");
        String sponsor = reviewedSponsor("fm14b-unbought");
        grant(sponsor, 100_000, clock.instant().plus(Duration.ofDays(400)));
        String video = approvedVideo(reviewedSponsor("fm14b-unbought-provider"));
        submit(sponsorToken(sponsor), draft(sponsor, "fm14b-unbought", video, 100L, clock.instant()));
        param(QbParams.EXPOSURE_BILLING_ENABLED, "true");
        String first = operatorOf(sponsor);
        String firstSession = operatorSession(member(first, unique("fm14b-u1")), first);
        String other = newOperator(hq(), unique("fm14b unbought other"));
        String otherSession = operatorSession(member(other, unique("fm14b-u2")), other);

        reconcile();

        assertThat(notes(firstSession)).filteredOn(row -> sponsor.equals(row.get("sponsorId"))).singleElement()
            .satisfies(row -> {
                assertThat(row).containsEntry("checkCode", ReconChecks.EXPOSURE_UNPURCHASED);
                assertThat(new BigDecimal(String.valueOf(row.get("publications")))).isEqualByComparingTo("1");
            });
        assertThat(notes(otherSession)).extracting(row -> row.get("sponsorId")).doesNotContain(sponsor);

        // Moved: the same run's note is the new operator's, the old one no longer reads it.
        run(OperatorProcesses.ASSIGN, hq(), body("userId", sponsor, "operatorId", other, "reason", "F-M14b"));
        assertThat(notes(otherSession)).extracting(row -> row.get("checkCode") + ":" + row.get("sponsorId"))
            .containsExactly(ReconChecks.EXPOSURE_UNPURCHASED + ":" + sponsor);
        assertThat(notes(firstSession)).extracting(row -> row.get("sponsorId")).doesNotContain(sponsor);
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
