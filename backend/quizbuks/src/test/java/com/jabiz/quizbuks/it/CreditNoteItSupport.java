package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.fee.AudienceRefundJob;
import com.jabiz.quizbuks.fee.FeeCodes;
import com.jabiz.quizbuks.fee.FeeProcesses;
import com.jabiz.quizbuks.fee.FeeRequests;
import com.jabiz.quizbuks.fee.FeeRules;
import com.jabiz.quizbuks.fee.QbCreditNotes;
import com.jabiz.quizbuks.fee.QbFees;
import com.jabiz.quizbuks.fee.QbPrepayments;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.quizbuks.wallet.QbWallets;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** F-M7: what the credit note ITs share: finance's two people, the notes and fees by hand, the reads. */
abstract class CreditNoteItSupport extends PrepaymentItSupport {

    static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");

    String finance() {
        return tokenOf(user("fm7-finance"), QbRoles.ADMIN_FINANCE);
    }

    String content() {
        return tokenOf(user("fm7-content"), QbRoles.ADMIN_CONTENT);
    }

    String monthly(String sponsor) {
        return ((FeeProcesses.ItemOutput) systemRun(FeeProcesses.MONTHLY_ITEM,
            body("sponsorId", sponsor, "period", FeeRules.period(clock.instant(), TOKYO)))).outcome();
    }

    static Map<String, Object> monthlyCharge(String sponsor) {
        return query("SELECT * FROM qb_fee_charge_version WHERE sponsor_id = ? AND kind = 'MONTHLY'", sponsor)
            .getFirst();
    }

    static Map<String, Object> invoiceOf(Object chargeId) {
        return query("SELECT * FROM qb_fee_invoice WHERE charge_id = ?", String.valueOf(chargeId)).getFirst();
    }

    static List<Map<String, Object>> notes(String sponsor) {
        return query("SELECT * FROM qb_fee_credit_note_version WHERE sponsor_id = ? ORDER BY issued_at, credit_note_no",
            sponsor);
    }

    static BigDecimal balance(String sponsor, String kind) {
        List<Map<String, Object>> rows = query("SELECT balance FROM qb_wallet WHERE owner_id = ? AND kind = ?",
            sponsor, kind);
        return rows.isEmpty() ? BigDecimal.ZERO : (BigDecimal) rows.getFirst().get("balance");
    }

    static List<String> lines(String sourceEntity, Object sourceId) {
        return query("SELECT a.account_code, e.direction, e.amount FROM ledger_transaction_version t "
            + "JOIN ledger_entry_version e ON e.transaction_id = t.transaction_id "
            + "JOIN ledger_account_version a ON a.account_id = e.account_id "
            + "WHERE t.source_entity = ? AND t.source_id = ? AND t.reverses_transaction_id IS NULL "
            + "ORDER BY t.process_seq_id, t.transaction_id, e.line_no", sourceEntity, String.valueOf(sourceId))
            .stream().map(p -> p.get("account_code") + " " + p.get("direction") + " "
                + ((BigDecimal) p.get("amount")).stripTrailingZeros().toPlainString()).toList();
    }

    /** A credit note by hand: proposed by finance, approved by another administrator. */
    Map<String, Object> creditNote(Object chargeId, long amount) {
        Map<String, Object> request = run(FeeRequests.RECORD, finance(), body("kind", QbFees.REQUEST_CREDIT_NOTE,
            "chargeId", String.valueOf(chargeId), "amount", amount, "reason", "Service was down two days"));
        run("APPROVAL_DECIDE", content(), body("requestId", request.get("approvalRequestId"), "decision", "APPROVE"));
        deliverEvents();
        return query("SELECT * FROM qb_fee_request WHERE request_id = ?", request.get("requestId")).getFirst();
    }

    /** A fee by hand: proposed by finance, approved by another administrator (charged in the current month). */
    Object manualFee(String sponsor, long amount) {
        Map<String, Object> request = run(FeeRequests.RECORD, finance(), body("kind", QbFees.REQUEST_MANUAL,
            "sponsorId", sponsor, "amount", amount, "reason", "Custom report"));
        run("APPROVAL_DECIDE", content(), body("requestId", request.get("approvalRequestId"), "decision", "APPROVE"));
        deliverEvents();
        return query("SELECT charge_id FROM qb_fee_request WHERE request_id = ?", request.get("requestId")).getFirst()
            .get("charge_id");
    }

    /** A seven-day audience draft (gender: FEMALE) whose single winner's place ends it. */
    String oneWinnerAudienceDraft(String sponsor, String name) {
        String token = sponsorToken(sponsor);
        Instant now = clock.instant();
        Map<String, Object> input = complete(version(token, name + " quiz"), name, now.plus(Duration.ofDays(1)),
            now.plus(Duration.ofDays(8)));
        input.put("maxWinners", 1);
        String publicationId = (String) run("QB_PUBLICATION_SAVE", token, input).get("publicationId");
        Map<String, Object> gender = new java.util.HashMap<>();
        gender.put("key", "gender");
        gender.put("options", List.of("FEMALE"));
        run("QB_PUBLICATION_AUDIENCE_SAVE", token, body("publicationId", publicationId, "criteria",
            new java.util.ArrayList<>(List.of(gender))));
        return publicationId;
    }

    /** A Taker in Japan who told the platform they are a woman, so that the publication is theirs to take. */
    String womanTaker(String name) {
        String taker = newTaker(unique(name), "JP");
        Map<String, Object> answer = new java.util.HashMap<>();
        answer.put("key", "gender");
        answer.put("options", List.of("FEMALE"));
        run("QB_PROFILE_ATTRIBUTES_SAVE", taker(taker), body("profileAnswers", List.of(answer)));
        return taker;
    }
}
