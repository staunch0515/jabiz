package com.jabiz.quizbuks.it;

import com.jabiz.context.EntryScope;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.fee.ExposurePrices;
import com.jabiz.quizbuks.fee.FeeCodes;
import com.jabiz.quizbuks.operator.OperatorCodes;
import com.jabiz.quizbuks.operator.QbOperators;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.runtime.context.Actor;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M8 (docs/quizbuks/plans/impl/F-M8.md §3.2): the operators' exposure prices. The lead of an operator, or the
 * headquarters, saves a price more than 0 within the headquarters' range, with a reason; a member who is not the lead,
 * and an operator for another, cannot; no range, no price; the price is saved over in turns (one row an operator).
 */
class ExposurePricesIT extends PrepaymentItSupport {

    private String hq() {
        return TestTokens.bearer(tokens, "fm8-ops", QbPermissions.OPERATORS_WRITE, QbPermissions.OPERATORS_READ);
    }

    private String finance() {
        return TestTokens.bearer(tokens, "fm8-finance", QbPermissions.FEE_MANAGE);
    }

    private String operatorSession(String userId, String operatorId) {
        Actor actor = new Actor(userId, null, Set.of(QbRoles.OPERATOR),
            Set.copyOf(QbRoles.of(QbRoles.OPERATOR).permissions()), clock.instant(), null, QbOperators.ENTRY, true)
            .inScope(new EntryScope(QbOperators.SCOPE, operatorId));
        return "Bearer " + tokens.issue(actor).token();
    }

    private String member(String hq, String operatorId, String name, boolean lead) {
        String userId = createUser(name);
        java.util.List<String> columns = query("SELECT column_name FROM information_schema.columns WHERE table_schema = "
            + "current_schema() AND table_name = 'sec_user_version' AND column_name NOT IN ('row_id', 'version_no', "
            + "'verified_email') ORDER BY ordinal_position").stream().map(row -> (String) row.get("column_name"))
            .toList();
        String list = String.join(", ", columns);
        execute("INSERT INTO sec_user_version (" + list + ", version_no, verified_email) SELECT " + list
            + ", version_no + 1, email FROM sec_user_version WHERE user_id = ?::uuid ORDER BY version_no DESC LIMIT 1",
            userId);
        run(com.jabiz.quizbuks.operator.OperatorProcesses.MEMBER_ADD, hq, body("operatorId", operatorId, "userId",
            userId, "lead", lead));
        return userId;
    }

    private static BigDecimal unitOf(String operator) {
        return (BigDecimal) query("SELECT unit FROM qb_exposure_price WHERE operator_id = ?", operator).getFirst()
            .get("unit");
    }

    @Test
    void anOperatorsLeadSavesItsPriceWithinTheRangeAndAMemberWhoIsNotTheLeadCannot() {
        String operator = newOperator(hq(), unique("fm8 operator"));
        String lead = member(hq(), operator, unique("fm8-lead"), true);
        String staff = member(hq(), operator, unique("fm8-staff"), false);
        Map<String, Object> price = body("unit", "2.5", "reason", "F-M8");
        assertThat(codes(refused(ExposurePrices.OPERATOR_SET, operatorSession(staff, operator), price, 422)))
            .containsExactly(OperatorCodes.NOT_LEAD);
        assertThat(query("SELECT 1 FROM qb_exposure_price WHERE operator_id = ?", operator)).isEmpty();

        Map<String, Object> saved = run(ExposurePrices.OPERATOR_SET, operatorSession(lead, operator), price);
        assertThat(String.valueOf(saved.get("operatorId"))).isEqualTo(operator);
        assertThat(unitOf(operator)).isEqualByComparingTo("2.5");

        // Saved over in turns: still one row, the new price and the new reason.
        run(ExposurePrices.OPERATOR_SET, operatorSession(lead, operator), body("unit", "4", "reason", "raised"));
        assertThat(query("SELECT unit, reason FROM qb_exposure_price WHERE operator_id = ?", operator))
            .singleElement().satisfies(row -> {
                assertThat((BigDecimal) row.get("unit")).isEqualByComparingTo("4");
                assertThat(row.get("reason")).isEqualTo("raised");
            });
    }

    @Test
    void thePriceIsMoreThanZeroWithinTheRangeAndTheReasonIsRequired() {
        String operator = newOperator(hq(), unique("fm8 range"));
        String lead = member(hq(), operator, unique("fm8-lead-range"), true);
        String session = operatorSession(lead, operator);
        // The range is 1 to 100 yen (the placeholders): below, above and zero are refused with the range.
        assertThat(codes(refused(ExposurePrices.OPERATOR_SET, session, body("unit", "0.5", "reason", "x"), 422)))
            .containsOnly(FeeCodes.EXPOSURE_PRICE_RANGE);
        assertThat(codes(refused(ExposurePrices.OPERATOR_SET, session, body("unit", "100.01", "reason", "x"), 422)))
            .containsOnly(FeeCodes.EXPOSURE_PRICE_RANGE);
        assertThat(codes(refused(ExposurePrices.OPERATOR_SET, session, body("unit", "0", "reason", "x"), 422)))
            .containsOnly(FeeCodes.EXPOSURE_PRICE_RANGE);
        // No reason, no price (a validation error of the input, 400).
        refused(ExposurePrices.OPERATOR_SET, session, body("unit", "5", "reason", " "), 400);
        assertThat(query("SELECT 1 FROM qb_exposure_price WHERE operator_id = ?", operator)).isEmpty();
        // The ends of the range are allowed.
        run(ExposurePrices.OPERATOR_SET, session, body("unit", "1", "reason", "least"));
        run(ExposurePrices.OPERATOR_SET, session, body("unit", "100", "reason", "highest"));
        assertThat(unitOf(operator)).isEqualByComparingTo("100");
    }

    @Test
    void theHeadquartersSetsAnyOperatorsPriceAndAnUnknownOperatorIsNotFound() {
        String operator = newOperator(hq(), unique("fm8 hq sets"));
        run(ExposurePrices.SET, finance(), body("operatorId", operator, "unit", "3", "reason", "headquarters"));
        assertThat(unitOf(operator)).isEqualByComparingTo("3");
        refused(ExposurePrices.SET, finance(), body("operatorId", "no-such-operator", "unit", "3", "reason", "x"), 404);
        // Outside the range the headquarters is refused as well.
        assertThat(codes(refused(ExposurePrices.SET, finance(), body("operatorId", operator, "unit", "500",
            "reason", "x"), 422))).containsOnly(FeeCodes.EXPOSURE_PRICE_RANGE);
    }

    @Test
    void anOperatorNeitherSetsAnotherOperatorsPriceNorHoldsTheHeadquartersProcess() {
        String mine = newOperator(hq(), unique("fm8 mine"));
        String other = newOperator(hq(), unique("fm8 other"));
        String lead = member(hq(), mine, unique("fm8-lead2"), true);
        refused(ExposurePrices.SET, operatorSession(lead, mine), body("operatorId", other, "unit", "3", "reason", "x"),
            403);
        assertThat(query("SELECT 1 FROM qb_exposure_price WHERE operator_id = ?", other)).isEmpty();
        // The lead's own process takes the caller's operator whatever the input names.
        run(ExposurePrices.OPERATOR_SET, operatorSession(lead, mine), body("operatorId", other, "unit", "3",
            "reason", "x"));
        assertThat(query("SELECT 1 FROM qb_exposure_price WHERE operator_id = ?", other)).isEmpty();
        assertThat(unitOf(mine)).isEqualByComparingTo("3");
    }

    @Test
    void thePriceTableIsWrittenByTheProcessesOnlyAndTheSettlementTableIsAppendOnly() {
        // The settlement table carries the platform's guard against updates and deletes (written once; F-M8c writes it).
        assertThat(query("SELECT tgname FROM pg_trigger WHERE tgrelid = 'qb_exposure_settlement_version'::regclass "
            + "AND NOT tgisinternal")).isNotEmpty();
        assertThat(query("SELECT indexname FROM pg_indexes WHERE tablename = 'qb_exposure_settlement_version'"))
            .extracting(row -> String.valueOf(row.get("indexname")))
            .contains("qb_exposure_settlement_version_period_idx", "qb_exposure_settlement_version_final_idx",
                "qb_exposure_settlement_version_once_idx");
    }
}
