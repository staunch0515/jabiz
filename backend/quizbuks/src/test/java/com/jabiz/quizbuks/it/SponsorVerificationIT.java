package com.jabiz.quizbuks.it;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetup;
import com.jabiz.job.JobDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.fee.CertifiedTopUps;
import com.jabiz.quizbuks.fee.FeeCredits;
import com.jabiz.quizbuks.operator.OperatorProcesses;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.verification.VerificationCodes;
import com.jabiz.quizbuks.verification.VerificationProcesses;
import com.jabiz.runtime.test.TestTokens;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M3 (docs/quizbuks/plans/impl/F-M.md §6): a Sponsor verifies their company by its mail domain. A public mail
 * service's domain is refused; the account's own address on the domain (a subdomain counts) goes to review at once,
 * otherwise a one-time link to an address on it (only the latest link, the Sponsor's own, before it expires); another
 * member of the Sponsor's operator reviews it; a domain is verified for one Sponsor only; a new name or domain loses the
 * verification at once; headquarters or the operator withdraw it, the platform fee Kudos kept. A verified Sponsor's
 * onboarding is the certified amount, and one verified after the onboarding is topped up to it once, while the grants'
 * switch is on (otherwise by the daily job afterwards).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "jabiz.mail.enabled=true", "jabiz.mail.from=quizbuks@example.com",
    "jabiz.mail.base-url=https://quizbuks.example.com",
    "spring.mail.host=localhost", "spring.mail.port=3053"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SponsorVerificationIT extends TreeItSupport {

    @RegisterExtension
    static final GreenMailExtension MAIL = new GreenMailExtension(new ServerSetup(3053, null,
        ServerSetup.PROTOCOL_SMTP));

    static final Pattern LINK = Pattern.compile(
        "https://quizbuks\\.example\\.com/sponsor/profile/verification/confirm\\?token=([A-Za-z0-9_-]+)");

    @Autowired
    @Qualifier("qbFeeCreditCertifiedDueJob")
    JobDefinition<FeeCredits.SponsorRef> dueJob;

    /** A Sponsor of a new operator of its own, with the sessions of its lead and of another member. */
    record Setting(String sponsor, String operatorId, String lead, String member) {}

    Setting setting(String name) {
        NewOperator operator = newOperatorWithLead(operatorsAdmin(), unique("FM3 " + name), null);
        String member = verifiedUser(unique("fm3-member"));
        run(OperatorProcesses.MEMBER_ADD, operatorsAdmin(), body("operatorId", operator.operatorId(), "userId", member,
            "lead", false));
        String sponsor = newSponsor(unique("s-fm3-" + name));
        run(OperatorProcesses.ASSIGN, operatorsAdmin(), body("userId", sponsor, "operatorId", operator.operatorId(),
            "reason", "F-M3"));
        return new Setting(sponsor, operator.operatorId(), memberSession(operator.leadId(), operator.operatorId()),
            memberSession(member, operator.operatorId()));
    }

    /** Gives the user a verified address (a new version of the platform's user, as a verification would). */
    void verifiedAddress(String userId, String address) {
        List<String> columns = query("SELECT column_name FROM information_schema.columns WHERE table_schema = "
            + "current_schema() AND table_name = 'sec_user_version' AND column_name NOT IN ('row_id', 'version_no', "
            + "'verified_email', 'email') ORDER BY ordinal_position").stream().map(row -> (String) row.get("column_name"))
            .toList();
        String list = String.join(", ", columns);
        execute("INSERT INTO sec_user_version (" + list + ", version_no, email, verified_email) SELECT " + list
            + ", version_no + 1, ?, ? FROM sec_user_version WHERE user_id = ?::uuid ORDER BY version_no DESC LIMIT 1",
            address, address, userId);
    }

    String hq() {
        return TestTokens.bearer(tokens, "fm3-hq", QbPermissions.ADMIN_VERIFICATION_READ,
            QbPermissions.VERIFICATION_REVOKE);
    }

    Map<String, Object> submit(String sponsor, String company, String domain, String address) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("companyName", company);
        input.put("domain", domain);
        input.put("confirmAddress", address);
        return run(VerificationProcesses.SUBMIT, sponsorToken(sponsor), input);
    }

    List<Object> submitRefused(String sponsor, String company, String domain, String address) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("companyName", company);
        input.put("domain", domain);
        input.put("confirmAddress", address);
        return codes(refused(VerificationProcesses.SUBMIT, sponsorToken(sponsor), input, 422));
    }

    static Map<String, Object> row(Object verificationId) {
        return query("SELECT * FROM qb_sponsor_verification WHERE verification_id = ?", verificationId).getFirst();
    }

    Map<String, Object> mine(String sponsor) {
        return items("qb.sponsor.verification", sponsorToken(sponsor), Map.of()).getFirst();
    }

    /** The token of the latest link mailed to the address. */
    String token(String address) {
        deliverEvents();
        List<MimeMessage> mails = Arrays.stream(MAIL.getReceivedMessages()).filter(m -> {
            try {
                return Arrays.stream(m.getAllRecipients()).anyMatch(r -> r.toString().contains(address));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).toList();
        assertThat(mails).as("mail to " + address).isNotEmpty();
        String body = GreenMailUtil.getBody(mails.getLast()).replace("=\r\n", "").replace("=3D", "=");
        Matcher matcher = LINK.matcher(body);
        assertThat(matcher.find()).as("link in " + body).isTrue();
        return matcher.group(1);
    }

    static List<Map<String, Object>> lots(String sponsor) {
        return query("SELECT * FROM qb_fee_credit_grant WHERE sponsor_id = ? ORDER BY granted_at, grant_id", sponsor);
    }

    /** The Sponsor's top-up lot (its key is one per Sponsor). */
    static Map<String, Object> topUp(String sponsor) {
        return query("SELECT * FROM qb_fee_credit_grant WHERE sponsor_id = ? AND source_key = ?", sponsor,
            CertifiedTopUps.KEY).getFirst();
    }

    /** The Sponsor's in-app notices of a kind. */
    static List<Map<String, Object>> notices(String userId, String kind) {
        return query("SELECT kind, action, body::text AS body FROM qb_message WHERE recipient_id = ? AND kind = ?",
            userId, kind);
    }

    void grantsSwitch(boolean on) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("paramKey", QbParams.FEE_CREDIT_GRANT_ENABLED);
        values.put("value", String.valueOf(on));
        Map<String, Object> change = run("CONTROL_CHANGE_PROPOSE", proposer(), body("targetEntity", "SysParam",
            "values", values, "reason", "F-M3 test"));
        run("CONTROL_CHANGE_PUBLISH", financeAdmin(), body("changeId", change.get("changeId")));
    }

    /** Proposes and publishes a business parameter (four eyes). */
    void param(String key, String value) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("paramKey", key);
        values.put("value", value);
        Map<String, Object> change = run("CONTROL_CHANGE_PROPOSE", proposer(), body("targetEntity", "SysParam",
            "values", values, "reason", "F-M3 test"));
        run("CONTROL_CHANGE_PUBLISH", financeAdmin(), body("changeId", change.get("changeId")));
    }

    void runDueJob() {
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        jobRunner.run(dueJob, clock.instant());
    }

    /** Verifies the Sponsor's company by the account's own address on the domain, approved by another member. */
    void verify(Setting s, String domain) {
        verifiedAddress(s.sponsor(), "owner@" + domain);
        decide(s.member(), submit(s.sponsor(), "Company " + domain, domain, null).get("approvalRequestId"), "APPROVE",
            null);
    }

    static BigDecimal granted(String sponsor) {
        return lots(sponsor).stream().map(lot -> (BigDecimal) lot.get("amount")).reduce(BigDecimal.ZERO,
            BigDecimal::add);
    }

    static List<String> ledgerLines(Object grantId) {
        return query("SELECT a.account_code, e.direction, e.amount FROM ledger_transaction_version t "
            + "JOIN ledger_entry_version e ON e.transaction_id = t.transaction_id "
            + "JOIN ledger_account_version a ON a.account_id = e.account_id "
            + "WHERE t.source_entity = 'QbFeeCreditGrant' AND t.source_id = ? ORDER BY t.process_seq_id, e.line_no",
            String.valueOf(grantId)).stream().map(p -> p.get("account_code") + " " + p.get("direction") + " "
            + ((BigDecimal) p.get("amount")).stripTrailingZeros().toPlainString()).toList();
    }

    static BigDecimal feeCreditWallet(String sponsor) {
        List<Map<String, Object>> rows = query("SELECT balance FROM qb_wallet WHERE owner_id = ? AND kind = "
            + "'FEE_CREDIT'", sponsor);
        return rows.isEmpty() ? BigDecimal.ZERO : (BigDecimal) rows.getFirst().get("balance");
    }

    List<Map<String, Object>> differences() {
        return items("qb.system.recon-fee-account", TestTokens.bearer(tokens, "fm3-recon",
            QbPermissions.WALLET_SYSTEM), Map.of());
    }

    /** The monthly report's platform fee Kudos granted from {@code from} until a day after now. */
    BigDecimal grantedInReport(java.time.Instant from) {
        String finance = tokenOf(userWithRole(unique("fm3-finance"), com.jabiz.quizbuks.setup.QbRoles.ADMIN_FINANCE),
            com.jabiz.quizbuks.setup.QbRoles.ADMIN_FINANCE);
        return items("qb.admin.monthly", finance, Map.of("params", Map.of("from", from.toString(), "to",
            clock.instant().plus(Duration.ofDays(1)).toString()))).stream()
            .filter(row -> "FEE_CREDIT_GRANTED".equals(row.get("category")))
            .map(row -> new BigDecimal(String.valueOf(row.get("feeCreditPromotion"))))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    @Order(1)
    void aPublicDomainOrAnythingButADomainIsRefused() {
        Setting s = setting("public");
        assertThat(submitRefused(s.sponsor(), "Acme", "gmail.com", "boss@gmail.com"))
            .containsExactly(VerificationCodes.PUBLIC_DOMAIN);
        assertThat(submitRefused(s.sponsor(), "Acme", "mail.qq.com", "boss@mail.qq.com"))
            .containsExactly(VerificationCodes.PUBLIC_DOMAIN);
        assertThat(submitRefused(s.sponsor(), "Acme", "https://acme.example/about", "boss@acme.example"))
            .containsExactly(VerificationCodes.DOMAIN_FORMAT);
        assertThat(submitRefused(s.sponsor(), "Acme", "acme-public.example", null))
            .containsExactly(VerificationCodes.ADDRESS_REQUIRED);
        assertThat(submitRefused(s.sponsor(), "Acme", "acme-public.example", "boss@elsewhere.example"))
            .containsExactly(VerificationCodes.ADDRESS_DOMAIN);
        // Not approved yet: not before the onboarding is.
        String applicant = userWithRole(unique("fm3-applicant"), com.jabiz.quizbuks.setup.QbRoles.SPONSOR);
        assertThat(submitRefused(applicant, "Acme", "acme-applicant.example", "boss@acme-applicant.example"))
            .containsExactly(VerificationCodes.NOT_APPROVED);
        assertThat(query("SELECT 1 FROM qb_sponsor_verification WHERE sponsor_id IN (?, ?)", s.sponsor(), applicant))
            .isEmpty();
    }

    @Test
    @Order(2)
    void theAccountsOwnAddressGoesToReviewAndAnotherMemberVerifies() {
        Setting s = setting("account");
        // A subdomain of the company's domain counts (the controller, 2026-11-10).
        verifiedAddress(s.sponsor(), "owner@tokyo.acme-account.example");
        Map<String, Object> submitted = submit(s.sponsor(), " Acme Account K.K. ", "WWW.Acme-Account.example", null);
        assertThat(submitted).containsEntry("status", "REVIEW").containsEntry("method", "ACCOUNT");
        Map<String, Object> stored = row(submitted.get("verificationId"));
        assertThat(stored).containsEntry("company_name", "Acme Account K.K.").containsEntry("domain", "acme-account.example")
            .containsEntry("operator_id", s.operatorId());
        assertThat(stored.get("confirm_address")).isNull();
        assertThat(mine(s.sponsor())).containsEntry("status", "REVIEW").containsEntry("verified", false);

        decide(s.member(), submitted.get("approvalRequestId"), "APPROVE", null);
        assertThat(row(submitted.get("verificationId"))).containsEntry("status", "APPROVED");
        assertThat(mine(s.sponsor())).containsEntry("verified", true);
        // The public dataset holds it (the public page's mark).
        assertThat(items("qb.admin.sponsor-verifications", hq(), Map.of("sponsorId", s.sponsor())))
            .singleElement().satisfies(r -> assertThat(r).containsEntry("status", "APPROVED"));
        // Another operator's member sees none of it.
        Setting other = setting("account-other");
        assertThat(items("qb.admin.sponsor-verifications", other.member(), Map.of("sponsorId", s.sponsor())))
            .isEmpty();
        // The same again is refused: verified already.
        assertThat(submitRefused(s.sponsor(), "Acme Account K.K.", "acme-account.example", null))
            .containsExactly(VerificationCodes.STATE);
    }

    @Test
    @Order(3)
    void theLinkIsTheLatestOnceAndBeforeItExpires() {
        Setting s = setting("link");
        Map<String, Object> first = submit(s.sponsor(), "Acme Link", "acme-link.example", "Info@Acme-Link.example");
        assertThat(first).containsEntry("status", "LINK_SENT").containsEntry("method", "LINK");
        String old = token("info@acme-link.example");
        // Sent again: the same row, a new link; the earlier one no longer works.
        Map<String, Object> again = submit(s.sponsor(), "Acme Link", "acme-link.example", "info@acme-link.example");
        assertThat(again.get("verificationId")).isEqualTo(first.get("verificationId"));
        String latest = token("info@acme-link.example");
        assertThat(latest).isNotEqualTo(old);
        refused(VerificationProcesses.CONFIRM, sponsorToken(s.sponsor()), body("token", old), 422);
        // Another account cannot use it.
        Setting other = setting("link-other");
        refused(VerificationProcesses.CONFIRM, sponsorToken(other.sponsor()), body("token", latest), 422);

        Map<String, Object> confirmed = run(VerificationProcesses.CONFIRM, sponsorToken(s.sponsor()),
            body("token", latest));
        assertThat(confirmed).containsEntry("status", "REVIEW");
        assertThat(row(first.get("verificationId"))).containsEntry("confirm_address", "info@acme-link.example");
        // Once.
        refused(VerificationProcesses.CONFIRM, sponsorToken(s.sponsor()), body("token", latest), 422);
        // The Sponsor cannot approve their own.
        refused("APPROVAL_DECIDE", sponsorToken(s.sponsor()), body("requestId", confirmed.get("approvalRequestId"),
            "decision", "APPROVE"), 403);
        decide(s.member(), confirmed.get("approvalRequestId"), "REJECT", "The site names another company");
        assertThat(row(first.get("verificationId"))).containsEntry("status", "REJECTED")
            .containsEntry("reject_reason", "The site names another company");
        assertThat(notices(s.sponsor(), "VERIFICATION_REJECTED")).singleElement().satisfies(n ->
            assertThat(n.get("body").toString()).contains("The site names another company"));
    }

    @Test
    @Order(4)
    void anExpiredLinkDoesNotWork() {
        Setting s = setting("expired");
        submit(s.sponsor(), "Acme Expired", "acme-expired.example", "info@acme-expired.example");
        String token = token("info@acme-expired.example");
        advance(Duration.ofHours(49));
        assertThat(codes(refused(VerificationProcesses.CONFIRM, sponsorToken(s.sponsor()), body("token", token), 422)))
            .containsExactly(VerificationCodes.LINK_INVALID);
        assertThat(mine(s.sponsor())).containsEntry("status", "LINK_SENT");
    }

    @Test
    @Order(5)
    void oneDomainIsVerifiedForOneSponsorOnly() {
        Setting a = setting("domain-a");
        Setting b = setting("domain-b");
        verifiedAddress(a.sponsor(), "a@acme-domain.example");
        verifiedAddress(b.sponsor(), "b@acme-domain.example");
        Map<String, Object> first = submit(a.sponsor(), "Acme Domain", "acme-domain.example", null);
        Map<String, Object> second = submit(b.sponsor(), "Acme Domain", "acme-domain.example", null);
        decide(a.member(), first.get("approvalRequestId"), "APPROVE", null);
        // The second, approved after: sent back, the domain is another's.
        decide(b.member(), second.get("approvalRequestId"), "APPROVE", null);
        assertThat(row(second.get("verificationId"))).containsEntry("status", "REJECTED")
            .containsEntry("reject_reason", VerificationCodes.DOMAIN_TAKEN);
        // And refused at once afterwards.
        assertThat(submitRefused(b.sponsor(), "Acme Domain", "acme-domain.example", null))
            .containsExactly(VerificationCodes.DOMAIN_TAKEN);
    }

    @Test
    @Order(6)
    void aNewNameLosesTheVerificationAndAWithdrawalKeepsTheKudos() {
        Setting s = setting("change");
        verifiedAddress(s.sponsor(), "owner@acme-change.example");
        Map<String, Object> first = submit(s.sponsor(), "Acme Change", "acme-change.example", null);
        decide(s.member(), first.get("approvalRequestId"), "APPROVE", null);
        Map<String, Object> renamed = submit(s.sponsor(), "Acme Change Holdings", "acme-change.example", null);
        assertThat(row(first.get("verificationId"))).containsEntry("status", "SUPERSEDED");
        assertThat(mine(s.sponsor())).containsEntry("verified", false).containsEntry("status", "REVIEW");
        decide(s.member(), renamed.get("approvalRequestId"), "APPROVE", null);
        assertThat(mine(s.sponsor())).containsEntry("verified", true);

        // Withdrawn by headquarters, with a reason; another operator's member cannot.
        Setting other = setting("change-other");
        refused(VerificationProcesses.REVOKE, other.member(), body("sponsorId", s.sponsor(), "reason", "No"), 404);
        Map<String, Object> revoked = run(VerificationProcesses.REVOKE, hq(), body("sponsorId", s.sponsor(), "reason",
            "The domain lapsed"));
        assertThat(revoked).containsEntry("status", "REVOKED");
        assertThat(row(renamed.get("verificationId"))).containsEntry("revoke_reason", "The domain lapsed");
        assertThat(mine(s.sponsor())).containsEntry("verified", false);
        deliverEvents();
        assertThat(notices(s.sponsor(), "VERIFICATION_APPROVED")).hasSize(2);
        assertThat(notices(s.sponsor(), "VERIFICATION_REVOKED")).singleElement().satisfies(n ->
            assertThat(n.get("body").toString()).contains("The domain lapsed"));
        assertThat(codes(refused(VerificationProcesses.REVOKE, s.member(), body("sponsorId", s.sponsor(), "reason",
            "Again"), 422))).containsExactly(VerificationCodes.STATE);
    }

    @Test
    @Order(7)
    void theOnboardingIsToppedUpOnceWhileTheSwitchIsOn() {
        grantsSwitch(true);
        // Granted the uncertified amount, then verified: topped up to the certified one, once.
        Setting s = setting("top-up");
        run(FeeCredits.ONBOARDING, s.lead(), body("sponsorId", s.sponsor()));
        assertThat(lots(s.sponsor())).singleElement().satisfies(lot ->
            assertThat((BigDecimal) lot.get("amount")).isEqualByComparingTo("1000"));
        verifiedAddress(s.sponsor(), "owner@acme-topup.example");
        decide(s.member(), submit(s.sponsor(), "Acme Top", "acme-topup.example", null).get("approvalRequestId"),
            "APPROVE", null);
        assertThat(lots(s.sponsor())).hasSize(2);
        assertThat(topUp(s.sponsor())).satisfies(lot -> {
            assertThat(lot).containsEntry("source", "CERTIFIED_TOP_UP");
            assertThat((BigDecimal) lot.get("amount")).isEqualByComparingTo("9000");
        });
        assertThat(mine(s.sponsor())).containsEntry("toppedUp", true);
        // Withdrawn and verified again: nothing more.
        run(VerificationProcesses.REVOKE, hq(), body("sponsorId", s.sponsor(), "reason", "Checked again"));
        decide(s.member(), submit(s.sponsor(), "Acme Top", "acme-topup.example", null).get("approvalRequestId"),
            "APPROVE", null);
        assertThat(lots(s.sponsor())).hasSize(2);

        // Verified before the onboarding: the certified amount at once.
        Setting early = setting("early");
        verifiedAddress(early.sponsor(), "owner@acme-early.example");
        decide(early.member(), submit(early.sponsor(), "Acme Early", "acme-early.example", null)
            .get("approvalRequestId"), "APPROVE", null);
        assertThat(lots(early.sponsor())).isEmpty();
        run(FeeCredits.ONBOARDING, early.lead(), body("sponsorId", early.sponsor()));
        assertThat(lots(early.sponsor())).singleElement().satisfies(lot ->
            assertThat((BigDecimal) lot.get("amount")).isEqualByComparingTo("10000"));
    }

    @Test
    @Order(8)
    void withTheSwitchOffTheTopUpWaitsForTheDailyJob() {
        grantsSwitch(true);
        Setting s = setting("later");
        run(FeeCredits.ONBOARDING, s.lead(), body("sponsorId", s.sponsor()));
        grantsSwitch(false);
        verifiedAddress(s.sponsor(), "owner@acme-later.example");
        decide(s.member(), submit(s.sponsor(), "Acme Later", "acme-later.example", null).get("approvalRequestId"),
            "APPROVE", null);
        assertThat(lots(s.sponsor())).hasSize(1);
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        jobRunner.run(dueJob, clock.instant());
        assertThat(lots(s.sponsor())).hasSize(1);

        grantsSwitch(true);
        advance(Duration.ofDays(1));
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        jobRunner.run(dueJob, clock.instant());
        assertThat(lots(s.sponsor())).hasSize(2);
        assertThat((BigDecimal) topUp(s.sponsor()).get("amount")).isEqualByComparingTo("9000");
    }

    @Test
    @Order(9)
    void raisingTheCertifiedAmountTopsUpNothingGrantedBefore() {
        grantsSwitch(true);
        // Granted certified at 10000: the lot records it.
        Setting early = setting("raise-early");
        verify(early, "acme-raise-early.example");
        run(FeeCredits.ONBOARDING, early.lead(), body("sponsorId", early.sponsor()));
        assertThat(lots(early.sponsor())).singleElement().satisfies(lot -> {
            assertThat((BigDecimal) lot.get("amount")).isEqualByComparingTo("10000");
            assertThat((BigDecimal) lot.get("certified_amount")).isEqualByComparingTo("10000");
        });
        // Granted basic while the certified amount was 10000: the lot records 10000.
        Setting late = setting("raise-late");
        run(FeeCredits.ONBOARDING, late.lead(), body("sponsorId", late.sponsor()));
        assertThat((BigDecimal) lots(late.sponsor()).getFirst().get("certified_amount")).isEqualByComparingTo("10000");

        param(QbParams.FEE_CREDIT_GRANT_CERTIFIED, "15000");
        try {
            runDueJob();
            assertThat(lots(early.sponsor())).hasSize(1);
            // Verified after the raise: topped up to the 10000 recorded, not to 15000.
            verify(late, "acme-raise-late.example");
            assertThat((BigDecimal) topUp(late.sponsor()).get("amount")).isEqualByComparingTo("9000");
            assertThat((BigDecimal) topUp(late.sponsor()).get("certified_amount")).isEqualByComparingTo("10000");
            assertThat(granted(late.sponsor())).isEqualByComparingTo("10000");
            assertThat(mine(late.sponsor())).satisfies(row ->
                assertThat(new BigDecimal(String.valueOf(row.get("certifiedAmount")))).isEqualByComparingTo("10000"));
            runDueJob();
            assertThat(lots(early.sponsor())).hasSize(1);
            assertThat(lots(late.sponsor())).hasSize(2);
            assertThat(differences()).isEmpty();
        } finally {
            param(QbParams.FEE_CREDIT_GRANT_CERTIFIED, "10000");
        }
    }

    @Test
    @Order(10)
    void aBannedSponsorIsToppedUpByTheJobOnceUnbanned() {
        grantsSwitch(true);
        Setting s = setting("banned");
        run(FeeCredits.ONBOARDING, s.lead(), body("sponsorId", s.sponsor()));
        verifiedAddress(s.sponsor(), "owner@acme-banned.example");
        Map<String, Object> submitted = submit(s.sponsor(), "Acme Banned", "acme-banned.example", null);
        String boss = userWithRole(unique("fm3-boss"), com.jabiz.quizbuks.setup.QbRoles.ADMIN_SUPER);
        String bossToken = tokenOf(boss, com.jabiz.quizbuks.setup.QbRoles.ADMIN_SUPER);
        run("QB_USER_BAN", bossToken, body("userId", s.sponsor(), "reason", "Fraud"));
        decide(s.member(), submitted.get("approvalRequestId"), "APPROVE", null);
        // Verified, but nothing while banned: neither the approval nor the job.
        assertThat(row(submitted.get("verificationId"))).containsEntry("status", "APPROVED");
        assertThat(lots(s.sponsor())).hasSize(1);
        runDueJob();
        assertThat(lots(s.sponsor())).hasSize(1);

        run(com.jabiz.quizbuks.identity.BanProcesses.UNBAN, bossToken, body("userId", s.sponsor(), "note", "Cleared"));
        deliverEvents();
        advance(Duration.ofDays(1));
        runDueJob();
        assertThat((BigDecimal) topUp(s.sponsor()).get("amount")).isEqualByComparingTo("9000");
        assertThat(granted(s.sponsor())).isEqualByComparingTo("10000");
    }

    @Test
    @Order(11)
    void revokedBeforeTheJobNothingThenOnceWhenVerifiedAgain() {
        grantsSwitch(true);
        Setting s = setting("revoked");
        run(FeeCredits.ONBOARDING, s.lead(), body("sponsorId", s.sponsor()));
        grantsSwitch(false);
        verify(s, "acme-revoked.example");
        assertThat(lots(s.sponsor())).hasSize(1);
        run(VerificationProcesses.REVOKE, hq(), body("sponsorId", s.sponsor(), "reason", "Not theirs"));
        deliverEvents();
        grantsSwitch(true);
        runDueJob();
        assertThat(lots(s.sponsor())).hasSize(1);

        // Verified again with the switch on: topped up once, by the approval; the job adds nothing.
        decide(s.member(), submit(s.sponsor(), "Company acme-revoked.example", "acme-revoked.example", null)
            .get("approvalRequestId"), "APPROVE", null);
        assertThat(lots(s.sponsor())).hasSize(2);
        advance(Duration.ofDays(1));
        runDueJob();
        assertThat(lots(s.sponsor())).hasSize(2);
        assertThat(granted(s.sponsor())).isEqualByComparingTo("10000");
    }

    @Test
    @Order(12)
    void anOnboardingAtTheCertifiedAmountOrMoreIsNeverToppedUp() {
        grantsSwitch(true);
        // The basic amount as high as the certified one when granted.
        param(QbParams.FEE_CREDIT_GRANT_BASIC, "12000");
        Setting s = setting("above");
        try {
            run(FeeCredits.ONBOARDING, s.lead(), body("sponsorId", s.sponsor()));
        } finally {
            param(QbParams.FEE_CREDIT_GRANT_BASIC, "1000");
        }
        assertThat((BigDecimal) lots(s.sponsor()).getFirst().get("amount")).isEqualByComparingTo("12000");
        verify(s, "acme-above.example");
        assertThat(lots(s.sponsor())).hasSize(1);
        assertThat(mine(s.sponsor())).containsEntry("toppedUp", false);
        advance(Duration.ofDays(1));
        runDueJob();
        assertThat(lots(s.sponsor())).hasSize(1);
    }

    @Test
    @Order(13)
    void theTopUpPostsAndReconcilesAndIsInTheMonthlyReport() {
        grantsSwitch(true);
        Setting s = setting("ledger");
        run(FeeCredits.ONBOARDING, s.lead(), body("sponsorId", s.sponsor()));
        java.time.Instant from = clock.instant().minus(Duration.ofDays(1));
        BigDecimal before = grantedInReport(from);
        verify(s, "acme-ledger.example");
        Map<String, Object> lot = topUp(s.sponsor());
        assertThat(ledgerLines(lot.get("grant_id"))).containsExactly("5140 DEBIT 9000", "2190 CREDIT 9000");
        assertThat(feeCreditWallet(s.sponsor())).isEqualByComparingTo("10000");
        assertThat(ledger("2190", s.sponsor())).isEqualByComparingTo("10000");
        assertThat(differences()).isEmpty();
        assertThat(grantedInReport(from).subtract(before)).isEqualByComparingTo("9000");
    }

    @Test
    @Order(14)
    void theApprovalAndTheOnboardingAtOnceGrantTheCertifiedAmountOnce() throws Exception {
        grantsSwitch(true);
        for (int i = 0; i < 3; i++) {
            Setting s = setting("race-" + i);
            verifiedAddress(s.sponsor(), "owner@acme-race-" + i + ".example");
            Object requestId = submit(s.sponsor(), "Acme Race", "acme-race-" + i + ".example", null)
                .get("approvalRequestId");
            java.util.concurrent.CompletableFuture<Void> approval = java.util.concurrent.CompletableFuture.runAsync(
                () -> decide(s.member(), requestId, "APPROVE", null));
            java.util.concurrent.CompletableFuture<Void> onboarding = java.util.concurrent.CompletableFuture.runAsync(
                () -> run(FeeCredits.ONBOARDING, s.lead(), body("sponsorId", s.sponsor())));
            approval.get();
            onboarding.get();
            deliverEvents();
            runDueJob();
            assertThat(lots(s.sponsor())).hasSizeBetween(1, 2);
            assertThat(granted(s.sponsor())).isEqualByComparingTo("10000");
        }
        assertThat(differences()).isEmpty();
    }

    @Test
    @Order(15)
    void aFailedTopUpLeavesTheVerificationAndTheJobGrantsIt() {
        grantsSwitch(true);
        Setting s = setting("failed");
        run(FeeCredits.ONBOARDING, s.lead(), body("sponsorId", s.sponsor()));
        // A validity the grant refuses (0 days): the top-up fails, the verification stands.
        param(QbParams.FEE_CREDIT_VALID_DAYS, "0");
        try {
            verifiedAddress(s.sponsor(), "owner@acme-failed.example");
            Map<String, Object> submitted = submit(s.sponsor(), "Acme Failed", "acme-failed.example", null);
            decide(s.member(), submitted.get("approvalRequestId"), "APPROVE", null);
            assertThat(row(submitted.get("verificationId"))).containsEntry("status", "APPROVED");
            assertThat(mine(s.sponsor())).containsEntry("verified", true).containsEntry("toppedUp", false);
            assertThat(lots(s.sponsor())).hasSize(1);
        } finally {
            param(QbParams.FEE_CREDIT_VALID_DAYS, "90");
        }
        advance(Duration.ofDays(1));
        runDueJob();
        assertThat((BigDecimal) topUp(s.sponsor()).get("amount")).isEqualByComparingTo("9000");
        // The event retried later finds it done.
        advance(Duration.ofHours(2));
        deliverEvents();
        assertThat(lots(s.sponsor())).hasSize(2);
        assertThat(differences()).isEmpty();
    }

    @Test
    @Order(16)
    void theCheckFindsATopUpNeverVerifiedOrAboveTheCertifiedAmount() {
        grantsSwitch(true);
        Setting never = setting("never");
        run(FeeCredits.ONBOARDING, never.lead(), body("sponsorId", never.sponsor()));
        Setting over = setting("over");
        run(FeeCredits.ONBOARDING, over.lead(), body("sponsorId", over.sponsor()));
        // Lots the processes would never write: inserted by hand, removed again afterwards.
        String copy = "INSERT INTO qb_fee_credit_grant (grant_id, sponsor_id, source, source_key, operator_id, amount, "
            + "remaining, voided_amount, granted_at, expires_at, certified_amount, version) SELECT ?, sponsor_id, "
            + "'CERTIFIED_TOP_UP', 'certified', operator_id, ?, ?, 0, granted_at, expires_at, 10000, 1 "
            + "FROM qb_fee_credit_grant WHERE sponsor_id = ? AND source_key = 'onboarding'";
        String a = java.util.UUID.randomUUID().toString();
        String b = java.util.UUID.randomUUID().toString();
        execute(copy, a, new BigDecimal("9000"), new BigDecimal("9000"), never.sponsor());
        execute(copy, b, new BigDecimal("9500"), new BigDecimal("9500"), over.sponsor());
        verifiedAddress(over.sponsor(), "owner@acme-over.example");
        decide(over.member(), submit(over.sponsor(), "Acme Over", "acme-over.example", null).get("approvalRequestId"),
            "APPROVE", null);
        try {
            List<Map<String, Object>> found = differences().stream()
                .filter(row -> "CERTIFIED_GRANTS".equals(row.get("checkCode"))).toList();
            assertThat(found).extracting(row -> row.get("sponsorId"))
                .containsExactlyInAnyOrder(never.sponsor(), over.sponsor());
            assertThat(found).filteredOn(row -> over.sponsor().equals(row.get("sponsorId"))).singleElement()
                .satisfies(row -> {
                    assertThat(new BigDecimal(String.valueOf(row.get("expected")))).isEqualByComparingTo("10000");
                    assertThat(new BigDecimal(String.valueOf(row.get("actual")))).isEqualByComparingTo("10500");
                });
        } finally {
            execute("DELETE FROM qb_fee_credit_grant WHERE grant_id IN (?, ?)", a, b);
        }
    }
}
