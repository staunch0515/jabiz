package com.jabiz.quizbuks.it;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetup;
import com.jabiz.quizbuks.messaging.MailProcesses;
import com.jabiz.quizbuks.publishing.PublicationAdmin;
import com.jabiz.quizbuks.publishing.PublicationNotices;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.runtime.context.Actor;
import com.jabiz.runtime.test.TestTokens;
import jakarta.mail.BodyPart;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The mail of Q7a against a local SMTP server (docs/quizbuks/plans/Q7-messages-legal.md D-Q7-1 – D-Q7-3, Q7-12):
 * each template is sent once on the move that triggers it, in the recipient's language, with links to the right front
 * end ({@code {entryUrl.app}} at {@code /}, {@code {entryUrl.sponsor}} at {@code /sponsor/}); the short-of-funds mail
 * at most once a day per publication; a notification the sponsor turned off is recorded as skipped while the
 * transactional mail is always sent; each front end lists only its own notifications; moves the sponsor made
 * themselves, a ban's pause and an account that was never enabled send nothing; old events send nothing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "jabiz.mail.enabled=true", "jabiz.mail.from=quizbuks@example.com",
    "jabiz.mail.base-url=https://quizbuks.example.com",
    "spring.mail.host=localhost", "spring.mail.port=3047"})
class NotificationMailIT extends PayoutItSupport {

    @RegisterExtension
    static final GreenMailExtension MAIL = new GreenMailExtension(new ServerSetup(3047, null,
        ServerSetup.PROTOCOL_SMTP));

    private static final String APP = "https://quizbuks.example.com/";
    private static final String SPONSOR = "https://quizbuks.example.com/sponsor/";

    // ---- helpers ---------------------------------------------------------------------------------------------------

    /** A user's own token at a sign-in entry (the mail preferences list the entry's notifications, D49). */
    private String entryToken(String userId, String entry) {
        return "Bearer " + tokens.issue(new Actor(userId, null, Set.of(), Set.of(), clock.instant(), null, entry,
            true)).token();
    }

    /** The content administrator's token (passed a second factor, as the takedown processes ask). */
    private String contentAdminToken() {
        return TestTokens.bearer(tokens, "content-admin-mail", QbPermissions.ADMIN_CONTENT_WRITE);
    }

    private static String addressOf(String name) {
        return name.toLowerCase() + "@example.com";
    }

    /** A name no earlier test of the class used. */
    private static String fresh(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private void mailLanguage(String userId, String entry, String locale) {
        client.post().uri("/api/auth/account/locale").contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, entryToken(userId, entry)).bodyValue(Map.of("locale", locale))
            .exchange().expectStatus().is2xxSuccessful();
    }

    /** The mail delivered to an address, oldest first. */
    private static List<MimeMessage> received(String address) {
        return Arrays.stream(MAIL.getReceivedMessages()).filter(message -> {
            try {
                return message.getAllRecipients()[0].toString().equalsIgnoreCase(address);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).toList();
    }

    private static String subject(MimeMessage message) {
        try {
            return message.getSubject();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String text(MimeMessage message) {
        try {
            return part(message);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String part(Part part) throws Exception {
        if (part.isMimeType("text/plain")) {
            return String.valueOf(part.getContent());
        }
        if (part.getContent() instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart child = multipart.getBodyPart(i);
                String found = part(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** The mail recorded for a user with the outcome of its last attempt, oldest first. */
    private static List<Map<String, Object>> outcomes(String userId) {
        return query("""
            SELECT m.template, m.locale, (SELECT a.outcome FROM sys_mail_attempt a WHERE a.message_id = m.message_id
                ORDER BY a.attempt_no DESC LIMIT 1) AS outcome
            FROM sys_mail_message m WHERE m.user_id = ?::uuid ORDER BY m.created_time, m.message_id""", userId);
    }

    private static List<Object> sent(String userId, String template) {
        return outcomes(userId).stream().filter(row -> template.equals(row.get("template")))
            .map(row -> row.get("outcome")).toList();
    }

    /** Two takers start (the wallet holds one reward), then both answer everything right: the second is pending. */
    private void twoCompletions(String publicationId) {
        String first = taker(newTaker(fresh("t-short"), null));
        String second = taker(newTaker(fresh("t-short"), null));
        String a = (String) start(first, publicationId).get("attemptId");
        String b = (String) start(second, publicationId).get("attemptId");
        clock.advance(Duration.ofSeconds(1));
        submit(first, a, answers(true, true));
        clock.advance(Duration.ofSeconds(1));
        submit(second, b, answers(true, true));
    }

    /** The publication mail of a user (not the onboarding's), oldest first. */
    private static List<Object> publicationTemplates(String userId) {
        return outcomes(userId).stream().map(row -> row.get("template"))
            .filter(template -> String.valueOf(template).startsWith("qb.publication.")).toList();
    }

    /** The subjects of the publication mail delivered to an address (not the onboarding's), oldest first. */
    private static List<MimeMessage> publicationMail(String address) {
        return received(address).stream().filter(message -> !subject(message).contains("sponsor account")).toList();
    }

    private static long statusEvents(String publicationId) {
        return outboxEvents(PublicationNotices.EVENT, "publicationId", publicationId);
    }

    // ---- takers ----------------------------------------------------------------------------------------------------

    @Test
    void aTakerIsMailedTheirTransferSentInTheirLanguageAndAFailedOneWithWhatToDo() {
        param(QbParams.TRANSFER_FEE, "0");
        param(QbParams.TRANSFER_REVIEW_ABOVE, "50000");
        transfersOn();
        String name = fresh("t-mail-sent");
        String taker = payee(name);
        mailLanguage(taker, "app", "ja");
        enabledPayee(taker);
        earn(taker, 1500);
        runTransfers();
        runJobs();
        assertThat(sent(taker, "qb.transfer.sent")).containsExactly("SENT");
        MimeMessage mail = received(addressOf(name)).stream()
            .filter(message -> subject(message).contains("送金しました")).findFirst().orElseThrow();
        assertThat(subject(mail)).isEqualTo("Kudos を送金しました：1,500 Kudos");
        assertThat(text(mail)).contains("1,500 Kudos", "0 Kudos", APP + "wallet");

        String failing = fresh("t-mail-failed");
        String other = payee(failing);
        enabledPayee(other);
        earn(other, 2000);
        runTransfers();
        String transferId = (String) transfersOf(other).getLast().get("transfer_id");
        StripeSim.target = "qb-" + transferId;
        StripeSim.REFUSE.set(1);
        runJobs();
        assertThat(transfer(transferId)).containsEntry("status", "FAILED");
        assertThat(sent(other, "qb.transfer.failed")).containsExactly("SENT");
        assertThat(sent(other, "qb.transfer.sent")).isEmpty();
        MimeMessage failed = received(addressOf(failing)).stream()
            .filter(message -> subject(message).contains("did not go through")).findFirst().orElseThrow();
        assertThat(subject(failed)).isEqualTo("Your transfer of 2,000 Kudos did not go through");
        // Why, in the taker's terms (Stripe's own words never), and where to look.
        assertThat(text(failed)).contains("Stripe declined the transfer to your payout account",
            APP + "wallet/payout").doesNotContain(SPONSOR);
    }

    @Test
    void aTakerIsAskedForDetailsOnlyWhenAnEnabledAccountIsHeldBack() {
        String name = fresh("t-mail-held");
        String taker = payee(name);
        openPayoutAccount(taker);
        String accountId = stripeAccountOf(taker);
        // Details given but not yet enabled: PENDING → RESTRICTED is the onboarding, not a mail.
        deliverConnect(connectEvent("account.updated", accountId, account(accountId, taker, false)));
        assertThat(payoutAccount(taker)).containsEntry("status", "RESTRICTED");
        assertThat(sent(taker, "qb.payout.action-needed")).isEmpty();
        // Reports of the same second: the more restrictive wins, so each comes a little later.
        advance(Duration.ofSeconds(5));
        deliverConnect(connectEvent("account.updated", accountId, account(accountId, taker, true)));
        assertThat(payoutAccount(taker)).containsEntry("status", "ENABLED");

        advance(Duration.ofSeconds(5));
        deliverConnect(connectEvent("account.updated", accountId, account(accountId, taker, false)));
        assertThat(payoutAccount(taker)).containsEntry("status", "RESTRICTED");
        assertThat(sent(taker, "qb.payout.action-needed")).containsExactly("SENT");
        MimeMessage mail = received(addressOf(name)).getLast();
        assertThat(subject(mail)).isEqualTo("Your payout account needs more information");
        assertThat(text(mail)).contains(APP + "wallet/payout");
        assertThat(outboxEvents("qb.payout-account.restricted", "userId", taker)).isEqualTo(1);
    }

    // ---- sponsors --------------------------------------------------------------------------------------------------

    @Test
    void aSponsorIsMailedTheirTopUpWithALinkToTheConsole() {
        String name = fresh("s-mail-topup");
        String sponsor = newSponsor(name);
        String topUpId = openTopUp(sponsor, 3000);
        deliver(event(eventId(), "checkout.session.completed", completed(topUpId)));
        assertThat(topUp(topUpId)).containsEntry("status", "PAID");
        assertThat(sent(sponsor, "qb.topup.paid")).containsExactly("SENT");
        MimeMessage mail = received(addressOf(name)).getLast();
        assertThat(subject(mail)).isEqualTo("Your top-up of 3,000 Kudos has arrived");
        assertThat(text(mail)).contains(SPONSOR + "wallet");
        // Once: the same event again (Stripe retries) is the platform's duplicate.
        deliver(event(eventId(), "checkout.session.completed", completed(topUpId)));
        assertThat(sent(sponsor, "qb.topup.paid")).hasSize(1);
    }

    @Test
    void aReviewersApprovalAndRejectionAreMailedButTheSponsorsOwnMovesAreNot() {
        String name = fresh("s-mail-review");
        String sponsor = newSponsor(name);
        String token = sponsorToken(sponsor);
        autoApprove(false);
        try {
            String versionId = version(token, "Mail review quiz");
            String approved = draft(token, versionId, "Approved one");
            decide((String) submit(token, approved).get("approvalRequestId"), "APPROVE", null);
            assertThat(status(approved)).isEqualTo("SCHEDULED");
            String rejected = draft(token, versionId, "Rejected one");
            decide((String) submit(token, rejected).get("approvalRequestId"), "REJECT", "Rewards too high.");
            String withdrawn = draft(token, versionId, "Withdrawn one");
            submit(token, withdrawn);
            run("QB_PUBLICATION_WITHDRAW", token, body("publicationId", withdrawn));
            assertThat(statusEvents(withdrawn)).isZero();
            deliverEvents();
        } finally {
            autoApprove(true);
        }
        assertThat(publicationTemplates(sponsor)).containsExactlyInAnyOrder("qb.publication.approved",
            "qb.publication.rejected");
        List<MimeMessage> mail = publicationMail(addressOf(name)).stream()
            .sorted(java.util.Comparator.comparing(message -> subject(message).contains("sent back"))).toList();
        assertThat(mail).extracting(NotificationMailIT::subject).containsExactly(
            "Your publication \"Approved one\" is approved", "Your publication \"Rejected one\" was sent back");
        // The start in UTC (the sponsor's zone is not known), the reviewer's reason, the console's publications.
        assertThat(text(mail.get(0))).containsPattern("from \\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2} UTC")
            .contains(SPONSOR + "publications");
        assertThat(text(mail.get(1))).contains("Rewards too high.", SPONSOR + "publications");

        // Paused and resumed by the sponsor: nothing.
        String live = livePublication(sponsor, "Own moves");
        run("QB_PUBLICATION_PAUSE", token, body("publicationId", live));
        run("QB_PUBLICATION_RESUME", token, body("publicationId", live));
        deliverEvents();
        assertThat(statusEvents(live)).isZero();
    }

    @Test
    void aTakedownIsMailedWithTheAdministratorsReasonButABansPauseIsNot() {
        String name = fresh("s-mail-takedown");
        String sponsor = newSponsor(name);
        String admin = contentAdminToken();
        String paused = livePublication(sponsor, "Taken down");
        run(PublicationAdmin.PAUSE, admin, body("publicationId", paused, "reason", "Misleading answers."));
        String ended = livePublication(sponsor, "Ended by admin");
        run(PublicationAdmin.END, admin, body("publicationId", ended, "reason", "Copied content."));
        deliverEvents();
        assertThat(sent(sponsor, "qb.publication.taken-down")).containsExactly("SENT", "SENT");
        assertThat(sent(sponsor, "qb.publication.ended")).isEmpty();
        assertThat(received(addressOf(name))).extracting(NotificationMailIT::text)
            .anySatisfy(text -> assertThat(text).contains("Misleading answers.", SPONSOR + "publications"))
            .anySatisfy(text -> assertThat(text).contains("Copied content."));

        String bannedName = fresh("s-mail-banned");
        String banned = newSponsor(bannedName);
        String running = livePublication(banned, "Banned sponsor's");
        String boss = userWithRole(fresh("mail-boss"), QbRoles.ADMIN_SUPER);
        run("QB_USER_BAN", tokenOf(boss, QbRoles.ADMIN_SUPER), body("userId", banned, "reason", "Fraud"));
        deliverEvents();
        assertThat(status(running)).isEqualTo("PAUSED");
        assertThat(statusEvents(running)).isZero();
        assertThat(sent(banned, "qb.publication.taken-down")).isEmpty();
    }

    @Test
    void shortOfFundsIsMailedAtMostOnceADayPerPublication() {
        String name = fresh("s-mail-short");
        String sponsor = newSponsor(name);
        String publicationId = livePublication(sponsor, "Short", Map.of("regions", List.of("GLOBAL")));
        credit(sponsor, 100);
        // Two attempts on a wallet that pays one reward: the second is pending, the publication short of funds.
        twoCompletions(publicationId);
        assertThat(status(publicationId)).isEqualTo("FUNDS_SHORT");
        deliverEvents();
        assertThat(sent(sponsor, "qb.publication.funds-short")).containsExactly("SENT");
        MimeMessage mail = received(addressOf(name)).getLast();
        assertThat(subject(mail)).isEqualTo("Top up to keep \"Short\" running");
        // What is waiting, where to top up, and how to stop these mails (a notification).
        assertThat(text(mail)).contains("Rewards waiting for payment: 1, together 100 Kudos", SPONSOR + "wallet",
            SPONSOR + "account", "/admin/mail/unsubscribe?token=");

        // Topped up (the pending reward and one more) and short again within the day: no second mail.
        credit(sponsor, 200);
        assertThat(status(publicationId)).isEqualTo("LIVE");
        twoCompletions(publicationId);
        assertThat(status(publicationId)).isEqualTo("FUNDS_SHORT");
        deliverEvents();
        assertThat(statusEvents(publicationId)).isEqualTo(2);
        assertThat(sent(sponsor, "qb.publication.funds-short")).hasSize(1);

        // A day later it is mailed again.
        advance(Duration.ofHours(25));
        credit(sponsor, 200);
        assertThat(status(publicationId)).isEqualTo("LIVE");
        twoCompletions(publicationId);
        assertThat(status(publicationId)).isEqualTo("FUNDS_SHORT");
        deliverEvents();
        assertThat(sent(sponsor, "qb.publication.funds-short")).containsExactly("SENT", "SENT");
        assertThat(query("SELECT count(*) AS n FROM qb_publication_notice WHERE publication_id = ?", publicationId)
            .getFirst()).containsEntry("n", 1L);
    }

    @Test
    void aTurnedOffNotificationIsSkippedWhileTransactionalMailIsAlwaysSent() {
        String name = fresh("s-mail-optout");
        String sponsor = newSponsor(name);
        String token = entryToken(sponsor, "sponsor");
        client.post().uri("/api/auth/mail/preferences").contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(Map.of("template", "qb.publication.ended", "subscribed", false))
            .exchange().expectStatus().is2xxSuccessful();
        // Transactional mail cannot be turned off.
        client.post().uri("/api/auth/mail/preferences").contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(Map.of("template", "qb.publication.taken-down", "subscribed", false))
            .exchange().expectStatus().isEqualTo(422);

        credit(sponsor, 1000);
        String publicationId = livePublication(sponsor, "One winner", Map.of("regions", List.of("GLOBAL"),
            "maxWinners", 1));
        play(newTaker(fresh("t-optout"), null), publicationId, true, true);
        assertThat(status(publicationId)).isEqualTo("ENDED");
        String takenDown = livePublication(sponsor, "Taken down anyway");
        run(PublicationAdmin.PAUSE, contentAdminToken(), body("publicationId", takenDown,
            "reason", "Checked."));
        deliverEvents();
        assertThat(sent(sponsor, "qb.publication.ended")).containsExactly("SKIPPED");
        assertThat(sent(sponsor, "qb.publication.taken-down")).containsExactly("SENT");
        assertThat(publicationMail(addressOf(name))).extracting(NotificationMailIT::subject)
            .containsExactly("Your publication \"Taken down anyway\" was taken down");
    }

    @Test
    void anEndedPublicationIsMailedWithWhyAndHowManyCompleted() {
        String name = fresh("s-mail-ended");
        String sponsor = newSponsor(name);
        mailLanguage(sponsor, "sponsor", "zh");
        credit(sponsor, 1000);
        String publicationId = livePublication(sponsor, "Quota", Map.of("regions", List.of("GLOBAL"),
            "maxWinners", 1));
        play(newTaker(fresh("t-ended"), null), publicationId, true, true);
        assertThat(status(publicationId)).isEqualTo("ENDED");
        deliverEvents();
        assertThat(outcomes(sponsor)).filteredOn(row -> "qb.publication.ended".equals(row.get("template")))
            .singleElement().satisfies(row -> assertThat(row).containsEntry("locale", "zh")
                .containsEntry("outcome", "SENT"));
        MimeMessage mail = received(addressOf(name)).getLast();
        assertThat(subject(mail)).isEqualTo("您的发布\"Quota\"已结束");
        assertThat(text(mail)).contains("获奖名额已满。", "完成的答题：1 次", SPONSOR + "publications");

        // By its end time, through the minute's job.
        String expiring = livePublication(sponsor, "Expiring", Map.of("regions", List.of("GLOBAL")));
        advance(Duration.ofDays(8));
        runTick();
        assertThat(status(expiring)).isEqualTo("ENDED");
        deliverEvents();
        assertThat(sent(sponsor, "qb.publication.ended")).hasSize(2);
        assertThat(text(received(addressOf(name)).getLast())).contains("已到结束时间。", "完成的答题：0 次");
    }

    // ---- preferences, old events, who may run the processes --------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void eachFrontEndListsOnlyItsOwnNotifications() {
        String sponsor = newSponsor(fresh("s-mail-prefs"));
        Map<String, Object> atConsole = client.get().uri("/api/auth/mail/preferences")
            .header(HttpHeaders.AUTHORIZATION, entryToken(sponsor, "sponsor")).header(HttpHeaders.ACCEPT_LANGUAGE, "ja")
            .exchange().expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        assertThat((List<Map<String, Object>>) atConsole.get("templates"))
            .extracting(row -> row.get("template") + " " + row.get("displayName"))
            .containsExactlyInAnyOrder("qb.publication.funds-short 公開の残高不足", "qb.publication.ended 公開の終了");

        String taker = newTaker(fresh("t-mail-prefs"), "JP");
        Map<String, Object> atApp = client.get().uri("/api/auth/mail/preferences")
            .header(HttpHeaders.AUTHORIZATION, entryToken(taker, "app")).exchange().expectStatus().isOk()
            .expectBody(MAP).returnResult().getResponseBody();
        assertThat((List<Map<String, Object>>) atApp.get("templates")).extracting(row -> row.get("template"))
            .containsExactly("qb.welcome");
        // A taker cannot set a sponsor's notification (the same refusal as for a name that does not exist).
        client.post().uri("/api/auth/mail/preferences").contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, entryToken(taker, "app"))
            .bodyValue(Map.of("template", "qb.publication.funds-short", "subscribed", false))
            .exchange().expectStatus().isEqualTo(422);
    }

    @Test
    void oldEventsAndMissingRecordsSendNothingAndOnlyTheSystemRunsTheProcesses() {
        String sponsor = newSponsor(fresh("s-mail-old"));
        String topUpId = openTopUp(sponsor, 2000);
        deliver(event(eventId(), "checkout.session.completed", completed(topUpId)));
        int before = sent(sponsor, "qb.topup.paid").size();
        // An event of four days ago (a consumer subscribed later receives the old ones too).
        Object output = systemRun(MailProcesses.TOPUP, body("topUpId", topUpId, "occurredAt",
            clock.instant().minus(Duration.ofDays(4)).toString()));
        assertThat(((MailProcesses.MailOutput) output).template()).isNull();
        assertThat(sent(sponsor, "qb.topup.paid")).hasSize(before);
        // A record that is not there, a user that is not there: nothing, and no failure to retry.
        assertThat(((MailProcesses.MailOutput) systemRun(MailProcesses.TOPUP, body("topUpId",
            UUID.randomUUID().toString(), "occurredAt", clock.instant().toString()))).template()).isNull();
        assertThat(((MailProcesses.MailOutput) systemRun(MailProcesses.PAYOUT_ACCOUNT, body("userId",
            UUID.randomUUID().toString(), "occurredAt", clock.instant().toString()))).template()).isNull();

        String now = clock.instant().toString();
        refusedAsInternal(MailProcesses.TRANSFER, admin(), body("transferId", "x", "kind", "SENT", "occurredAt", now));
        refusedAsInternal(MailProcesses.PAYOUT_ACCOUNT, admin(), body("userId", sponsor, "occurredAt", now));
        refusedAsInternal(MailProcesses.TOPUP, admin(), body("topUpId", topUpId, "occurredAt", now));
        refusedAsInternal(MailProcesses.PUBLICATION, admin(), body("publicationId", "x", "sponsorId", sponsor,
            "kind", "APPROVED", "occurredAt", now));
    }
}
