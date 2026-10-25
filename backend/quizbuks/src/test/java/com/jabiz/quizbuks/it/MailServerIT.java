package com.jabiz.quizbuks.it;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
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

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A sponsor's own mail server (docs/quizbuks/plans/Q29-merchant-mail-server.md; platform decision D56): saving it
 * sends a test mail through it and records whether it worked; the password is stored sealed; invitations leave
 * through a verified server only, in the sponsor's name, never through the platform's server; a removed or failing
 * server stops them. The platform's server is the first GreenMail, the shop's the second (SMTPS with a login).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "jabiz.mail.enabled=true", "jabiz.mail.from=quizbuks@example.com",
    "jabiz.mail.base-url=https://quizbuks.example.com",
    "spring.mail.host=localhost", "spring.mail.port=3050",
    "jabiz.security.entries.app.self-registration=true"})
class MailServerIT extends PlayItSupport {

    @RegisterExtension
    static final GreenMailExtension MAIL = new GreenMailExtension(new ServerSetup(3050, null,
        ServerSetup.PROTOCOL_SMTP));

    static final String SHOP_USER = "shop";
    static final String SHOP_PASSWORD = "shop-secret-1";
    static final int SHOP_PORT = 3466;

    @RegisterExtension
    static final GreenMailExtension SHOP = new GreenMailExtension(new ServerSetup(SHOP_PORT, null,
        ServerSetup.PROTOCOL_SMTPS)).withConfiguration(GreenMailConfiguration.aConfig()
        .withUser("shop@example.com", SHOP_USER, SHOP_PASSWORD));

    @Autowired
    @Qualifier("qbInvitationMailJob")
    JobDefinition<InvitationProcesses.InvitationRef> mailJob;

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private static Map<String, Object> settings(String host, int port, String tls, String password) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("host", host);
        input.put("port", port);
        input.put("tls", tls);
        input.put("username", SHOP_USER);
        input.put("password", password);
        input.put("fromAddress", "shop@example.com");
        input.put("fromName", "The Shop");
        return input;
    }

    private Map<String, Object> set(String sponsorId, Map<String, Object> input) {
        return run("QB_MAIL_SERVER_SET", sponsorToken(sponsorId), input);
    }

    private Map<String, Object> server(String sponsorId) {
        List<Map<String, Object>> rows = query("SELECT host, port, tls, username, sealed_password, from_address, "
            + "from_name, verified_at, tested_at, last_error FROM qb_sponsor_mail_server WHERE sponsor_id = ?",
            sponsorId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private List<Map<String, Object>> mine(String sponsorId) {
        return items("qb.sponsor.mail-server", sponsorToken(sponsorId), Map.of());
    }

    private static List<MimeMessage> to(GreenMailExtension server, String address) {
        return Arrays.stream(server.getReceivedMessages()).filter(m -> {
            try {
                return m.getAllRecipients()[0].toString().equalsIgnoreCase(address);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).toList();
    }

    private JobRunner.JobRun runMailJob() {
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        JobRunner.JobRun run = jobs.execute(mailJob, clock.instant());
        deliverEvents();
        return run;
    }

    private String invitedPublication(String sponsorId, String name) {
        return livePublication(sponsorId, name, Map.of("audience", "INVITED", "regions", List.of()));
    }

    private static String email(String prefix) {
        return unique(prefix).toLowerCase() + "@example.com";
    }

    // ---- tests -----------------------------------------------------------------------------------------------------

    @Test
    void aVerifiedServerCarriesTheInvitationsInTheSponsorsName() throws Exception {
        String sponsor = newSponsor(unique("s-mail"));
        credit(sponsor, 100_000);
        String sponsorAddress = String.valueOf(query("SELECT email FROM sec_user_version WHERE user_id = ?::uuid "
            + "ORDER BY version_no DESC LIMIT 1", sponsor).getFirst().get("email"));

        // Saving sends a test mail through the server, to the sponsor's own address, and records the server verified.
        Map<String, Object> saved = set(sponsor, settings("localhost", SHOP_PORT, "SMTPS", SHOP_PASSWORD));
        assertThat(saved).containsEntry("verified", true).containsEntry("sentTo", sponsorAddress)
            .containsEntry("error", null);
        assertThat(to(SHOP, sponsorAddress)).singleElement().satisfies(mail -> {
            assertThat(mail.getSubject()).contains("localhost");
            assertThat(mail.getFrom()[0].toString()).contains("shop@example.com").contains("The Shop");
        });
        // The platform's server carried nothing of it (only the platform's own mail to the sponsor, if any).
        assertThat(to(MAIL, sponsorAddress)).noneSatisfy(mail -> assertThat(mail.getSubject()).contains("localhost"));
        // The password is stored sealed (decision D56), and no read path returns it: not the operation record either.
        assertThat(query("SELECT input_summary::text AS s FROM op_process WHERE process_name = 'QB_MAIL_SERVER_SET'"))
            .isNotEmpty().allSatisfy(op -> assertThat(String.valueOf(op.get("s"))).doesNotContain(SHOP_PASSWORD));
        Map<String, Object> row = server(sponsor);
        assertThat(String.valueOf(row.get("sealed_password"))).startsWith("s1:").doesNotContain(SHOP_PASSWORD);
        assertThat(row.get("verified_at")).isNotNull();
        assertThat(row.get("last_error")).isNull();
        assertThat(mine(sponsor)).singleElement().satisfies(mine -> assertThat(mine)
            .containsEntry("host", "localhost").containsEntry("tls", "SMTPS").containsEntry("username", SHOP_USER)
            .containsEntry("fromName", "The Shop").doesNotContainKeys("sealedPassword", "password")
            .containsEntry("lastError", null));
        assertThat(mine(sponsor).getFirst().get("verifiedAt")).isNotNull();
        assertThat(mine(sponsorB())).isEmpty();

        // The invitations leave through the shop's server, from the shop's address; the platform's server sees none.
        String publicationId = invitedPublication(sponsor, "Via the shop");
        String invitee = email("guest");
        run("QB_INVITATION_ADD", sponsorToken(sponsor), body("publicationId", publicationId, "emails",
            List.of(invitee)));
        assertThat(run("QB_INVITATION_SEND", sponsorToken(sponsor), body("publicationId", publicationId)))
            .containsEntry("queued", 1);
        assertThat(runMailJob().items().succeeded()).isEqualTo(1);
        assertThat(to(SHOP, invitee)).singleElement().satisfies(mail -> {
            assertThat(mail.getFrom()[0].toString()).contains("shop@example.com");
            assertThat(GreenMailUtil.getBody(mail)).contains("auth/email-link");
        });
        assertThat(to(MAIL, invitee)).isEmpty();
        assertThat(query("SELECT status FROM qb_invitation WHERE publication_id = ?::uuid", publicationId))
            .singleElement().satisfies(r -> assertThat(r).containsEntry("status", "SENT"));
        assertThat(query("SELECT sender_key FROM sys_mail_message WHERE address = ?", invitee)).singleElement()
            .satisfies(r -> assertThat(r).containsEntry("sender_key", sponsor));

        // Saved again without a password: the stored one is used for the test and kept.
        Map<String, Object> again = settings("localhost", SHOP_PORT, "SMTPS", null);
        again.put("fromName", "The Shop, renamed");
        assertThat(set(sponsor, again)).containsEntry("verified", true);
        assertThat(server(sponsor)).containsEntry("from_name", "The Shop, renamed");
        assertThat(to(SHOP, sponsorAddress)).hasSize(2);
    }

    @Test
    void aServerTheTestMailDoesNotLeaveIsSavedUnverifiedAndInvitationsAreRefused() {
        String sponsor = newSponsor(unique("s-fail"));
        credit(sponsor, 100_000);

        // Settings the platform refuses are violations on their fields, nothing is saved.
        assertThat(violations(refused("QB_MAIL_SERVER_SET", sponsorToken(sponsor),
            settings("10.0.0.5", 587, "STARTTLS", SHOP_PASSWORD), 422)))
            .extracting(v -> v.get("field"), v -> v.get("ruleCode")).containsExactly(
                org.assertj.core.groups.Tuple.tuple("host", "QB_MAIL_SERVER_INVALID"));
        assertThat(violations(refused("QB_MAIL_SERVER_SET", sponsorToken(sponsor),
            settings("smtp.example.com", 8025, "STARTTLS", SHOP_PASSWORD), 422)))
            .extracting(v -> v.get("field")).containsExactly("port");
        assertThat(violations(refused("QB_MAIL_SERVER_SET", sponsorToken(sponsor),
            settings("smtp.example.com", 587, "NONE", SHOP_PASSWORD), 422)))
            .extracting(v -> v.get("field")).containsExactly("tls");
        assertThat(violations(refused("QB_MAIL_SERVER_SET", sponsorToken(sponsor),
            settings("localhost", SHOP_PORT, "SMTPS", " "), 422)))
            .extracting(v -> v.get("field"), v -> v.get("ruleCode")).containsExactly(
                org.assertj.core.groups.Tuple.tuple("password", "QB_MAIL_SERVER_PASSWORD"));
        assertThat(server(sponsor)).isNull();

        // A server that refuses the login: saved with its answer, not verified.
        Map<String, Object> saved = set(sponsor, settings("localhost", SHOP_PORT, "SMTPS", "not-the-password"));
        assertThat(saved).containsEntry("verified", false);
        assertThat(String.valueOf(saved.get("error"))).isNotBlank();
        Map<String, Object> row = server(sponsor);
        assertThat(row.get("verified_at")).isNull();
        assertThat(row.get("tested_at")).isNotNull();
        assertThat(String.valueOf(row.get("last_error"))).isEqualTo(saved.get("error"));
        assertThat(mine(sponsor)).singleElement().satisfies(r -> assertThat(r).containsEntry("lastError",
            saved.get("error")));

        // Nothing is sent through it: the sponsor cannot send invitations.
        String publicationId = invitedPublication(sponsor, "Unverified");
        run("QB_INVITATION_ADD", sponsorToken(sponsor), body("publicationId", publicationId, "emails",
            List.of(email("nobody"))));
        assertThat(codes(refused("QB_INVITATION_SEND", sponsorToken(sponsor), body("publicationId", publicationId),
            422))).containsExactly("QB_INVITATION_NO_MAIL_SERVER");
        // Nor one without any server.
        String other = newSponsor(unique("s-none"));
        credit(other, 100_000);
        String otherPublication = invitedPublication(other, "No server");
        run("QB_INVITATION_ADD", sponsorToken(other), body("publicationId", otherPublication, "emails",
            List.of(email("nobody"))));
        assertThat(codes(refused("QB_INVITATION_SEND", sponsorToken(other), body("publicationId", otherPublication),
            422))).containsExactly("QB_INVITATION_NO_MAIL_SERVER");

        // Corrected, the server is verified and invitations go.
        assertThat(set(sponsor, settings("localhost", SHOP_PORT, "SMTPS", SHOP_PASSWORD)))
            .containsEntry("verified", true);
        assertThat(run("QB_INVITATION_SEND", sponsorToken(sponsor), body("publicationId", publicationId)))
            .containsEntry("queued", 1);
    }

    @Test
    void aRemovedServerSendsNothingAndTheQueuedInvitationWaitsAgain() {
        String sponsor = newSponsor(unique("s-remove"));
        credit(sponsor, 100_000);
        assertThat(set(sponsor, settings("localhost", SHOP_PORT, "SMTPS", SHOP_PASSWORD)))
            .containsEntry("verified", true);
        String publicationId = invitedPublication(sponsor, "Removed server");
        String invitee = email("late");
        run("QB_INVITATION_ADD", sponsorToken(sponsor), body("publicationId", publicationId, "emails",
            List.of(invitee)));
        run("QB_INVITATION_SEND", sponsorToken(sponsor), body("publicationId", publicationId));

        // Removed between queuing and the job: no mail, the row waits as ADDED for the sponsor to send again.
        assertThat(run("QB_MAIL_SERVER_REMOVE", sponsorToken(sponsor), Map.of())).containsEntry("sponsorId", sponsor);
        assertThat(server(sponsor)).isNull();
        assertThat(mine(sponsor)).isEmpty();
        refused("QB_MAIL_SERVER_REMOVE", sponsorToken(sponsor), Map.of(), 404);
        JobRunner.JobRun run = runMailJob();
        assertThat(run.items().succeeded()).isEqualTo(1);
        assertThat(to(SHOP, invitee)).isEmpty();
        assertThat(to(MAIL, invitee)).isEmpty();
        assertThat(query("SELECT status FROM qb_invitation WHERE publication_id = ?::uuid", publicationId))
            .singleElement().satisfies(r -> assertThat(r).containsEntry("status", "ADDED"));
        assertThat(codes(refused("QB_INVITATION_SEND", sponsorToken(sponsor), body("publicationId", publicationId),
            422))).containsExactly("QB_INVITATION_NO_MAIL_SERVER");
    }
}
