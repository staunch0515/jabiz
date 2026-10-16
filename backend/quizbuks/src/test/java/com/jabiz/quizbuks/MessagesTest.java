package com.jabiz.quizbuks;

import com.jabiz.quizbuks.content.ContentCodes;
import com.jabiz.quizbuks.identity.AccountCodes;
import com.jabiz.quizbuks.play.PlayCodes;
import com.jabiz.quizbuks.setup.ReviewCodes;
import com.jabiz.quizbuks.publishing.PublishingCodes;
import com.jabiz.quizbuks.wallet.RefundMath;
import com.jabiz.quizbuks.wallet.TopUpChecks;
import com.jabiz.quizbuks.wallet.WalletCodes;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three languages have the same texts (backend/quizbuks/CLAUDE.md section 4): the platform's startup check covers
 * the rule codes; this covers every key, and the content codes of processes as well.
 */
class MessagesTest {

    private static Properties load(String language) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = new InputStreamReader(Objects.requireNonNull(MessagesTest.class.getResourceAsStream(
            "/messages_" + language + ".properties")), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    @Test
    void everyLanguageHasEveryText() throws IOException {
        Properties en = load("en");
        for (String language : List.of("zh", "ja")) {
            assertThat(load(language).stringPropertyNames()).as(language)
                .containsExactlyInAnyOrderElementsOf(en.stringPropertyNames());
        }
        assertThat(en.stringPropertyNames()).containsAll(ContentCodes.ALL).containsAll(PublishingCodes.ALL)
            .contains("approval.subject.qb.publication", "qb.publication.copy-name")
            .containsAll(AccountCodes.ALL).containsAll(ReviewCodes.ALL)
            .contains("approval.subject.qb.sponsor", "task.qb.sponsor.resubmit", "process.QB_PROFILE_SAVE",
                "process.QB_SPONSOR_APPLY", "process.QB_SPONSOR_PROFILE_SAVE", "process.QB_SPONSOR_APPROVAL_RESULT",
                "process.QB_USER_BAN", "process.QB_USER_UNBAN", "mail.qb.welcome.subject", "mail.qb.welcome.body",
                "mail.qb.sponsor.approved.subject", "mail.qb.sponsor.approved.body",
                "mail.qb.sponsor.rejected.subject", "mail.qb.sponsor.rejected.body", "query.qb.me.sign-ins",
                "query.qb.admin.users", "query.qb.admin.sponsors", "entity.QbProfile", "entity.QbSponsor",
                "entity.QbUserBan", "param.qb.review.sponsor.auto");
        assertThat(en.stringPropertyNames()).containsAll(PlayCodes.ALL)
            .contains("process.QB_VIEW", "process.QB_ATTEMPT_START", "process.QB_ATTEMPT_SAVE",
                "process.QB_ATTEMPT_SUBMIT", "process.QB_ATTEMPT_EXPIRE", "process.QB_PENDING_SETTLE",
                "process.QB_PENDING_PAY", "process.QB_PUBLICATION_FUNDS_RESTORED", "process.QB_PUBLICATION_FUNDED",
                "process.QB_PUBLICATION_STATS_REFRESH", "process.QB_WALLET_DEV_CREDIT", "entity.QbAttempt",
                "entity.QbPublicationCard", "entity.QbPublicationView", "entity.QbPublicationStats",
                "entity.QbWallet", "query.qb.app.feed", "query.qb.app.publication",
                "query.qb.app.publication-tiers", "query.qb.app.history", "query.qb.admin.attempts",
                "query.qb.admin.wallets", "query.qb.system.publication-stats");
        // Q6a: top-ups, refunds, disputes and the reconciliation; one task title per reason.
        assertThat(en.stringPropertyNames()).containsAll(WalletCodes.ALL)
            .containsAll(TopUpChecks.REASONS.stream().map(r -> "task.qb.topup-check." + r).toList())
            .containsAll(RefundMath.REASONS.stream().map(r -> "task.qb.refund-check." + r).toList())
            .contains("task.qb.recon-diff", "param.qb.topup.min", "param.qb.topup.max", "entity.QbTopUp",
                "entity.QbReconRun", "entity.QbReconItem", "process.QB_TOPUP_START", "process.QB_TOPUP_OPENED",
                "process.QB_TOPUP_OPEN_FAILED", "process.QB_TOPUP_CONFIRM", "process.QB_TOPUP_EXPIRE",
                "process.QB_TOPUP_CHECK", "process.QB_TOPUP_CHECKED", "process.QB_TOPUP_CHECK_FAILED",
                "process.QB_TOPUP_FEE_RECORD", "process.QB_TOPUP_FEE_FAILED", "process.QB_TOPUP_REFUNDED",
                "process.QB_TOPUP_DISPUTE_OPENED", "process.QB_TOPUP_DISPUTE_CLOSED", "process.QB_RECONCILE_RUN",
                "query.qb.sponsor.wallet", "query.qb.sponsor.topups", "query.qb.app.wallet", "query.qb.app.ledger",
                "query.qb.admin.topups", "query.qb.admin.recon-runs", "query.qb.admin.recon-items");
        // Q6b: payout accounts, transfers and the bank payouts.
        assertThat(en.stringPropertyNames())
            .containsAll(com.jabiz.quizbuks.wallet.PayoutChecks.ACCOUNT_REASONS.stream()
                .map(r -> "task.qb.payout-check." + r).toList())
            .containsAll(com.jabiz.quizbuks.wallet.PayoutChecks.TRANSFER_REASONS.stream()
                .map(r -> "task.qb.transfer-check." + r).toList())
            .contains("approval.subject.qb.transfer", "param.qb.transfer.fee", "entity.QbPayoutAccount",
                "entity.QbPayoutOnboarding", "entity.QbTransfer", "entity.QbStripePayout", "process.QB_PAYOUT_ONBOARD",
                "process.QB_PAYOUT_ONBOARD_READY", "process.QB_PAYOUT_ONBOARD_FAILED",
                "process.QB_PAYOUT_ACCOUNT_UPDATED", "process.QB_PAYOUT_ACCOUNT_DEAUTHORIZED",
                "process.QB_TAKER_WALLET_SYNC", "process.QB_TAKER_WALLET_RESYNC", "process.QB_TRANSFER_CREATE",
                "process.QB_TRANSFER_APPROVAL_RESULT", "process.QB_TRANSFER_SENT", "process.QB_TRANSFER_SEND_FAILED",
                "process.QB_TRANSFER_CHECK", "process.QB_TRANSFER_CHECKED", "process.QB_TRANSFER_CHECK_FAILED",
                "process.QB_TRANSFER_REVERSED", "process.QB_STRIPE_PAYOUT_RECORD", "process.QB_STRIPE_PAYOUT_FAILED",
                "query.qb.app.payout-account", "query.qb.admin.transfers", "query.qb.admin.payout-accounts",
                "task.qb.topup-check.FEE_CURRENCY");
        // Q9b: the sponsor's transactions and their totals.
        assertThat(en.stringPropertyNames()).contains("query.qb.sponsor.transactions",
            "query.qb.sponsor.transactions.amount", "query.qb.sponsor.transactions.userName",
            "query.qb.sponsor.transaction-summary", "query.qb.sponsor.transaction-summary.topUpRefundedAmount");
        assertThat(en.stringPropertyNames()).allSatisfy(key -> assertThat(en.getProperty(key)).as(key).isNotBlank());
    }
}
