package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.quizbuks.wallet.QbTopUps;
import com.jabiz.quizbuks.wallet.QbWallets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who tops up and who sees what (docs/quizbuks/plans/Q6-payments.md, D-Q6-2, D-Q6-14): only an onboarded sponsor
 * with a verified address who is not banned starts a top-up, of a whole number of Kudos within the controlled limits
 * and at most three unfinished at a time; the only thing the client gives is the amount. A sponsor sees their own
 * top-ups and checkouts only; nobody writes top-ups or wallets through the datasets, and nobody runs the system's
 * processes (ProcessAccessIT lists them).
 */
class TopUpOwnerIT extends TopUpItSupport {

    @Test
    void onlyAnOnboardedVerifiedSponsorWhoIsNotBannedTopsUp() {
        // A taker holds no top-up permission.
        assertThat(callStart(taker(u1()), 1000).expectBody(MAP).returnResult().getStatus().value()).isEqualTo(403);
        // A sponsor whose onboarding is not approved.
        String applicant = user("s-owner-applicant");
        assertThat(codes(refused("QB_TOPUP_START", sponsorToken(applicant), body("amount", 1000), 422)))
            .contains("QB_SPONSOR_NOT_APPROVED");
        // An address not verified.
        String sponsor = newSponsor("s-owner-ok");
        assertThat(refused("QB_TOPUP_START", unverifiedTokenOf(sponsor, QbRoles.SPONSOR), body("amount", 1000),
            403)).isNotNull();
        // Banned.
        String banned = newSponsor("s-owner-banned");
        run("QB_USER_BAN", tokenOf(user("owner-boss"), QbRoles.ADMIN_SUPER), body("userId", banned, "reason", "No"));
        assertThat(codes(refused("QB_TOPUP_START", sponsorToken(banned), body("amount", 1000), 422)))
            .containsExactly("QB_USER_BANNED");
        assertThat(count("qb_topup WHERE sponsor_id IN ('" + applicant + "', '" + banned + "')")).isZero();
    }

    @Test
    void theAmountIsWholeKudosWithinTheLimits() {
        String sponsor = newSponsor("s-owner-amount");
        assertThat(violations(refused("QB_TOPUP_START", sponsorToken(sponsor), body("amount", 999), 422)))
            .singleElement().satisfies(v -> assertThat(v).containsEntry("ruleCode", "QB_TOPUP_AMOUNT_RANGE"));
        assertThat(codes(refused("QB_TOPUP_START", sponsorToken(sponsor), body("amount", 1_000_001), 422)))
            .containsExactly("QB_TOPUP_AMOUNT_RANGE");
        refused("QB_TOPUP_START", sponsorToken(sponsor), body("amount", "1000.5"), 400);
        refused("QB_TOPUP_START", sponsorToken(sponsor), body("amount", -5), 400);
        assertThat(count("qb_topup WHERE sponsor_id = '" + sponsor + "'")).isZero();
        // Whatever else the client sends is not an input: the checkout's return and amount are the server's.
        run("QB_TOPUP_START", sponsorToken(sponsor), body("amount", 1000, "successUrl", "https://evil.example.com/",
            "sponsorId", u1(), "currency", "usd"));
        runJobs();
        assertThat(STRIPE.requests(SESSIONS)).singleElement().satisfies(request -> assertThat(form(request.body()))
            .containsEntry("success_url", "https://sponsor.quizbuks.test/sponsor/wallet/topups/"
                + query("SELECT topup_id FROM qb_topup WHERE sponsor_id = ?", sponsor).getFirst().get("topup_id")
                + "?checkout=paid")
            .containsEntry("metadata[sponsorId]", sponsor)
            .containsEntry("line_items[0][price_data][currency]", "jpy"));
    }

    @Test
    void atMostThreeUnfinishedTopUps() {
        String sponsor = newSponsor("s-owner-three");
        for (int i = 0; i < 3; i++) {
            startTopUp(sponsor, 1000);
        }
        assertThat(codes(refused("QB_TOPUP_START", sponsorToken(sponsor), body("amount", 1000), 422)))
            .containsExactly("QB_TOPUP_TOO_MANY_OPEN");
        // Once one expires, there is room again.
        runJobs();
        String first = query("SELECT topup_id FROM qb_topup WHERE sponsor_id = ? ORDER BY created_at, topup_id "
            + "LIMIT 1", sponsor).getFirst().get("topup_id").toString();
        deliver(event(eventId(), "checkout.session.expired", completed(first, s -> {
            s.put("status", "expired");
            s.put("payment_status", "unpaid");
        })));
        startTopUp(sponsor, 1000);
    }

    @Test
    void anExpiredCheckoutOrALostOpeningNoLongerHoldsAPlace() {
        String sponsor = newSponsor("s-owner-places");
        for (int i = 0; i < 3; i++) {
            openTopUp(sponsor, 1000);
        }
        assertThat(codes(refused("QB_TOPUP_START", sponsorToken(sponsor), body("amount", 1000), 422)))
            .containsExactly("QB_TOPUP_TOO_MANY_OPEN");
        // The checkouts expired (an hour): the places are free, whatever Stripe has said so far.
        advance(java.time.Duration.ofMinutes(61));
        String next = startTopUp(sponsor, 1000);
        runJobs();
        // An opening one whose checkout's result never came (its job finished, its completion lost) holds its place
        // for an hour only.
        execute("UPDATE qb_topup SET status = 'OPENING', checkout_session_id = NULL, checkout_url = NULL, "
            + "expires_at = NULL WHERE topup_id = ?", next);
        startTopUp(sponsor, 1000);
        startTopUp(sponsor, 1000);
        assertThat(codes(refused("QB_TOPUP_START", sponsorToken(sponsor), body("amount", 1000), 422)))
            .containsExactly("QB_TOPUP_TOO_MANY_OPEN");
        runJobs();
        advance(java.time.Duration.ofMinutes(30));
        execute("UPDATE qb_topup SET created_at = created_at - interval '2 hours' WHERE topup_id = ?", next);
        startTopUp(sponsor, 1000);
    }

    @Test
    void aSponsorSeesTheirOwnTopUpsOnly() {
        String mine = newSponsor("s-owner-mine");
        String theirs = newSponsor("s-owner-theirs");
        String myTopUp = openTopUp(mine, 1000);
        String theirTopUp = openTopUp(theirs, 2000);
        assertThat(items("qb.sponsor.topups", sponsorToken(mine), Map.of())).extracting(row -> row.get("topUpId"))
            .containsExactly(myTopUp);
        assertThat(rows(QbTopUps.SPONSOR_TOPUPS, sponsorToken(mine))).extracting(row -> row.get("topUpId"))
            .containsExactly(myTopUp);
        assertThat(items("qb.sponsor.wallet", sponsorToken(theirs), Map.of()).getFirst().get("balance")).isNotNull();
        // Administrators see every top-up, without the checkouts' addresses.
        List<Map<String, Object>> all = items("qb.admin.topups", tokenOf("owner-finance", QbRoles.ADMIN_FINANCE),
            Map.of("limit", 500));
        assertThat(all).extracting(row -> row.get("topUpId")).contains(myTopUp, theirTopUp);
        assertThat(all).allSatisfy(row -> assertThat(row).doesNotContainKey("checkoutUrl"));
        // Takers and sponsors do not read the administrators' list or every top-up.
        queryDataset(QbTopUps.defaultDataset(QbTopUps.TOPUP), sponsorToken(mine)).expectStatus().isForbidden();
        client.post().uri("/api/queries/qb.admin.topups").contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, sponsorToken(mine)).bodyValue(Map.of()).exchange()
            .expectStatus().isForbidden();
        client.post().uri("/api/queries/qb.sponsor.wallet").contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, taker(u1())).bodyValue(Map.of()).exchange()
            .expectStatus().isForbidden();
    }

    @Test
    void nobodyWritesTopUpsOrWalletsThroughTheDatasets() {
        String sponsor = newSponsor("s-owner-write");
        String topUpId = openTopUp(sponsor, 1000);
        deliver(event(eventId(), "checkout.session.completed", completed(topUpId)));
        Map<String, Object> row = topUp(topUpId);
        Map<String, Object> wallet = wallet(sponsor);
        Map<String, Map<String, Object>> writes = Map.of(
            QbTopUps.defaultDataset(QbTopUps.TOPUP), change(topUpId, row.get("version"), Map.of("amount", 1)),
            QbTopUps.SPONSOR_TOPUPS, change(topUpId, row.get("version"), Map.of("status", "OPEN")),
            QbWallets.defaultDataset(QbWallets.WALLET), change(wallet.get("wallet_id"), wallet.get("version"),
                Map.of("balance", 1_000_000)),
            QbWallets.SPONSOR_WALLETS, change(wallet.get("wallet_id"), wallet.get("version"),
                Map.of("balance", 1_000_000)));
        for (String token : List.of(sponsorToken(sponsor), admin())) {
            writes.forEach((dataset, change) -> {
                int status = client.post().uri("/api/datasets/" + dataset + "/commit")
                    .contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, token)
                    .bodyValue(Map.of("changes", List.of(change))).exchange().expectBody(MAP).returnResult()
                    .getStatus().value();
                assertThat(status).as(dataset).isBetween(400, 499);
            });
        }
        // Inserting a top-up as a sponsor is refused too.
        int insert = client.post().uri("/api/datasets/" + QbTopUps.defaultDataset(QbTopUps.TOPUP) + "/commit")
            .contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, admin())
            .bodyValue(Map.of("changes", List.of(Map.of("action", "INSERT", "attributes", Map.of("amount", 5)))))
            .exchange().expectBody(MAP).returnResult().getStatus().value();
        assertThat(insert).isBetween(400, 499);
        assertThat(topUp(topUpId)).containsEntry("status", "PAID");
        assertThat(balance(sponsor)).isEqualByComparingTo("1000");
        // The system's processes are nobody's to run, holding their permission or not.
        for (String process : List.of("QB_TOPUP_CONFIRM", "QB_TOPUP_OPENED", "QB_TOPUP_REFUNDED",
            "QB_RECONCILE_RUN")) {
            refusedAsInternal(process, admin(), Map.of());
            refusedAsInternal(process, walletSystem(), Map.of());
        }
    }

    static Map<String, Object> change(Object id, Object version, Map<String, Object> attributes) {
        return Map.of("action", "UPDATE", "id", String.valueOf(id), "version", ((Number) version).longValue(),
            "attributes", attributes);
    }
}
