package com.jabiz.quizbuks.it;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.fee.DistributionPrices;
import com.jabiz.quizbuks.fee.FeeCodes;
import com.jabiz.quizbuks.fee.FeeCredits;
import com.jabiz.quizbuks.fee.FeeProcesses;
import com.jabiz.quizbuks.fee.FeeRequests;
import com.jabiz.quizbuks.fee.QbFees;
import com.jabiz.quizbuks.identity.BanProcesses;
import com.jabiz.quizbuks.operator.OperatorProcesses;
import com.jabiz.quizbuks.operator.OperatorWithdrawals;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.quizbuks.wallet.QbWallets;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.HoldLock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F-M15 a0 (docs/quizbuks/plans/F-M15.md §5.4, §10): what a ban stops on the money's side. The operators' acts that
 * had no ban check refuse a banned caller whose session is still valid, write nothing, and work once unbanned; the ones
 * already checked stay so. No platform fee Kudos are granted to a banned Sponsor (a lot their key already has is still
 * given back), a grant by hand is not asked for them and one in review when they are banned ends rejected, and they
 * are not reminded of their lots expiring.
 */
class BanMoneyIT extends CreditNoteItSupport {

    /** A test-only root that runs the grant as its sub-process under the Sponsor's wallet lock, as its callers do. */
    static final String GRANT_RELAY = "IT_QB_FEE_CREDIT_GRANT_RELAY";

    @TestConfiguration
    static class Processes {

        @Bean
        ProcessDefinition<FeeCredits.GrantInput, FeeCredits.GrantOutput, ProcessContext> itFeeCreditGrantRelay() {
            return ProcessDefinition.define(GRANT_RELAY, 1, FeeCredits.GrantInput.class, FeeCredits.GrantOutput.class,
                ProcessContext.class, pb -> pb
                    .permissions(QbPermissions.FEE_SYSTEM)
                    .internal()
                    .contextFactory((start, input) -> {
                        ProcessContext ctx = new ProcessContext(start);
                        ctx.put("in", input);
                        return ctx;
                    })
                    .outputMapper(ctx -> ctx.get("out", FeeCredits.GrantOutput.class))
                    .step("Lock the wallet", HoldLock.<ProcessContext>exclusive(ctx -> QbWallets.lockName(
                        QbWallets.SPONSOR, ctx.get("in", FeeCredits.GrantInput.class).sponsorId())))
                    .step("Grant", CallProcess.<ProcessContext>of(FeeCredits.GRANT, 1, ctx -> ctx.get("in"), "out")));
        }
    }

    // ---- the grant ----------------------------------------------------------------------------------------------

    @Test
    void aBannedSponsorIsGrantedNothingNewButTheirKeyGivesBackItsLot() {
        String sponsor = newSponsor(unique("s-fm15-grant"));
        FeeCredits.GrantOutput first = grantRelay(sponsor, "it:first", 30);
        assertThat(first.granted()).isTrue();
        String boss = bossToken();
        run("QB_USER_BAN", boss, body("userId", sponsor, "reason", "Fraud"));

        // The same key: the lot granted before the ban, nothing more.
        FeeCredits.GrantOutput again = grantRelay(sponsor, "it:first", 30);
        assertThat(again.granted()).isFalse();
        assertThat(again.grantId()).isEqualTo(first.grantId());
        // A new key: refused.
        assertThatThrownBy(() -> grantRelay(sponsor, "it:second", 30))
            .isInstanceOfSatisfying(BusinessRuleViolationException.class, e -> assertThat(e.violations())
                .extracting(v -> v.ruleCode()).containsExactly(FeeCodes.FEE_CREDIT_BANNED));
        assertThat(lots(sponsor)).hasSize(1);
        assertThat(balance(sponsor, QbWallets.FEE_CREDIT)).isEqualByComparingTo("500");

        run(BanProcesses.UNBAN, boss, body("userId", sponsor, "note", "Cleared"));
        assertThat(grantRelay(sponsor, "it:second", 30).granted()).isTrue();
        assertThat(lots(sponsor)).hasSize(2);
    }

    @Test
    void aGrantByHandIsNotAskedForABannedSponsorAndOneInReviewIsRejected() {
        String sponsor = newSponsor(unique("s-fm15-request"));
        Map<String, Object> inReview = run(FeeRequests.RECORD, finance(), body("kind", QbFees.REQUEST_CREDIT,
            "sponsorId", sponsor, "amount", 800, "reason", "Event partner"));
        assertThat(inReview).containsEntry("status", QbFees.REVIEW);
        String boss = bossToken();
        run("QB_USER_BAN", boss, body("userId", sponsor, "reason", "Fraud"));

        // Asked for while banned: refused at once; a fee by hand still is asked for.
        assertThat(codes(refused(FeeRequests.RECORD, finance(), body("kind", QbFees.REQUEST_CREDIT, "sponsorId",
            sponsor, "amount", 500, "reason", "Again"), 422))).containsExactly(FeeCodes.FEE_CREDIT_BANNED);
        assertThat(run(FeeRequests.RECORD, finance(), body("kind", QbFees.REQUEST_MANUAL, "sponsorId", sponsor,
            "amount", 300, "reason", "Custom report"))).containsEntry("status", QbFees.REVIEW);

        // Approved while banned: rejected, not stuck, nothing granted.
        run("APPROVAL_DECIDE", content(), body("requestId", inReview.get("approvalRequestId"), "decision", "APPROVE"));
        deliverEvents();
        assertThat(query("SELECT status, grant_id FROM qb_fee_request WHERE request_id = ?",
            inReview.get("requestId")).getFirst()).containsEntry("status", QbFees.REJECTED)
            .containsEntry("grant_id", null);
        assertThat(lots(sponsor)).isEmpty();
        assertThat(ledger("2190", sponsor)).isEqualByComparingTo("0");
    }

    @Test
    void aBannedSponsorIsNotRemindedOfLotsExpiring() {
        String sponsor = newSponsor(unique("s-fm15-remind"));
        grantRelay(sponsor, "it:soon", 3);
        String boss = bossToken();
        run("QB_USER_BAN", boss, body("userId", sponsor, "reason", "Fraud"));
        assertThat(((FeeProcesses.ItemOutput) systemRun(FeeCredits.REMIND_ITEM, body("sponsorId", sponsor)))
            .outcome()).isEqualTo("BANNED");
        deliverEvents();
        assertThat(templatesTo(sponsor)).doesNotContain("qb.fee-credit.expiring");
        assertThat(lots(sponsor).getFirst().get("reminded_at")).isNull();

        // Unbanned: told, the lot marked.
        run(BanProcesses.UNBAN, boss, body("userId", sponsor, "note", "Cleared"));
        assertThat(((FeeProcesses.ItemOutput) systemRun(FeeCredits.REMIND_ITEM, body("sponsorId", sponsor)))
            .outcome()).isEqualTo("REMINDED");
        deliverEvents();
        assertThat(templatesTo(sponsor)).contains("qb.fee-credit.expiring");
        assertThat(lots(sponsor).getFirst().get("reminded_at")).isNotNull();
    }

    // ---- the operators' acts ------------------------------------------------------------------------------------

    /**
     * A lead banned while their session is valid: the onboarding, the billing mode, the bank account, the
     * withdrawal's request and cancel and a member's removal are refused (the gaps closed), as the distribution price
     * already was; unbanned, they work.
     */
    @Test
    void aBannedLeadMovesNoMoney() {
        param(QbParams.FEE_CREDIT_GRANT_ENABLED, "true");
        NewOperator operator = newOperatorWithLead(operatorsAdmin(), unique("FM15 Banned lead"), null);
        String operatorId = operator.operatorId();
        String sponsor = newSponsor(unique("s-fm15-ops"));
        run(OperatorProcesses.ASSIGN, operatorsAdmin(), body("userId", sponsor, "operatorId", operatorId, "reason",
            "F-M15"));
        String member = verifiedUser(unique("fm15-staff"));
        run(OperatorProcesses.MEMBER_ADD, operatorsAdmin(), body("operatorId", operatorId, "userId", member, "lead",
            false));
        execute("INSERT INTO qb_operator_withdrawal (withdrawal_id, operator_id, amount, status, requested_by, "
            + "requested_at, revision, version) VALUES (gen_random_uuid()::text, ?, 5, 'REQUESTED', 'it', now(), 1, 0)",
            operatorId);
        String withdrawalId = String.valueOf(query("SELECT withdrawal_id FROM qb_operator_withdrawal WHERE "
            + "operator_id = ?", operatorId).getFirst().get("withdrawal_id"));
        Object billingBefore = billingMode(sponsor);

        String lead = memberSession(operator.leadId(), operatorId);
        String boss = bossToken();
        run("QB_USER_BAN", boss, body("userId", operator.leadId(), "reason", "Fraud"));

        assertThat(codes(refused(FeeCredits.ONBOARDING, lead, body("sponsorId", sponsor), 422)))
            .containsExactly("QB_USER_BANNED");
        assertThat(codes(refused(FeeProcesses.BILLING_SET, lead, billing(sponsor), 422)))
            .containsExactly("QB_USER_BANNED");
        assertThat(codes(refused(OperatorWithdrawals.BANK_ACCOUNT_SAVE, lead, bankAccount(), 422)))
            .containsExactly("QB_USER_BANNED");
        assertThat(codes(refused(OperatorWithdrawals.REQUEST, lead, body("amount", 3), 422)))
            .containsExactly("QB_USER_BANNED");
        assertThat(codes(refused(OperatorWithdrawals.CANCEL, lead, body("withdrawalId", withdrawalId), 422)))
            .containsExactly("QB_USER_BANNED");
        assertThat(codes(refused(OperatorProcesses.MEMBER_DROP, lead, body("userId", member), 422)))
            .containsExactly("QB_USER_BANNED");
        assertThat(codes(refused(DistributionPrices.OPERATOR_SET, lead, body("rate", "0.05", "unitFloor", "1",
            "unitCeiling", "20", "minimumDeposit", 500, "reason", "F-M15"), 422))).containsExactly("QB_USER_BANNED");
        // Nothing written.
        assertThat(lots(sponsor)).isEmpty();
        assertThat(billingMode(sponsor)).isEqualTo(billingBefore);
        assertThat(query("SELECT 1 FROM qb_operator_bank_account WHERE operator_id = ?", operatorId)).isEmpty();
        assertThat(query("SELECT status FROM qb_operator_withdrawal WHERE operator_id = ?", operatorId))
            .singleElement().satisfies(row -> assertThat(row).containsEntry("status", OperatorWithdrawals.REQUESTED));
        assertThat(query("SELECT 1 FROM qb_operator_member WHERE operator_id = ? AND user_id = ?::uuid "
            + "AND operator_active", operatorId, member)).hasSize(1);

        run(BanProcesses.UNBAN, boss, body("userId", operator.leadId(), "note", "Cleared"));
        assertThat(run(FeeCredits.ONBOARDING, lead, body("sponsorId", sponsor))).containsEntry("granted", true);
        assertThat(run(FeeProcesses.BILLING_SET, lead, billing(sponsor))).containsEntry("changed", true);
        run(OperatorWithdrawals.BANK_ACCOUNT_SAVE, lead, bankAccount());
        // The request is the lead's again: refused now only for the account's cooling-off.
        assertThat(codes(refused(OperatorWithdrawals.REQUEST, lead, body("amount", 3), 422)))
            .doesNotContain("QB_USER_BANNED");
        assertThat(run(OperatorWithdrawals.CANCEL, lead, body("withdrawalId", withdrawalId)))
            .containsEntry("status", OperatorWithdrawals.CANCELLED);
        assertThat(run(OperatorProcesses.MEMBER_DROP, lead, body("userId", member))).containsEntry("changed", true);
    }

    /** Already refused (F-M15 a0 §10-6): a banned Sponsor sells no Kudos; a banned taker withdraws nothing. */
    @Test
    void aBannedSponsorOrTakerStillMovesNoMoney() {
        switches();
        String sponsor = newSponsor(unique("s-fm15-sell"));
        String taker = newTaker(unique("t-fm15-withdraw"), "JP");
        String boss = bossToken();
        run("QB_USER_BAN", boss, body("userId", sponsor, "reason", "Fraud"));
        run("QB_USER_BAN", boss, body("userId", taker, "reason", "Fraud"));
        assertThat(codes(refused("QB_KUDOS_SELL", sponsorToken(sponsor), body("amount", 500), 422)))
            .containsExactly("QB_USER_BANNED");
        assertThat(codes(refused("QB_WITHDRAWAL_REQUEST", taker(taker), body("amount", 2000), 422)))
            .containsExactly("QB_USER_BANNED");
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    String bossToken() {
        return tokenOf(userWithRole(unique("fm15-boss"), QbRoles.ADMIN_SUPER), QbRoles.ADMIN_SUPER);
    }

    /** A lot of 500 for {@code key}, valid for {@code days}, granted as the platform's callers grant it. */
    FeeCredits.GrantOutput grantRelay(String sponsor, String key, int days) {
        return (FeeCredits.GrantOutput) systemRun(GRANT_RELAY, body("sponsorId", sponsor, "source", "MANUAL",
            "sourceKey", key, "amount", 500, "validDays", days));
    }

    static List<Map<String, Object>> lots(String sponsor) {
        return query("SELECT * FROM qb_fee_credit_grant WHERE sponsor_id = ? ORDER BY granted_at, grant_id", sponsor);
    }

    static Object billingMode(String sponsor) {
        return latest("qb_sponsor_version", "onboarding_id", "user_id = '" + sponsor + "'").getFirst()
            .get("billing_mode");
    }

    static Map<String, Object> billing(String sponsor) {
        return body("userId", sponsor, "billingMode", "POSTPAID", "creditLimit", 20000, "reason", "Trusted");
    }

    static Map<String, Object> bankAccount() {
        return body("bankCode", "0005", "bankName", "MUFG", "branchCode", "001", "branchName", "Head office",
            "accountType", "ORDINARY", "accountNumber", "1234567", "accountHolder", "カ）トウホク");
    }
}
