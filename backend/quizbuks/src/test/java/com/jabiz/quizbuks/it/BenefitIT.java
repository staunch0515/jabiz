package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.benefit.BenefitCodes;
import com.jabiz.quizbuks.benefit.BenefitConversationProcesses;
import com.jabiz.quizbuks.benefit.OfferProcesses;
import com.jabiz.quizbuks.benefit.PurchaseProcesses;
import com.jabiz.quizbuks.benefit.PurchaseStatus;
import com.jabiz.quizbuks.benefit.QbBenefits;
import com.jabiz.quizbuks.identity.AccountCodes;
import com.jabiz.runtime.test.FileSamples;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sponsored benefits between sponsors (Q89a, docs/quizbuks/plans/Q89-sponsored-benefits.md): a provider holding
 * {@code BENEFIT_PROVIDER} lists a benefit, which only sponsors holding a benefit certification see; a sponsor holding
 * {@code BENEFIT_SPONSOR} asks about it (contact details allowed) and records what it bought and paid, with proofs;
 * the provider confirms the revision it read or sends it back; a change asks it again; purchases are only inserted.
 */
class BenefitIT extends TreeItSupport {

    /** F-S2c: the benefit role opened as production opens it: the gate's node lit by the user. */
    private void grant(String userId, String role) {
        unlock(role.equals("BENEFIT_PROVIDER") ? QbParams.TREE_GATE_BENEFIT_PROVIDER : QbParams.TREE_GATE_BENEFIT_SPONSOR,
            userId);
    }

    /** A provider holding BENEFIT_PROVIDER, a sponsor holding BENEFIT_SPONSOR and a sponsor holding neither. */
    private record Setting(String provider, String sponsor, String stranger) {}

    private Setting setting(String name) {
        String provider = newSponsor(unique("s-q89-p-" + name));
        String sponsor = newSponsor(unique("s-q89-s-" + name));
        String stranger = newSponsor(unique("s-q89-x-" + name));
        grant(provider, "BENEFIT_PROVIDER");
        grant(sponsor, "BENEFIT_SPONSOR");
        return new Setting(provider, sponsor, stranger);
    }

    private String uploadAs(String token, String policy, byte[] content, String fileName, String contentType) {
        return (String) upload(policy, content, fileName, contentType, token).expectStatus().isCreated()
            .expectBody(MAP).returnResult().getResponseBody().get("fileId");
    }

    private Map<String, Object> noodles(Object... more) {
        Map<String, Object> values = new HashMap<>(body("kind", QbBenefits.GOODS, "name", "Beef noodles",
            "description", "One bowl of beef noodles", "unitValue", 30, "pickupAddress", "Ramen B, 1-2-3 Shibuya",
            "pickupHours", "11:00-21:00", "listed", true));
        values.putAll(body(more));
        return values;
    }

    private String offer(String provider) {
        return (String) run(OfferProcesses.SAVE, sponsorToken(provider), noodles()).get("offerId");
    }

    private List<Map<String, Object>> catalog(String userId) {
        return items("qb.sponsor.benefit-catalog", sponsorToken(userId), Map.of("limit", 500));
    }

    private String today() {
        return LocalDate.ofInstant(clock.instant(), ZoneId.of("Asia/Tokyo")).toString();
    }

    private Map<String, Object> purchase(String sponsor, String offerId, Object... more) {
        Map<String, Object> values = new HashMap<>(body("offerId", offerId, "quantity", 100, "paidAmount", 3000,
            "paidOn", today()));
        values.putAll(body(more));
        return run(PurchaseProcesses.SAVE, sponsorToken(sponsor), values);
    }

    private Map<String, Object> purchaseRow(String id, String template, String userId) {
        return items(template, sponsorToken(userId), Map.of("params", Map.of("purchaseId", id))).getFirst();
    }

    private static List<Object> statusesOf(String purchaseId) {
        return query("SELECT status FROM qb_benefit_purchase_version WHERE purchase_id = CAST(? AS uuid) "
            + "ORDER BY version_no", purchaseId).stream().map(row -> row.get("status")).toList();
    }

    @Test
    void onlyAProviderListsABenefitAndOnlyCertifiedSponsorsSeeIt() {
        Setting s = setting("catalog");
        // Listing needs the certification.
        assertThat(codes(refused(OfferProcesses.SAVE, sponsorToken(s.stranger()), noodles(), 422)))
            .containsExactly(BenefitCodes.NOT_CERTIFIED);
        // Goods say where and when; the value is whole; the images are the provider's own.
        assertThat(codes(refused(OfferProcesses.SAVE, sponsorToken(s.provider()), noodles("pickupHours", " ",
            "unitValue", 29.5), 422))).containsExactlyInAnyOrder(BenefitCodes.PICKUP_REQUIRED, BenefitCodes.VALUE);
        String others = uploadAs(sponsorToken(s.stranger()), QbBenefits.IMAGE_POLICY, FileSamples.jpeg(200, 150),
            "bowl.jpg", "image/jpeg");
        assertThat(codes(refused(OfferProcesses.SAVE, sponsorToken(s.provider()), noodles("images", List.of(others)),
            422))).containsExactly(AccountCodes.FILE_NOT_OWN);
        String image = uploadAs(sponsorToken(s.provider()), QbBenefits.IMAGE_POLICY, FileSamples.jpeg(200, 150),
            "bowl.jpg", "image/jpeg");
        Map<String, Object> saved = run(OfferProcesses.SAVE, sponsorToken(s.provider()), noodles("images",
            List.of(image)));
        String offerId = (String) saved.get("offerId");
        assertThat(saved).containsEntry("status", QbBenefits.LISTED).containsEntry("revision", 1);

        // The certified sponsor sees it with the provider's company; one without a certification sees nothing.
        assertThat(catalog(s.sponsor())).filteredOn(row -> offerId.equals(row.get("offerId"))).singleElement()
            .satisfies(row -> {
                assertThat(row).containsEntry("name", "Beef noodles").containsEntry("image1", image)
                    .containsEntry("pickupHours", "11:00-21:00").doesNotContainKey("providerId");
                assertThat(row.get("providerCompany")).isNotNull();
            });
        assertThat(catalog(s.stranger())).isEmpty();
        // F-S2c: the roles open to the caller, with the node that opens each (both named here).
        assertThat(items("qb.sponsor.benefit-access", sponsorToken(s.sponsor()), Map.of()))
            .extracting(row -> row.get("certification"), row -> row.get("open"), row -> row.get("nodeId") != null)
            .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("BENEFIT_PROVIDER", false, true),
                org.assertj.core.groups.Tuple.tuple("BENEFIT_SPONSOR", true, true));

        // Hidden by its provider: off the catalog; a stale edit is refused.
        run(OfferProcesses.SAVE, sponsorToken(s.provider()), noodles("offerId", offerId, "listed", false,
            "baseRevision", 1));
        assertThat(catalog(s.sponsor())).noneMatch(row -> offerId.equals(row.get("offerId")));
        assertThat(codes(refused(OfferProcesses.SAVE, sponsorToken(s.provider()), noodles("offerId", offerId,
            "baseRevision", 1), 422))).containsExactly(BenefitCodes.OFFER_CHANGED);
        assertThat(items("qb.sponsor.benefit-offers", sponsorToken(s.provider()), Map.of())).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("status", QbBenefits.UNLISTED));
        // Another sponsor's benefit is not found for editing.
        refused(OfferProcesses.SAVE, sponsorToken(s.sponsor()), noodles("offerId", offerId), 404);
    }

    @Test
    void aSponsorAsksTheProviderWhoAnswersWithItsContactDetails() {
        Setting s = setting("talk");
        String offerId = offer(s.provider());
        // Asking needs the sponsor's certification; the provider does not write to itself.
        assertThat(codes(refused(BenefitConversationProcesses.OPEN, sponsorToken(s.stranger()), body("offerId",
            offerId, "body", "How much for 100?"), 422))).containsExactly(BenefitCodes.NOT_CERTIFIED);
        assertThat(codes(refused(BenefitConversationProcesses.OPEN, sponsorToken(s.provider()), body("offerId",
            offerId, "body", "Hello"), 422))).containsExactly(BenefitCodes.OWN_OFFER);

        Map<String, Object> opened = run(BenefitConversationProcesses.OPEN, sponsorToken(s.sponsor()),
            body("offerId", offerId, "body", "  We would like 100 bowls. How much?  "));
        assertThat(opened).containsEntry("created", true);
        String id = (String) opened.get("conversationId");
        assertThat(items("qb.sponsor.benefit-inquiries", sponsorToken(s.provider()), Map.of())).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("conversationId", id).containsEntry("unread", true)
                .containsEntry("offerName", "Beef noodles").containsEntry("lastBody", "We would like 100 bowls. How much?")
                .doesNotContainKeys("sponsorId", "providerId"));

        // The provider answers with its bank account and phone: allowed between sponsors (D-Q89-4).
        clock.advance(Duration.ofMinutes(1));
        String reply = "3000 yen. Mizuho 1234567, call 03-1234-5678";
        run(BenefitConversationProcesses.SEND, sponsorToken(s.provider()), body("conversationId", id, "body", reply));
        assertThat(items("qb.sponsor.benefit-conversations", sponsorToken(s.sponsor()), Map.of())).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("unread", true).containsEntry("lastBody", reply));
        run(BenefitConversationProcesses.READ, sponsorToken(s.sponsor()), body("conversationId", id));
        assertThat(items("qb.sponsor.benefit-conversations", sponsorToken(s.sponsor()), Map.of())).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("unread", false));
        assertThat(items("qb.sponsor.benefit-messages", sponsorToken(s.sponsor()), Map.of("params",
            Map.of("conversationId", id)))).extracting(row -> row.get("body"), row -> row.get("mine"))
            .containsExactly(org.assertj.core.groups.Tuple.tuple("We would like 100 bowls. How much?", true),
                org.assertj.core.groups.Tuple.tuple(reply, false));
        // Asking again goes into the same conversation; a stranger finds none.
        clock.advance(Duration.ofMinutes(1));
        assertThat(run(BenefitConversationProcesses.OPEN, sponsorToken(s.sponsor()), body("offerId", offerId,
            "body", "Agreed."))).containsEntry("conversationId", id).containsEntry("created", false);
        refused(BenefitConversationProcesses.SEND, sponsorToken(s.stranger()), body("conversationId", id, "body",
            "Hi"), 404);
        assertThat(items("qb.sponsor.benefit-messages", sponsorToken(s.stranger()), Map.of("params",
            Map.of("conversationId", id)))).isEmpty();
        assertThat(codes(refused(BenefitConversationProcesses.SEND, sponsorToken(s.provider()), body("conversationId",
            id, "body", " "), 422))).containsExactly(BenefitCodes.BODY_REQUIRED);
    }

    @Test
    void theProviderConfirmsWhatItReadAndAChangeAsksItAgain() {
        Setting s = setting("buy");
        String offerId = offer(s.provider());
        String sponsor = sponsorToken(s.sponsor());
        String provider = sponsorToken(s.provider());
        // Buying needs the certification, a listed benefit of another, and a payment not after today.
        assertThat(codes(refused(PurchaseProcesses.SAVE, sponsorToken(s.stranger()), body("offerId", offerId,
            "quantity", 100, "paidAmount", 3000, "paidOn", today()), 422))).containsExactly(BenefitCodes.NOT_CERTIFIED);
        assertThat(codes(refused(PurchaseProcesses.SAVE, sponsor, body("offerId", offerId, "quantity", 100,
            "paidAmount", 3000, "paidOn", LocalDate.parse(today()).plusDays(1).toString()), 422)))
            .containsExactly(BenefitCodes.PAID_ON_FUTURE);

        Map<String, Object> draft = purchase(s.sponsor(), offerId);
        String id = (String) draft.get("purchaseId");
        assertThat(draft).containsEntry("status", PurchaseStatus.DRAFT).containsEntry("revision", 1);
        // Sent to the provider only with a proof of the payment, the sponsor's own upload.
        assertThat(codes(refused(PurchaseProcesses.SUBMIT, sponsor, body("purchaseId", id), 422)))
            .containsExactly(BenefitCodes.PROOF_REQUIRED);
        String receipt = uploadAs(sponsor, QbBenefits.PROOF_POLICY, FileSamples.pdf(), "receipt.pdf",
            "application/pdf");
        run(PurchaseProcesses.SAVE, sponsor, body("purchaseId", id, "quantity", 100, "paidAmount", 3000, "paidOn",
            today(), "proofs", List.of(receipt), "note", "Paid by bank transfer", "baseRevision", 1));
        Map<String, Object> sent = run(PurchaseProcesses.SUBMIT, sponsor, body("purchaseId", id, "baseRevision", 2));
        assertThat(sent).containsEntry("status", PurchaseStatus.PENDING_PROVIDER);
        assertThat(openTasksOf(s.provider())).singleElement().satisfies(task -> assertThat(task)
            .containsEntry("title_key", PurchaseProcesses.CONFIRM_TASK_TITLE)
            .containsEntry("source_key", "qb.benefit-confirm:" + id));

        // The provider sees the benefit as saved, what was paid, the proof and the sponsor's company.
        assertThat(purchaseRow(id, "qb.sponsor.benefit-orders", s.provider())).containsEntry("title", "Beef noodles")
            .containsEntry("quantity", 100).containsEntry("paidAmount", 3000).containsEntry("proof1", receipt)
            .containsEntry("status", PurchaseStatus.PENDING_PROVIDER).containsKey("otherCompany");
        // Only the provider answers, and only the revision it read; the sponsor cannot confirm its own.
        refused(PurchaseProcesses.CONFIRM, sponsor, body("purchaseId", id, "baseRevision", 3), 404);
        refused(PurchaseProcesses.CONFIRM, sponsorToken(s.stranger()), body("purchaseId", id, "baseRevision", 3), 404);
        assertThat(codes(refused(PurchaseProcesses.CONFIRM, provider, body("purchaseId", id, "baseRevision", 2), 422)))
            .containsExactly(BenefitCodes.PURCHASE_CHANGED);
        assertThat(run(PurchaseProcesses.CONFIRM, provider, body("purchaseId", id, "baseRevision", 3)))
            .containsEntry("status", PurchaseStatus.CONFIRMED);
        assertThat(openTasksOf(s.provider())).isEmpty();
        assertThat(purchaseRow(id, "qb.sponsor.benefit-purchases", s.sponsor())).containsEntry("confirmedRevision", 3)
            .containsEntry("status", PurchaseStatus.CONFIRMED);

        // A change after the confirmation goes back to the provider, who sends it back with a reason.
        clock.advance(Duration.ofMinutes(5));
        assertThat(run(PurchaseProcesses.SAVE, sponsor, body("purchaseId", id, "quantity", 120, "paidAmount", 3600,
            "paidOn", today(), "proofs", List.of(receipt), "baseRevision", 4)))
            .containsEntry("status", PurchaseStatus.PENDING_PROVIDER);
        assertThat(openTasksOf(s.provider())).hasSize(1);
        assertThat(run(PurchaseProcesses.DECLINE, provider, body("purchaseId", id, "reason",
            "We received 3000 yen only", "baseRevision", 5))).containsEntry("status", PurchaseStatus.DRAFT);
        assertThat(openTasksOf(s.sponsor())).singleElement().satisfies(task -> assertThat(task)
            .containsEntry("title_key", PurchaseProcesses.RESUBMIT_TASK_TITLE));
        assertThat(purchaseRow(id, "qb.sponsor.benefit-purchases", s.sponsor()))
            .containsEntry("declineReason", "We received 3000 yen only");
        // Withdrawn: nothing more happens to it.
        run(PurchaseProcesses.CANCEL, sponsor, body("purchaseId", id));
        assertThat(openTasksOf(s.sponsor())).isEmpty();
        assertThat(codes(refused(PurchaseProcesses.SUBMIT, sponsor, body("purchaseId", id), 422)))
            .containsExactly(BenefitCodes.PURCHASE_STATE);

        // Every revision stays: the table was only inserted into.
        assertThat(statusesOf(id)).containsExactly(PurchaseStatus.DRAFT, PurchaseStatus.DRAFT,
            PurchaseStatus.PENDING_PROVIDER, PurchaseStatus.CONFIRMED, PurchaseStatus.PENDING_PROVIDER,
            PurchaseStatus.DRAFT, PurchaseStatus.CANCELLED);
        assertThat(query("SELECT coalesce(n_tup_upd, 0) + coalesce(n_tup_del, 0) AS changed FROM pg_stat_user_tables "
            + "WHERE schemaname = current_schema() AND relname = 'qb_benefit_purchase_version'")).singleElement()
            .satisfies(row -> assertThat(((Number) row.get("changed")).longValue()).isZero());
    }

    @Test
    void aSponsorDoesNotBuyItsOwnBenefitNorAHiddenOne() {
        Setting s = setting("own");
        grant(s.provider(), "BENEFIT_SPONSOR");
        String offerId = offer(s.provider());
        assertThat(codes(refused(PurchaseProcesses.SAVE, sponsorToken(s.provider()), body("offerId", offerId,
            "quantity", 1, "paidAmount", 0, "paidOn", today()), 422))).containsExactly(BenefitCodes.OWN_OFFER);
        run(OfferProcesses.SAVE, sponsorToken(s.provider()), noodles("offerId", offerId, "listed", false));
        assertThat(codes(refused(PurchaseProcesses.SAVE, sponsorToken(s.sponsor()), body("offerId", offerId,
            "quantity", 1, "paidAmount", 0, "paidOn", today()), 422))).containsExactly(BenefitCodes.OFFER_UNAVAILABLE);
        refused(PurchaseProcesses.SAVE, sponsorToken(s.sponsor()), body("offerId", java.util.UUID.randomUUID()
            .toString(), "quantity", 1, "paidAmount", 0, "paidOn", today()), 404);
    }
}
