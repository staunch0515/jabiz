package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.quizbuks.wallet.WalletCodes;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The top-up receipts (docs/quizbuks/plans/Q9d-journal-receipts.md, F-09): a sponsor issues the receipt of their own
 * paid top-up, in their language, and asking again gives the same one; the PDF downloaded is the bytes kept at the
 * issue, also after a refund; an unpaid top-up has none, nor is one issued before the operator's details are set; no
 * other sponsor finds it.
 */
class TopUpReceiptIT extends PayoutItSupport {

    Map<String, Object> issue(String sponsorId, String topUpId, String language) {
        return run("QB_TOPUP_RECEIPT_ISSUE", sponsorToken(sponsorId), body("topUpId", topUpId, "language", language));
    }

    byte[] pdf(String runId, String token) {
        var result = client.get().uri("/api/documents/runs/{id}/pdf", runId).header(HttpHeaders.AUTHORIZATION, token)
            .exchange().expectStatus().isOk().expectBody(byte[].class).returnResult();
        byte[] bytes = result.getResponseBody();
        assertThat(result.getResponseHeaders().getFirst("X-Jabiz-Pdf-Hash")).isEqualTo(sha256(bytes));
        return bytes;
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String text(byte[] pdf) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    String paidTopUp(String sponsorId, long amount) {
        String topUpId = openTopUp(sponsorId, amount);
        deliver(event(eventId(), "checkout.session.completed", completed(topUpId)));
        assertThat(topUp(topUpId)).containsEntry("status", "PAID");
        return topUpId;
    }

    @Test
    void aSponsorIssuesTheReceiptOfTheirOwnPaidTopUpOnce() throws Exception {
        String sponsor = newSponsor("s-receipt");
        String topUpId = paidTopUp(sponsor, 5_000);

        // Not before the operator's details are set (empty after setup).
        assertThat(codes(refused("QB_TOPUP_RECEIPT_ISSUE", sponsorToken(sponsor), body("topUpId", topUpId,
            "language", "en"), 422))).containsExactly(WalletCodes.RECEIPT_NO_ISSUER);
        param(QbParams.ISSUER_NAME, "QuizBuks Operations K.K.");
        param(QbParams.ISSUER_ADDRESS, "1-2-3 Chiyoda, Tokyo");
        param(QbParams.ISSUER_REGISTRATION_NO, "T1234567890123");

        // Only a paid top-up has one.
        String open = openTopUp(sponsor, 2_000);
        assertThat(codes(refused("QB_TOPUP_RECEIPT_ISSUE", sponsorToken(sponsor), body("topUpId", open,
            "language", "en"), 422))).containsExactly(WalletCodes.RECEIPT_NOT_PAID);

        Map<String, Object> issued = issue(sponsor, topUpId, "en");
        assertThat(issued).containsEntry("issued", true).containsEntry("documentNo", topUpId);
        byte[] first = pdf((String) issued.get("runId"), sponsorToken(sponsor));
        assertThat(new String(first, 0, 5, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        assertThat(text(first)).contains("Receipt", topUpId, "QuizBuks Operations K.K.", "T1234567890123",
            "5,000");

        // Asked again (in another language, after a refund): the same receipt, its bytes as kept.
        deliver(event(eventId(), "charge.refunded", Map.of("id", "ch_" + topUpId.replace("-", ""),
            "payment_intent", paymentOf(topUpId), "amount", 5_000, "amount_refunded", 1_000, "currency", "jpy",
            "livemode", false)));
        Map<String, Object> again = issue(sponsor, topUpId, "ja");
        assertThat(again).containsEntry("issued", false).containsEntry("runId", issued.get("runId"));
        assertThat(pdf((String) again.get("runId"), sponsorToken(sponsor))).isEqualTo(first);

        // In the sponsor's language: the title and the labels are Japanese.
        String japanese = paidTopUp(sponsor, 3_000);
        Map<String, Object> ja = issue(sponsor, japanese, "ja");
        @SuppressWarnings("unchecked")
        Map<String, Object> run = client.get().uri("/api/documents/runs/{id}", ja.get("runId"))
            .header(HttpHeaders.AUTHORIZATION, sponsorToken(sponsor)).exchange().expectStatus().isOk()
            .expectBody(MAP).returnResult().getResponseBody();
        assertThat((Map<String, Object>) run.get("run")).containsEntry("language", "ja")
            .containsEntry("title", "領収書").containsEntry("documentNo", japanese)
            .containsEntry("subjectEntity", "QbTopUp").containsEntry("subjectId", japanese);
        assertThat(String.valueOf(run.get("sources"))).contains("入金日時（UTC）", "QuizBuks Operations K.K.");

        // Another sponsor finds neither the top-up nor its receipt; the content administrator may not issue.
        String other = newSponsor("s-receipt-other");
        refused("QB_TOPUP_RECEIPT_ISSUE", sponsorToken(other), body("topUpId", topUpId, "language", "en"), 404);
        client.get().uri("/api/documents/runs/{id}/pdf", issued.get("runId"))
            .header(HttpHeaders.AUTHORIZATION, sponsorToken(other)).exchange().expectStatus().isNotFound();
        refused("QB_TOPUP_RECEIPT_ISSUE", tokenOf(user("r-content"), QbRoles.ADMIN_CONTENT), body("topUpId", topUpId,
            "language", "en"), 403);
        // An unknown language is refused before anything is read.
        refused("QB_TOPUP_RECEIPT_ISSUE", sponsorToken(sponsor), body("topUpId", topUpId, "language", "fr"), 400);

        // One receipt per top-up, only inserted.
        assertThat(query("SELECT topup_id FROM qb_topup_receipt_version ORDER BY topup_id").stream()
            .map(r -> r.get("topup_id")).toList()).containsExactlyInAnyOrderElementsOf(List.of(topUpId, japanese));
        assertThat(query("SELECT coalesce(n_tup_upd, 0) + coalesce(n_tup_del, 0) AS changed FROM pg_stat_user_tables "
            + "WHERE schemaname = current_schema() AND relname = 'qb_topup_receipt_version'").getFirst()
            .get("changed")).isEqualTo(0L);
    }
}
