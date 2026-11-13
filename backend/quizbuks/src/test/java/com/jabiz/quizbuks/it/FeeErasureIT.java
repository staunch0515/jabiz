package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.fee.QbFees;
import com.jabiz.quizbuks.operator.OperatorTransfers;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.wallet.ManualCredits;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M15 a0 (docs/quizbuks/plans/impl/F-M15.md §4.2, L1): erasing a Sponsor keeps their money's records and overwrites only
 * the free text in them, column by column as the platform writes it (NULL where the column may be empty, {@code ***}
 * where it is required): a fee by hand's reason, an invoice's void reason, the requests' reasons, a manual top-up's
 * reason, and a transfer's note, erased with the lead who wrote it or with the direct Sponsor it went to. Another
 * Sponsor's stay. A class of its own: the purge rewrites append-only rows.
 */
class FeeErasureIT extends CreditNoteItSupport {

    @DynamicPropertySource
    static void maintenance(DynamicPropertyRegistry registry) {
        // The tests' login is the maintenance login too (a member of jabiz_maintenance, granted below), for the purge.
        registry.add("jabiz.erasure.datasource.url", () -> DB.r2dbcUrl() + "?schema=" + schema());
        registry.add("jabiz.erasure.datasource.username", DB::username);
        registry.add("jabiz.erasure.datasource.password", DB::password);
    }

    @BeforeEach
    void maintenanceRole() {
        execute("DO $$ BEGIN CREATE ROLE jabiz_maintenance; EXCEPTION WHEN duplicate_object THEN NULL; END $$");
        execute("DO $$ BEGIN EXECUTE 'GRANT jabiz_maintenance TO ' || quote_ident(current_user);"
            + " EXCEPTION WHEN OTHERS THEN NULL; END $$");
    }

    @Test
    void anErasedSponsorsMoneyStaysWithoutItsFreeText() {
        param(QbParams.FEE_MONTHLY, "1000");
        String ann = newSponsor(unique("s-fm15-erase-ann"));
        String bob = newSponsor(unique("s-fm15-erase-bob"));
        for (String sponsor : List.of(ann, bob)) {
            // A fee by hand (its charge and its request), a postpaid month invoiced and voided (its request too),
            // and a top-up entered by hand.
            manualFee(sponsor, 300);
            billing(sponsor, "POSTPAID", 50_000);
            assertThat(monthly(sponsor)).isEqualTo("CHARGED");
            invoiceTheMonth(sponsor);
            Object invoiceId = query("SELECT invoice_id FROM qb_fee_invoice WHERE sponsor_id = ?", sponsor)
                .getFirst().get("invoice_id");
            voidInvoice(invoiceId);
            Map<String, Object> manual = run(ManualCredits.RECORD, finance(), body("sponsorId", sponsor, "kind",
                "PAID_OUTSIDE", "amount", 2000, "reason", "Paid in cash by Mr Tanaka", "receivedOn",
                LocalDate.ofInstant(clock.instant(), ZoneId.of("Asia/Tokyo")).toString(), "bankReference",
                "R-" + UUID.randomUUID().toString().substring(0, 8)));
            run("APPROVAL_DECIDE", content(), body("requestId", manual.get("approvalRequestId"), "decision",
                "APPROVE"));
            deliverEvents();
        }
        assertThat(texts(ann)).allSatisfy(text -> assertThat(text).isNotNull().isNotEqualTo("***"));

        runAsSystem("ACCOUNT_ERASE", Map.of("userId", ann, "requestedBy", "it-support"));
        deliverEvents();

        // Every version of every row stays; the texts are gone: NULL where the column may be empty, *** where not.
        assertThat(query("SELECT DISTINCT reason FROM qb_fee_charge_version WHERE sponsor_id = ? AND kind = 'MANUAL'",
            ann)).singleElement().satisfies(row -> assertThat(row.get("reason")).isNull());
        assertThat(query("SELECT DISTINCT void_reason FROM qb_fee_invoice WHERE sponsor_id = ? AND status = ?", ann,
            QbFees.VOID)).singleElement().satisfies(row -> assertThat(row.get("void_reason")).isNull());
        assertThat(query("SELECT DISTINCT reason FROM qb_fee_request WHERE sponsor_id = ?", ann)).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("reason", "***"));
        assertThat(query("SELECT payer_name, manual_reason FROM qb_topup WHERE sponsor_id = ? AND method = 'MANUAL'",
            ann)).singleElement().satisfies(row -> assertThat(row).containsEntry("payer_name", null)
                .containsEntry("manual_reason", null));
        assertThat(query("SELECT 1 FROM qb_fee_charge_version WHERE sponsor_id = ?", ann)).hasSizeGreaterThan(1);
        // Bob's untouched.
        assertThat(texts(bob)).allSatisfy(text -> assertThat(text).isNotNull().isNotEqualTo("***"));
    }

    @Test
    void aTransfersNoteGoesWithTheLeadWhoWroteItOrTheSponsorItWentTo() {
        param(QbParams.FEE_MONTHLY, "1000");
        NewOperator op = newOperatorWithLead(operatorsAdmin(), unique("FM15 Erase"), null);
        creditOperator(op.operatorId(), 5_000);
        String second = verifiedUser(unique("fm15-second-lead"));
        execute("INSERT INTO qb_operator_member (member_id, operator_id, user_id, lead, operator_active, added_at, "
            + "added_by, version) VALUES (gen_random_uuid()::text, ?, ?::uuid, true, true, now(), 'it', 0)",
            op.operatorId(), second);
        String byFirst = transfer(memberSession(op.leadId(), op.operatorId()), "Our fees, from Sato");
        String bySecond = transfer(memberSession(second, op.operatorId()), "Our fees, from Suzuki");

        // The second lead erased: their note goes; the first lead's stays.
        runAsSystem("ACCOUNT_ERASE", Map.of("userId", second, "requestedBy", "it-support"));
        deliverEvents();
        assertThat(note(bySecond)).isNull();
        assertThat(note(byFirst)).isEqualTo("Our fees, from Sato");

        // The direct Sponsor (the first lead) erased: every note of a transfer to them goes.
        runAsSystem("ACCOUNT_ERASE", Map.of("userId", op.leadId(), "requestedBy", "it-support"));
        deliverEvents();
        assertThat(note(byFirst)).isNull();
        assertThat(query("SELECT 1 FROM qb_operator_transfer_version WHERE operator_id = ?", op.operatorId()))
            .hasSize(2);
    }

    /** The free texts of the Sponsor's money. */
    static List<Object> texts(String sponsor) {
        List<Object> texts = new java.util.ArrayList<>();
        query("SELECT reason FROM qb_fee_charge_version WHERE sponsor_id = ? AND kind = 'MANUAL'", sponsor)
            .forEach(row -> texts.add(row.get("reason")));
        query("SELECT void_reason FROM qb_fee_invoice WHERE sponsor_id = ? AND status = ?", sponsor, QbFees.VOID)
            .forEach(row -> texts.add(row.get("void_reason")));
        query("SELECT reason FROM qb_fee_request WHERE sponsor_id = ?", sponsor)
            .forEach(row -> texts.add(row.get("reason")));
        query("SELECT manual_reason FROM qb_topup WHERE sponsor_id = ? AND method = 'MANUAL'", sponsor)
            .forEach(row -> texts.add(row.get("manual_reason")));
        assertThat(texts).as("texts of " + sponsor).hasSize(5);
        return texts;
    }

    static Object note(String transferId) {
        return query("SELECT note FROM qb_operator_transfer_version WHERE transfer_id = ?::uuid", transferId).getFirst()
            .get("note");
    }

    /** A transfer of 100 to the operator's direct Sponsor, with the lead's note; its id. */
    String transfer(String session, String note) {
        return String.valueOf(run(OperatorTransfers.TRANSFER, session, body("amount", 100, "note", note,
            "requestKey", UUID.randomUUID().toString())).get("transferId"));
    }

    /** Credits the operator's account as a month's settlement does (as OperatorTransferIT does). */
    void creditOperator(String operatorId, long amount) {
        String settlementId = UUID.randomUUID().toString();
        execute("INSERT INTO qb_operator_settlement (settlement_id, operator_id, month, period_start, period_end, "
            + "revenue, platform_share, operator_share, prospecting_cost, carried_in, payable, carried_out, status, "
            + "issued_at, revision, version) VALUES (?, ?, '2026-09', now(), now(), ?, 0.5, ?, 0, 0, ?, 0, 'CREDITED', "
            + "now(), 1, 0)", settlementId, operatorId, amount * 2, amount, amount);
        run("LEDGER_POST", TestTokens.bearer(tokens, "ledger-test", "ledger.post"), body("bookingTime",
            clock.instant().toString(), "description", "Settlement (test)", "sourceEntity", "QbOperatorSettlement",
            "sourceId", settlementId, "entries", List.of(
                body("accountCode", "5130", "direction", "DEBIT", "amount", amount),
                body("accountCode", "2140", "direction", "CREDIT", "amount", amount))));
    }
}
