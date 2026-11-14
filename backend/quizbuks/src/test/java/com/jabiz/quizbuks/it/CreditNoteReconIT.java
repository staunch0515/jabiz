package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.fee.AudienceRefundJob;
import com.jabiz.quizbuks.fee.FeeProcesses;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M14b sweep (docs/quizbuks/plans/impl/F-M14.md §7, b): the audience fee's and the credit notes' daily checks
 * (qb.system.recon-credit-notes, F-M7) that no test had made report. Each: nothing while the books agree, the code once
 * a difference is planted. Ordinary tables are changed behind the processes' back; the append-only ones (notes, their
 * uses, the charges) get a forged version, as only a defect or a hand on the database could make them.
 */
class CreditNoteReconIT extends CreditNoteItSupport {

    /** What the credit notes' template finds about the sponsor, the refund's hours as the reconciliation passes them. */
    List<String> codes(String sponsor) {
        Map<String, Object> params = new HashMap<>();
        params.put("at", clock.instant().toString());
        params.put("refundHours", paramValue(QbParams.AUDIENCE_REFUND_MISSING_AFTER_HOURS));
        return items("qb.system.recon-credit-notes", TestTokens.bearer(tokens, "fm14b-recon",
            QbPermissions.WALLET_SYSTEM), Map.of("params", params)).stream()
            .filter(row -> sponsor.equals(String.valueOf(row.get("sponsorId"))))
            .map(row -> String.valueOf(row.get("checkCode"))).toList();
    }

    /**
     * Copies of an append-only row under new ids (registered as entities of {@code entity}), with some columns replaced
     * by SQL expressions over the original's: what a defect writing it twice would leave. The rows themselves obey every
     * constraint of the table (a note's parts add up, its number is unique).
     */
    static void copy(String table, String key, String entity, Object id, int times, Map<String, String> replaced) {
        List<String> columns = query("SELECT column_name FROM information_schema.columns WHERE table_schema = "
            + "current_schema() AND table_name = ? AND column_name <> 'row_id' ORDER BY ordinal_position", table)
            .stream().map(row -> String.valueOf(row.get("column_name"))).toList();
        for (int i = 1; i <= times; i++) {
            String n = String.valueOf(i);
            Map<String, String> values = new HashMap<>();
            replaced.forEach((column, expression) -> values.put(column, expression.replace("#", n)));
            values.put(key, "r.entity_id");
            values.put("version_no", "1");
            String select = columns.stream().map(c -> values.getOrDefault(c, "o." + c)).collect(Collectors.joining(", "));
            execute("WITH r AS (INSERT INTO entity_registry (entity_id, entity_type, created_seq_id) SELECT "
                + "gen_random_uuid(), ?, max(process_seq_id) FROM op_process RETURNING entity_id) "
                + "INSERT INTO " + table + " (" + String.join(", ", columns) + ") SELECT " + select + " FROM " + table
                + " o, r WHERE o." + key + " = ?::uuid", entity, String.valueOf(id));
        }
    }

    /** A use of the note on no invoice of anyone's, as {@code CreditNoteIT} forges one. */
    void forgedUse(Object noteId, String sponsor, long amount) {
        Object seq = query("SELECT max(process_seq_id) AS s FROM op_process").getFirst().get("s");
        java.sql.Timestamp now = java.sql.Timestamp.from(clock.instant());
        execute("WITH registered AS (INSERT INTO entity_registry (entity_id, entity_type, created_seq_id) "
            + "VALUES (gen_random_uuid(), 'QbFeeCreditNoteUse', ?) RETURNING entity_id) "
            + "INSERT INTO qb_fee_credit_note_use_version (use_id, version_no, effect_start_time, created_time, "
            + "process_seq_id, credit_note_id, sponsor_id, invoice_id, amount, used_at) "
            + "SELECT entity_id, 1, ?, ?, ?, ?, ?, gen_random_uuid()::text, ?, ? FROM registered",
            seq, now, now, seq, noteId, sponsor, amount, now);
    }

    /** A sponsor's monthly fee, paid from the cash, and a credit note of 400 on it: the books agree. */
    Map<String, Object> notedSponsor(String name) {
        String sponsor = newSponsor(unique(name));
        cash(sponsor, 5_000);
        assertThat(monthly(sponsor)).isEqualTo("CHARGED");
        creditNote(monthlyCharge(sponsor).get("charge_id"), 400);
        assertThat(codes(sponsor)).isEmpty();
        return notes(sponsor).getLast();
    }

    @Test
    void aNoteGivingBackMoreThanItsTotalOrNotesAboveTheirFeeAreReported() {
        param(QbParams.FEE_MONTHLY, "1000");
        // CREDIT_NOTE_USES: the note gave its total back in cash, and a use of 1 more is recorded.
        Map<String, Object> uses = notedSponsor("s-fm14b-uses");
        String usesSponsor = String.valueOf(uses.get("sponsor_id"));
        forgedUse(uses.get("credit_note_id"), usesSponsor, 1);
        assertThat(codes(usesSponsor)).containsExactly("CREDIT_NOTE_USES");
        // CREDIT_NOTE_LIMITS: two more notes of 400 on the fee of 1000: 1200 of notes on it.
        Map<String, Object> limits = notedSponsor("s-fm14b-limits");
        copy("qb_fee_credit_note_version", "credit_note_id", "QbFeeCreditNote", limits.get("credit_note_id"), 2,
            Map.of("credit_note_no", "o.credit_note_no || '-F#'", "source_key", "o.source_key || '-F#'"));
        assertThat(codes(String.valueOf(limits.get("sponsor_id")))).containsExactly("CREDIT_NOTE_LIMITS");
    }

    @Test
    void aUseOnAVoidedInvoiceAndCreditLeftBesideAnUnpaidInvoiceAreReported() {
        param(QbParams.FEE_MONTHLY, "1000");
        // A postpaid fee owed, a note of 500 on it (credit), and the month's invoice that takes it first: 550 of 1100.
        String sponsor = newSponsor(unique("s-fm14b-void"));
        billing(sponsor, "POSTPAID", 5_000);
        assertThat(monthly(sponsor)).isEqualTo("CHARGED");
        creditNote(monthlyCharge(sponsor).get("charge_id"), 500);
        assertThat(invoiceTheMonth(sponsor)).isEqualTo("ISSUED");
        Map<String, Object> invoice = query("SELECT * FROM qb_fee_invoice WHERE sponsor_id = ? AND charge_id IS NULL",
            sponsor).getFirst();
        assertThat(invoice.get("status")).isEqualTo("PARTIAL");
        assertThat(codes(sponsor)).isEmpty();
        // CREDIT_NOTE_USE_VOID: the invoice the note was used on is voided behind the processes' back.
        String invoiceId = String.valueOf(invoice.get("invoice_id"));
        execute("UPDATE qb_fee_invoice SET status = 'VOID' WHERE invoice_id = ?", invoiceId);
        try {
            assertThat(codes(sponsor)).containsExactly("CREDIT_NOTE_USE_VOID");
        } finally {
            execute("UPDATE qb_fee_invoice SET status = 'PARTIAL' WHERE invoice_id = ?", invoiceId);
        }
        assertThat(codes(sponsor)).isEmpty();
        // CREDIT_UNUSED_WITH_DUE: another note's 550 of credit left while the invoice still owes 550.
        copy("qb_fee_credit_note_version", "credit_note_id", "QbFeeCreditNote",
            notes(sponsor).getFirst().get("credit_note_id"), 1,
            Map.of("credit_note_no", "o.credit_note_no || '-F#'", "source_key", "o.source_key || '-F#'"));
        assertThat(codes(sponsor)).containsExactly("CREDIT_UNUSED_WITH_DUE");
    }

    @Test
    void anAudienceFeeNotDecidedAfterTheHoursOrForAPublicationThatNeverWentOnIsReported() {
        param(QbParams.AUDIENCE_REFUND_UNUSED_DAYS, "true");
        autoApprove(true);
        String sponsor = pricedSponsor("fm14b-audience");
        cash(sponsor, 1_000);
        credit(sponsor, 10_000);
        String publicationId = oneWinnerAudienceDraft(sponsor, "FM14b audience");
        submit(sponsorToken(sponsor), publicationId);
        goLive(publicationId);
        assertThat(codes(sponsor)).isEmpty();
        // AUDIENCE_REFUND_MISSING: ended, and its unused days not decided past qb.audience.refund.missing-after-hours.
        run("QB_PUBLICATION_ADMIN_END", TestTokens.bearer(tokens, "fm14b-content", QbPermissions.ADMIN_CONTENT_WRITE),
            body("publicationId", publicationId, "reason", "F-M14b test"));
        int hours = Integer.parseInt(String.valueOf(paramValue(QbParams.AUDIENCE_REFUND_MISSING_AFTER_HOURS)));
        clock.set(clock.instant().plus(Duration.ofHours(hours - 1)));
        assertThat(codes(sponsor)).isEmpty();
        clock.set(clock.instant().plus(Duration.ofHours(2)));
        assertThat(codes(sponsor)).containsExactly("AUDIENCE_REFUND_MISSING");
        Map<String, Object> ref = body("prepaymentId", String.valueOf(prepayments(publicationId).getFirst()
            .get("prepayment_id")), "publicationId", publicationId, "sponsorId", sponsor, "endedAt",
            clock.instant().toString());
        assertThat(((FeeProcesses.ItemOutput) systemRun(AudienceRefundJob.ITEM, ref)).outcome()).isNotNull();
        assertThat(codes(sponsor)).doesNotContain("AUDIENCE_REFUND_MISSING");
        List<String> before = new ArrayList<>(codes(sponsor));
        // AUDIENCE_FEE_NOT_LIVE: the fee's publication has no card (as if it never went on); the card is put back.
        execute("CREATE TABLE fm14b_card AS SELECT * FROM qb_publication_card WHERE publication_id = ?::uuid",
            publicationId);
        execute("DELETE FROM qb_publication_card WHERE publication_id = ?::uuid", publicationId);
        try {
            assertThat(codes(sponsor)).containsAll(before).contains("AUDIENCE_FEE_NOT_LIVE");
        } finally {
            execute("INSERT INTO qb_publication_card SELECT * FROM fm14b_card");
            execute("DROP TABLE fm14b_card");
        }
        assertThat(codes(sponsor)).doesNotContain("AUDIENCE_FEE_NOT_LIVE");
    }
}
