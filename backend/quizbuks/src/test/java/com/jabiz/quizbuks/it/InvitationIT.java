package com.jabiz.quizbuks.it;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetup;
import com.jabiz.job.JobDefinition;
import com.jabiz.quizbuks.invitation.InvitationProcesses;
import com.jabiz.runtime.job.JobRunner;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Invitation-only publications (docs/quizbuks/plans/Q25-invitations.md): a sponsor publishes for a list of addresses
 * instead of regions; the list grows within its limit and loses only invitations nobody started; sending queues one
 * mail per address within the sponsor's daily quota, and the job sends each with a one-time sign-in link (platform
 * decision D55) that opens the invitee's account at the publication's page; only an invitee starts the publication,
 * which neither the feed nor the public page lists; the answer's time, score and reward are written on the
 * invitation. The limits are lowered for the test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "jabiz.mail.enabled=true", "jabiz.mail.from=quizbuks@example.com",
    "jabiz.mail.base-url=https://quizbuks.example.com",
    "spring.mail.host=localhost", "spring.mail.port=3048",
    "jabiz.security.entries.app.self-registration=true",
    "quizbuks.invitations.max-per-publication=4",
    "quizbuks.invitations.sends-per-day=3"})
class InvitationIT extends PlayItSupport {

    @RegisterExtension
    static final GreenMailExtension MAIL = new GreenMailExtension(new ServerSetup(3048, null,
        ServerSetup.PROTOCOL_SMTP));

    static final Pattern LINK = Pattern.compile(
        "https://quizbuks\\.example\\.com/taker/auth/email-link\\?entry=app&token=([A-Za-z0-9_-]+)&to=([^)\\s]*)");
    static final Pattern UNSUBSCRIBE = Pattern.compile(
        "https://quizbuks\\.example\\.com/taker/mail/unsubscribe\\?token=([A-Za-z0-9_.-]+)");

    @Autowired
    @Qualifier("qbInvitationMailJob")
    JobDefinition<InvitationProcesses.InvitationRef> mailJob;

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private String invitedPublication(String sponsorId, String name) {
        return invitedPublication(sponsorId, name, Map.of());
    }

    private String invitedPublication(String sponsorId, String name, Map<String, Object> overrides) {
        Map<String, Object> values = new java.util.HashMap<>(Map.of("audience", "INVITED", "regions", List.of()));
        values.putAll(overrides);
        return livePublication(sponsorId, name, values);
    }

    private Map<String, Object> add(String sponsorId, String publicationId, List<String> emails) {
        return run("QB_INVITATION_ADD", sponsorToken(sponsorId), body("publicationId", publicationId, "emails",
            emails));
    }

    private Map<String, Object> send(String sponsorId, String publicationId) {
        return run("QB_INVITATION_SEND", sponsorToken(sponsorId), body("publicationId", publicationId));
    }

    /** Runs the mail job once and delivers the mail it records. */
    private JobRunner.JobRun runMailJob() {
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        JobRunner.JobRun run = jobs.execute(mailJob, clock.instant());
        deliverEvents();
        return run;
    }

    private static List<Map<String, Object>> invitations(String publicationId) {
        return query("SELECT invitation_id, email, status, sent_count, user_id, attempt_id, score, reward "
            + "FROM qb_invitation WHERE publication_id = CAST(? AS uuid) ORDER BY email", publicationId);
    }

    private static Map<String, Object> invitation(String publicationId, String email) {
        return invitations(publicationId).stream().filter(row -> email.equals(row.get("email"))).findFirst()
            .orElseThrow();
    }

    /** The text of the newest mail to an address. */
    private static String textTo(String address) {
        List<MimeMessage> mails = Arrays.stream(MAIL.getReceivedMessages()).filter(m -> {
            try {
                return m.getAllRecipients()[0].toString().equalsIgnoreCase(address);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).toList();
        assertThat(mails).as("mail to " + address).isNotEmpty();
        return GreenMailUtil.getBody(mails.getLast()).replace("=\r\n", "").replace("=3D", "=");
    }

    private static int mailsTo(String address) {
        return (int) Arrays.stream(MAIL.getReceivedMessages()).filter(m -> {
            try {
                return m.getAllRecipients()[0].toString().equalsIgnoreCase(address);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).count();
    }

    /** Opens the mailed link: the invitee's session at the app. */
    private String openLink(String address) {
        Matcher link = LINK.matcher(textTo(address));
        assertThat(link.find()).as("a sign-in link to " + address).isTrue();
        Map<String, Object> session = client.post().uri("/api/auth/email-link/confirm")
            .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("token", link.group(1))).exchange()
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        assertThat(session).containsEntry("status", "SIGNED_IN");
        return "Bearer " + session.get("accessToken");
    }

    private Map<String, Object> page(String token, String publicationId) {
        List<Map<String, Object>> rows = items("qb.app.publication", token, Map.of("params", Map.of("publicationId",
            publicationId, "at", clock.instant().toString())));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static String email(String prefix) {
        return unique(prefix).toLowerCase() + "@example.com";
    }

    // ---- tests -----------------------------------------------------------------------------------------------------

    @Test
    void anInvitedPublicationIsAnsweredByItsInviteesOnly() {
        String sponsor = sponsorA();
        credit(sponsor, 100_000);
        String publicationId = invitedPublication(sponsor, "Invited");
        assertThat(card(publicationId)).containsEntry("audience", "INVITED").containsEntry("regions", "GLOBAL");
        String alice = email("alice");
        String bob = email("bob");

        // The list: added in any case, duplicates and known addresses skipped, nothing but addresses.
        assertThat(add(sponsor, publicationId, List.of(" " + alice.toUpperCase() + " ", bob, alice)))
            .containsEntry("added", 2).containsEntry("skipped", 0).containsEntry("total", 2);
        assertThat(add(sponsor, publicationId, List.of(bob))).containsEntry("added", 0).containsEntry("skipped", 1)
            .containsEntry("total", 2);
        assertThat(codes(refused("QB_INVITATION_ADD", sponsorToken(sponsor), body("publicationId", publicationId,
            "emails", List.of("not an address")), 422))).containsExactly("QB_INVITATION_EMAIL");
        assertThat(invitations(publicationId)).extracting(row -> row.get("email"), row -> row.get("status"))
            .containsExactly(org.assertj.core.groups.Tuple.tuple(alice, "ADDED"),
                org.assertj.core.groups.Tuple.tuple(bob, "ADDED"));

        // Neither the feed nor the public page lists it; a taker who is not invited cannot start it.
        String stranger = taker(newTaker(unique("stranger"), "JP"));
        assertThat(feed(stranger, "JP", Map.of())).extracting(row -> row.get("publicationId"))
            .doesNotContain(publicationId);
        assertThat(page(stranger, publicationId)).isNull();
        assertThat(codes(refused("QB_ATTEMPT_START", stranger, body("publicationId", publicationId), 422)))
            .containsExactly("QB_NOT_INVITED");
        client.get().uri("/api/public/queries/qb.public.publication?p.publicationId=" + publicationId).exchange()
            .expectStatus().isOk().expectBody().jsonPath("$.items").isEmpty();

        // Nor the tiers, nor a sponsor notice about it (its card's GLOBAL stands in for no region, Q25 review M1).
        assertThat(items("qb.app.publication-tiers", stranger, Map.of("params", Map.of("publicationId", publicationId,
            "at", clock.instant().toString(), "country", "JP")))).isEmpty();
        assertThat(items("qb.sponsor.broadcast-publications", sponsorToken(sponsor), Map.of("params",
            Map.of("at", clock.instant().toString())))).extracting(row -> row.get("publicationId"))
            .doesNotContain(publicationId);
        // An account that merely carries the invited address, without having verified it, is not the invitee.
        String claimantName = unique("claimant");
        String claimant = taker(newTaker(claimantName, "JP"));
        add(sponsor, publicationId, List.of(claimantName.toLowerCase() + "@example.com"));
        assertThat(codes(refused("QB_ATTEMPT_START", claimant, body("publicationId", publicationId), 422)))
            .containsExactly("QB_NOT_INVITED");
        run("QB_INVITATION_REMOVE", sponsorToken(sponsor), body("invitationId",
            invitation(publicationId, claimantName.toLowerCase() + "@example.com").get("invitation_id")));

        // Sent: queued within the quota, then the job mails each with its sign-in link and an unsubscribe link.
        assertThat(send(sponsor, publicationId)).containsEntry("queued", 2).containsEntry("remainingToday", 1);
        assertThat(codes(refused("QB_INVITATION_SEND", sponsorToken(sponsor), body("publicationId", publicationId),
            422))).containsExactly("QB_INVITATION_NONE");
        JobRunner.JobRun run = runMailJob();
        assertThat(run.items().succeeded()).isEqualTo(2);
        assertThat(invitation(publicationId, alice)).containsEntry("status", "SENT");
        assertThat(((Number) invitation(publicationId, alice).get("sent_count")).intValue()).isEqualTo(1);
        String mail = textTo(alice);
        assertThat(mail).contains("Invited").contains("Kudos");
        assertThat(UNSUBSCRIBE.matcher(mail).find()).as("an unsubscribe link").isTrue();
        Matcher link = LINK.matcher(mail);
        assertThat(link.find()).isTrue();
        assertThat(link.group(2)).isEqualTo("%2Ftaker%2Fp%2F" + publicationId);
        // Nothing waits any more: the job has nothing to do.
        assertThat(runMailJob().items().selected()).isZero();

        // Alice opens her link: a verified taker's account, the page shown to her, the attempt started on the
        // invitation, and the answer recorded on it.
        String aliceToken = openLink(alice);
        assertThat(page(aliceToken, publicationId)).isNull();
        run("QB_VIEW", aliceToken, body("publicationId", publicationId));
        assertThat(page(aliceToken, publicationId)).containsEntry("audience", "INVITED").containsEntry("invited", true)
            .containsEntry("canStart", true);
        assertThat(invitation(publicationId, alice).get("user_id")).isNotNull();
        String attemptId = (String) start(aliceToken, publicationId).get("attemptId");
        assertThat(invitation(publicationId, alice)).containsEntry("status", "STARTED")
            .containsEntry("attempt_id", attemptId);
        // Started: the sponsor may not remove it any more; Bob's, unanswered, goes.
        String aliceInvitation = String.valueOf(invitation(publicationId, alice).get("invitation_id"));
        assertThat(codes(refused("QB_INVITATION_REMOVE", sponsorToken(sponsor), body("invitationId",
            aliceInvitation), 422))).containsExactly("QB_INVITATION_STATE");
        clock.advance(Duration.ofSeconds(1));
        Map<String, Object> result = submit(aliceToken, attemptId, answers(true, true));
        assertThat(result).containsEntry("score", 5);
        Map<String, Object> answered = invitation(publicationId, alice);
        assertThat(answered).containsEntry("status", "ANSWERED");
        assertThat(((Number) answered.get("score")).intValue()).isEqualTo(5);
        assertThat(((Number) answered.get("reward")).intValue()).isEqualTo(((Number) result.get("reward"))
            .intValue());
        assertThat(items("qb.sponsor.invitations", sponsorToken(sponsor), Map.of("params", Map.of("publicationId",
            publicationId)))).extracting(row -> row.get("email"), row -> row.get("status"))
            .contains(org.assertj.core.groups.Tuple.tuple(alice, "ANSWERED"));
        assertThat(items("qb.sponsor.invitation-summary", sponsorToken(sponsor), Map.of("params",
            Map.of("publicationId", publicationId)))).singleElement().satisfies(row -> {
                assertThat(((Number) row.get("total")).intValue()).isEqualTo(2);
                assertThat(((Number) row.get("answered")).intValue()).isEqualTo(1);
                assertThat(((Number) row.get("sent")).intValue()).isEqualTo(1);
            });

        // Bob's invitation is removed; his link still signs him in, but the publication refuses him.
        String bobInvitation = String.valueOf(invitation(publicationId, bob).get("invitation_id"));
        run("QB_INVITATION_REMOVE", sponsorToken(sponsor), body("invitationId", bobInvitation));
        assertThat(invitations(publicationId)).hasSize(1);
        String bobToken = openLink(bob);
        assertThat(codes(refused("QB_ATTEMPT_START", bobToken, body("publicationId", publicationId), 422)))
            .containsExactly("QB_NOT_INVITED");
        assertThat(page(bobToken, publicationId)).isNull();

        // Another sponsor sees and changes nothing of the list.
        String other = sponsorB();
        refused("QB_INVITATION_ADD", sponsorToken(other), body("publicationId", publicationId, "emails",
            List.of(email("eve"))), 404);
        refused("QB_INVITATION_REMOVE", sponsorToken(other), body("invitationId", aliceInvitation), 404);
        assertThat(items("qb.sponsor.invitations", sponsorToken(other), Map.of("params", Map.of("publicationId",
            publicationId)))).isEmpty();
    }

    @Test
    void theListAndTheDailySendsAreLimited() {
        String sponsor = newSponsor(unique("s-limits"));
        credit(sponsor, 100_000);
        String publicationId = invitedPublication(sponsor, "Limited");
        // Sent while live only: a scheduled publication's page shows nobody anything yet.
        String scheduled = invitedPublication(sponsor, "Scheduled",
            Map.of("startAt", clock.instant().plus(Duration.ofDays(1))));
        add(sponsor, scheduled, List.of(email("later")));
        assertThat(codes(refused("QB_INVITATION_SEND", sponsorToken(sponsor), body("publicationId", scheduled), 422)))
            .containsExactly("QB_INVITATION_NOT_OPEN");
        // Four at most on the list.
        assertThat(codes(refused("QB_INVITATION_ADD", sponsorToken(sponsor), body("publicationId", publicationId,
            "emails", List.of(email("a"), email("b"), email("c"), email("d"), email("e"))), 422)))
            .containsExactly("QB_INVITATION_LIMIT");
        assertThat(add(sponsor, publicationId, List.of(email("a"), email("b"), email("c"), email("d"))))
            .containsEntry("added", 4);
        assertThat(codes(refused("QB_INVITATION_ADD", sponsorToken(sponsor), body("publicationId", publicationId,
            "emails", List.of(email("e"))), 422))).containsExactly("QB_INVITATION_LIMIT");
        // Three sends a day: four wait, so nothing goes until tomorrow (the quota is the sponsor's across publications).
        assertThat(codes(refused("QB_INVITATION_SEND", sponsorToken(sponsor), body("publicationId", publicationId),
            422))).containsExactly("QB_INVITATION_QUOTA");
        String remove = String.valueOf(invitations(publicationId).getFirst().get("invitation_id"));
        run("QB_INVITATION_REMOVE", sponsorToken(sponsor), body("invitationId", remove));
        assertThat(send(sponsor, publicationId)).containsEntry("queued", 3).containsEntry("remainingToday", 0);
        // Paused before the job ran: no link goes out, the rows wait as ADDED for the sponsor to send again.
        run("QB_PUBLICATION_PAUSE", sponsorToken(sponsor), body("publicationId", publicationId));
        int mailsBefore = MAIL.getReceivedMessages().length;
        assertThat(runMailJob().items().succeeded()).isEqualTo(3);
        assertThat(invitations(publicationId)).extracting(row -> row.get("status")).containsOnly("ADDED");
        assertThat(MAIL.getReceivedMessages()).hasSize(mailsBefore);
        run("QB_PUBLICATION_RESUME", sponsorToken(sponsor), body("publicationId", publicationId));
        String another = invitedPublication(sponsor, "Limited too");
        add(sponsor, another, List.of(email("f")));
        assertThat(codes(refused("QB_INVITATION_SEND", sponsorToken(sponsor), body("publicationId", another), 422)))
            .containsExactly("QB_INVITATION_QUOTA");
        clock.advance(Duration.ofDays(1));
        assertThat(send(sponsor, another)).containsEntry("queued", 1).containsEntry("remainingToday", 2);
        assertThat(query("SELECT day, sent FROM qb_invitation_quota WHERE sponsor_id = ? ORDER BY day", sponsor))
            .hasSize(2);
    }

    @Test
    void aPublicPublicationHasNoList() {
        String sponsor = sponsorA();
        String publicationId = livePublication(sponsor, "Open to all");
        assertThat(card(publicationId)).containsEntry("audience", "PUBLIC");
        assertThat(codes(refused("QB_INVITATION_ADD", sponsorToken(sponsor), body("publicationId", publicationId,
            "emails", List.of(email("x"))), 422))).containsExactly("QB_INVITATION_AUDIENCE");
        assertThat(codes(refused("QB_INVITATION_SEND", sponsorToken(sponsor), body("publicationId", publicationId),
            422))).containsExactly("QB_INVITATION_AUDIENCE");
        // An audience that is neither.
        assertThat(codes(refused("QB_PUBLICATION_SAVE", sponsorToken(sponsor), body("name", "Odd", "audience",
            "FRIENDS"), 422))).containsExactly("QB_PUBLICATION_AUDIENCE_INVALID");
    }
}
