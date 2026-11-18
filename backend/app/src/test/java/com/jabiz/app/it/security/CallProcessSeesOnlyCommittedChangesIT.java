package com.jabiz.app.it.security;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetup;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.LoadEntity;
import com.jabiz.runtime.process.steps.SaveChanges;
import com.jabiz.runtime.security.InvitationProcesses;
import com.jabiz.runtime.security.SecurityEntities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 17zz24 (docs/design/06-process.md section 5): a sub-process reads what is stored. A process that changes a
 * user's address and then calls {@code SEC_USER_INVITE} (which checks and mails the stored address) in the same
 * transaction has the sub-process see the change once it has committed its changes with {@code SaveChanges.now}: the
 * writes the engine holds are written before every read of their tables (decision D46). Without it the change is only
 * registered, stored when the caller ends, and the sub-process answers from the old address.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "jabiz.mail.enabled=true", "jabiz.mail.from=jabiz@example.com", "jabiz.mail.base-url=https://app.example.com",
    "spring.mail.host=localhost", "spring.mail.port=3043", SignUpItSupport.ENTRY_PROPERTIES_REGISTRATION,
    SignUpItSupport.ENTRY_PROPERTIES_ADMIN, SignUpItSupport.ENTRY_PROPERTIES_LOOSE})
@Import({SignUpItSupport.FixedTimeLimits.class, CallProcessSeesOnlyCommittedChangesIT.Fixture.class})
class CallProcessSeesOnlyCommittedChangesIT extends SignUpItSupport {

    @RegisterExtension
    static final GreenMailExtension MAIL = new GreenMailExtension(new ServerSetup(3043, null, ServerSetup.PROTOCOL_SMTP));

    static final String PROCESS = "IT_ADDRESS_THEN_INVITE";

    public record Input(String userId, String email, boolean saveFirst) {}

    public record Done(String status) {}

    @TestConfiguration
    static class Fixture {
        /** Changes the user's address, optionally commits, then invites the user as a sub-process. */
        @Bean
        ProcessDefinition<Input, Done, ProcessContext> itAddressThenInvite() {
            return ProcessDefinition.define(PROCESS, 1, Input.class, Done.class, ProcessContext.class, pb -> pb
                .permissions("*")
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put("in", input);
                    ctx.put("userId", UUID.fromString(input.userId()));
                    return ctx;
                })
                .outputMapper(ctx -> new Done("ok"))
                .step("Load the user", LoadEntity.by(SecurityEntities.USER_DATASET, "userId", "user"))
                .compute("Change the address", (metadata, ctx) -> {
                    EntityInstance user = ctx.get("user", EntityInstance.class);
                    Map<String, Object> changes = new LinkedHashMap<>();
                    changes.put("email", ctx.get("in", Input.class).email());
                    ctx.changes().update(SecurityEntities.USER, user.id(), user.version(), changes);
                })
                .step("Commit", SaveChanges.when(ctx -> ctx.get("in", Input.class).saveFirst()))
                .step("Invite", CallProcess.latest("SEC_USER_INVITE",
                    ctx -> new InvitationProcesses.InviteInput((UUID) ctx.get("userId"), null), "invited")));
        }
    }

    @Override
    GreenMailExtension mail() {
        return MAIL;
    }

    private String call(String userId, String email, boolean saveFirst) {
        return refusalOrOk(post("/api/processes/" + PROCESS + "/latest", admin(),
            Map.of("userId", userId, "email", email, "saveFirst", saveFirst)));
    }

    private String refusalOrOk(org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec response) {
        Map<String, Object> body = response.expectBody(MAP).returnResult().getResponseBody();
        return String.valueOf(body.containsKey("output") ? "OK" : ruleCode(body));
    }

    /**
     * The invitation goes only to the address entered when the account was created, changed by nobody since
     * (decision D63): the sub-process has to see the change made a step before it.
     */
    @Test
    void aSubProcessSeesTheAddressChangedBeforeItOnceTheChangesAreCommitted() {
        String old = address("old");
        String changed = address("new");
        String user = createUserWithEmail(unique("flush"), old, null);
        assertThat(call(user, changed, true)).isEqualTo("INVITATION_ADDRESS_CHANGED");
        deliver();
        assertThat(to(old)).isEmpty();
        assertThat(to(changed)).isEmpty();
    }

    /**
     * Without {@code SaveChanges} the change is still only registered: the sub-process judges and mails the stored
     * (old) address, and the new one is stored when the caller ends.
     */
    @Test
    void changesNotYetCommittedAreNotSeenByTheSubProcess() {
        String old = address("old");
        String changed = address("new");
        String user = createUserWithEmail(unique("flush"), old, null);
        assertThat(call(user, changed, false)).isEqualTo("OK");
        deliver();
        assertThat(to(old)).hasSize(1);
        assertThat(to(changed)).isEmpty();
        assertThat(query("SELECT email FROM sec_user_version WHERE user_id = ?::uuid ORDER BY version_no DESC LIMIT 1",
            user).getFirst().get("email")).isEqualTo(changed);
    }
}
