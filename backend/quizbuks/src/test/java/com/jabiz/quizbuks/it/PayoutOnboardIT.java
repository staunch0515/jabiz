package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.wallet.WalletCodes;
import com.jabiz.runtime.test.StubHttpServer;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Payout accounts (U-53; docs/quizbuks/plans/Q6-payments.md, D-Q6-10): a taker of the deployment's country opens a
 * Stripe Express account through a job (one account per user, whatever repeats), gets a link to Stripe's onboarding
 * that only they read, and the account follows Stripe's signed reports of the Connect endpoint, the newest only.
 * No Stripe account is ever recorded for a second user.
 */
class PayoutOnboardIT extends PayoutItSupport {

    @Test
    void aTakerOpensAPayoutAccountAndStripeEnablesIt() {
        String taker = payee("t-onboard");
        Map<String, Object> started = run("QB_PAYOUT_ONBOARD", taker(taker), body());
        assertThat(started).containsKey("jobId");
        runJobs();
        // The account was created for the user, once, with the user's own key; then the link.
        StubHttpServer.Received create = STRIPE.requests("/v1/accounts").getFirst();
        assertThat(create.header("Idempotency-Key")).isEqualTo("qb-connect-" + taker);
        assertThat(form(create.body())).containsEntry("type", "express").containsEntry("country", "JP")
            .containsEntry("capabilities[transfers][requested]", "true").containsEntry("metadata[userId]", taker);
        Map<String, String> link = form(STRIPE.requests("/v1/account_links").getFirst().body());
        assertThat(link).containsEntry("type", "account_onboarding")
            .containsEntry("return_url", "https://app.quizbuks.test/wallet/payout?onboarding=done")
            .containsEntry("refresh_url", "https://app.quizbuks.test/wallet/payout?onboarding=refresh");
        Map<String, Object> account = payoutAccount(taker);
        assertThat(account).containsEntry("status", "PENDING").containsEntry("payouts_enabled", false);
        String accountId = (String) account.get("external_account_id");
        assertThat(accountId).startsWith("acct_test");

        // Only the taker reads their link.
        List<Map<String, Object>> mine = items("qb.app.payout-account", taker(taker),
            Map.of("params", Map.of("now", now().toString())));
        assertThat(mine).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("status", "PENDING");
            assertThat((String) row.get("onboardingUrl")).startsWith("https://connect.stripe.com/setup/e/");
        });
        assertThat(items("qb.app.payout-account", taker(payee("t-onboard-other")),
            Map.of("params", Map.of("now", now().toString())))).isEmpty();
        // Not even finance: whoever follows a pending account's link could put their own bank account on it.
        String finance = tokenOf(userWithRole(unique("finance"), com.jabiz.quizbuks.setup.QbRoles.ADMIN_FINANCE),
            com.jabiz.quizbuks.setup.QbRoles.ADMIN_FINANCE);
        queryDataset(com.jabiz.quizbuks.wallet.QbPayouts.defaultDataset(
            com.jabiz.quizbuks.wallet.QbPayouts.ONBOARDING), finance).expectStatus().isForbidden();

        // Asked again (the link used or expired): the same account, a new link.
        openPayoutAccount(taker);
        assertThat(STRIPE.requests("/v1/accounts")).hasSize(1);
        assertThat(form(STRIPE.requests("/v1/account_links").get(1).body())).containsEntry("account", accountId);

        // Stripe enables it.
        deliverConnect(connectEvent("account.updated", accountId, account(accountId, taker, true)));
        assertThat(payoutAccount(taker)).containsEntry("status", "ENABLED").containsEntry("payouts_enabled", true);
    }

    @Test
    void onlyTheNewestReportCounts() {
        String taker = payee("t-onboard-order");
        openPayoutAccount(taker);
        String accountId = stripeAccountOf(taker);
        Map<String, Object> older = connectEvent("account.updated", accountId, account(accountId, taker, true));
        advance(Duration.ofMinutes(1));
        Map<String, Object> newer = connectEvent("account.updated", accountId, account(accountId, taker, false));
        deliverConnect(newer);
        assertThat(payoutAccount(taker)).containsEntry("status", "RESTRICTED");
        // The older report arrives late: ignored.
        deliverConnect(older);
        assertThat(payoutAccount(taker)).containsEntry("status", "RESTRICTED")
            .containsEntry("requirements_due", "individual.id_number");
        // Disconnected: final, a later report changes nothing, and the taker cannot open it again.
        deliverConnect(connectEvent("account.application.deauthorized", accountId,
            Map.of("id", "ca_app", "object", "application")));
        assertThat(payoutAccount(taker)).containsEntry("status", "DEAUTHORIZED").containsEntry("payouts_enabled",
            false);
        advance(Duration.ofMinutes(1));
        deliverConnect(connectEvent("account.updated", accountId, account(accountId, taker, true)));
        assertThat(payoutAccount(taker)).containsEntry("status", "DEAUTHORIZED");
        assertThat(codes(refused("QB_PAYOUT_ONBOARD", taker(taker), body(), 422)))
            .containsExactly(WalletCodes.PAYOUT_ACCOUNT_CLOSED);
    }

    @Test
    void onlyTakersOfTheCountryOpenOne() {
        String abroad = newTaker("t-onboard-us", "US");
        assertThat(codes(refused("QB_PAYOUT_ONBOARD", taker(abroad), body(), 422)))
            .containsExactly(WalletCodes.PAYOUT_COUNTRY_UNSUPPORTED);
        String unknown = newTaker("t-onboard-none", null);
        assertThat(codes(refused("QB_PAYOUT_ONBOARD", taker(unknown), body(), 422)))
            .containsExactly(WalletCodes.PAYOUT_COUNTRY_UNSUPPORTED);
        assertThat(STRIPE.requests("/v1/accounts")).isEmpty();
    }

    @Test
    void aStripeAccountIsNeverGivenToASecondUser() {
        String first = payee("t-onboard-first");
        openPayoutAccount(first);
        String accountId = stripeAccountOf(first);
        // Stripe (or a forged answer) gives the second user the first one's account: a task, nothing recorded.
        String second = payee("t-onboard-second");
        StripeSim.ACCOUNTS_BY_KEY.put("qb-connect-" + second, accountId);
        openPayoutAccount(second);
        assertThat(payoutAccount(second)).isNull();
        assertThat(query("SELECT 1 FROM qb_payout_onboarding WHERE user_id = ?", second)).isEmpty();
        assertThat(tasksAbout("payout-account:" + second)).extracting(t -> t.get("title_key"))
            .containsExactly("task.qb.payout-check.ACCOUNT_TAKEN");
        // An event naming the first one's account with the second user as its owner changes nothing.
        deliverConnect(connectEvent("account.updated", accountId, account(accountId, second, true)));
        assertThat(payoutAccount(first)).containsEntry("status", "PENDING").containsEntry("user_id", first);
    }

    @Test
    void eventsOfUnknownAccountsWaitAndThenBecomeTasks() {
        Map<String, Object> event = connectEvent("account.updated", "acct_nobody",
            account("acct_nobody", "u-nobody", true));
        deliverConnect(event);
        assertThat(tasksAbout("stripe:" + event.get("id"))).isEmpty();
        advance(Duration.ofMinutes(31));
        deliverEvents();
        assertThat(tasksAbout("stripe:" + event.get("id"))).extracting(t -> t.get("title_key"))
            .containsExactly("task.qb.payout-check.UNKNOWN_ACCOUNT");
        // The other mode: a task, nothing changed.
        String taker = payee("t-onboard-live");
        openPayoutAccount(taker);
        String accountId = stripeAccountOf(taker);
        Map<String, Object> live = connectEvent("account.updated", accountId, account(accountId, taker, true));
        live.put("livemode", true);
        deliverConnect(live);
        assertThat(payoutAccount(taker)).containsEntry("status", "PENDING");
        assertThat(tasksAbout("stripe:" + live.get("id"))).extracting(t -> t.get("title_key"))
            .containsExactly("task.qb.payout-check.LIVEMODE");
    }

    @Test
    void anAccountThatNamesTheOtherModeIsRefused() {
        // Stripe's v1 Account has no livemode, so the event's mode decides; one that does name a mode must agree.
        String taker = payee("t-onboard-account-live");
        openPayoutAccount(taker);
        String accountId = stripeAccountOf(taker);
        Map<String, Object> object = account(accountId, taker, true);
        object.put("livemode", true);
        Map<String, Object> event = connectEvent("account.updated", accountId, object);
        deliverConnect(event);
        assertThat(payoutAccount(taker)).containsEntry("status", "PENDING");
        assertThat(tasksAbout("stripe:" + event.get("id"))).extracting(t -> t.get("title_key"))
            .containsExactly("task.qb.payout-check.LIVEMODE");
    }

    @Test
    void connectEventsNeedTheConnectSecret() {
        String taker = payee("t-onboard-forged");
        openPayoutAccount(taker);
        String accountId = stripeAccountOf(taker);
        // Signed with the account endpoint's secret (or any other): refused, nothing stored.
        byte[] body = json.writeValueAsBytes(connectEvent("account.updated", accountId,
            account(accountId, taker, true)));
        String signature = new com.jabiz.inbound.StripeSignature().sign(SECRET, clock.instant().getEpochSecond(),
            body).get(com.jabiz.inbound.StripeSignature.HEADER);
        client.post().uri(CONNECT_INBOUND).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .header(com.jabiz.inbound.StripeSignature.HEADER, signature).bodyValue(body).exchange()
            .expectStatus().isBadRequest();
        deliverEvents();
        assertThat(payoutAccount(taker)).containsEntry("status", "PENDING");
    }
}
