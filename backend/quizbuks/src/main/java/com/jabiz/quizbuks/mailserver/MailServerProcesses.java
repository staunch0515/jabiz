package com.jabiz.quizbuks.mailserver;

import com.jabiz.entity.Violation;
import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.process.ProcessStart;
import com.jabiz.process.StepSpec;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.identity.CallerChecks;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.security.SecurityEntities;
import com.jabiz.runtime.security.secret.SealSecret;
import com.jabiz.security.Sensitive;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A sponsor's mail server (docs/quizbuks/plans/Q29-merchant-mail-server.md):
 * <ul>
 *   <li>{@code QB_MAIL_SERVER_SET}: saves the settings (the password sealed, platform step {@code SealSecret}) after
 *       sending a test mail through them to the sponsor's own address ({@link TestMailServer}): the row is verified
 *       when the mail left, otherwise it keeps the server's reason and invitations stay refused;</li>
 *   <li>{@code QB_MAIL_SERVER_REMOVE}: removes the sponsor's server.</li>
 * </ul>
 * Both take the sponsor's lock, which serialises saving and removing; the invitation processes only read the row, in
 * their own transactions, and act on what they find (a server gone or failing meanwhile returns the invitation to
 * ADDED, decision D-Q29-4).
 */
public final class MailServerProcesses {

    public static final String SET = "QB_MAIL_SERVER_SET";
    public static final String REMOVE = "QB_MAIL_SERVER_REMOVE";

    /**
     * The settings as the sponsor types them. The password is given on the first save and whenever it changes; blank
     * keeps the stored one. Nothing of it is logged ({@code @Sensitive}, {@link #toString}).
     */
    public record SetInput(@NotBlank @Size(max = QbMailServers.HOST_LENGTH) String host, @Min(1) @Max(65535) int port,
        @NotBlank @Size(max = 10) String tls, @NotBlank @Size(max = QbMailServers.USERNAME_LENGTH) String username,
        @Sensitive @Size(max = QbMailServers.PASSWORD_LENGTH) String password,
        @NotBlank @Size(max = QbMailServers.ADDRESS_LENGTH) String fromAddress,
        @Size(max = 100) String fromName) {
        @Override
        public String toString() {
            return "SetInput[host=" + host + ", port=" + port + ", tls=" + tls + ", username=" + username
                + ", fromAddress=" + fromAddress + "]";
        }
    }

    /**
     * @param verified whether the test mail left through the server (the server is used from now on)
     * @param error    the server's or the connection's answer when it did not
     * @param sentTo   the sponsor's address the test mail went to
     */
    public record SetOutput(String sponsorId, boolean verified, String error, Instant testedAt, String sentTo) {}

    public record RemoveInput() {}

    public record RemoveOutput(String sponsorId) {}

    static final String INPUT = "mailServer.input";
    static final String OUTPUT = "mailServer.output";
    static final String SPONSOR_ID = "mailServer.sponsorId";
    static final String SERVER = "mailServer.server";
    static final String USER = "mailServer.user";
    static final String SEALED = "mailServer.sealed";
    static final String ERROR = "mailServer.error";
    static final String SENT_TO = "mailServer.sentTo";

    public static ProcessDefinition<SetInput, SetOutput, ProcessContext> set() {
        return set(SET, "Saves the sponsor's own mail server after sending a test mail through it.",
            QbPermissions.PUBLICATION_WRITE, pb -> pb.steps(CallerChecks::refuseBanned),
            ctx -> ctx.request().actorId());
    }

    /**
     * A process that saves a mail server under a sender key: the sponsor's id, or another key (Q48c: an owner's
     * prospecting, {@code prospect:<owner>}) decided by {@code prelude}'s steps. The test mail goes to the caller.
     */
    public static ProcessDefinition<SetInput, SetOutput, ProcessContext> set(String name, String description,
        String permission, Consumer<ProcessDefinitionBuilder<SetInput, SetOutput, ProcessContext>> prelude,
        Function<ProcessContext, Object> key) {
        return ProcessDefinition.define(name, 1, SetInput.class, SetOutput.class, ProcessContext.class, pb -> {
            pb.description(description)
                .permissions(permission)
                .requiresVerifiedEmail()
                .contextFactory(MailServerProcesses::context)
                .outputMapper(ctx -> ctx.get(OUTPUT, SetOutput.class));
            prelude.accept(pb);
            pb.compute("Whose server", (metadata, ctx) -> ctx.put(SPONSOR_ID, String.valueOf(key.apply(ctx))))
                .step("Lock the sponsor's server", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbMailServers.lockName(ctx.get(SPONSOR_ID))))
                .step("Load the server", QueryEntities.<ProcessContext>of(QbMailServers.DEFAULT_DATASET,
                    ctx -> QbMailServers.bySponsor(ctx.get(SPONSOR_ID)), SERVER))
                // The caller's platform user, for the address the test mail goes to (an actor id that is no UUID
                // names none).
                .step("Load the caller's account", QueryEntities.<ProcessContext>of(SecurityEntities.USER_DATASET,
                    ctx -> EntityQuery.builder().where(new QueryPredicate.In("userId", uuids(ctx.request().actorId())))
                        .limit(1).build(), USER))
                .compute("Check the settings", (metadata, ctx) -> check(ctx))
                .step("Seal the password", SealSecret.<ProcessContext>of(
                    ctx -> ctx.hasViolations() ? null : ctx.get(INPUT, SetInput.class).password(),
                    ctx -> String.valueOf((Object) ctx.get(SPONSOR_ID)), SEALED))
                .step("Send a test mail through it", StepSpec.of(TestMailServer.class, NoMetadata.INSTANCE))
                .compute("Record the server", (metadata, ctx) -> record(ctx));
        });
    }

    public static ProcessDefinition<RemoveInput, RemoveOutput, ProcessContext> remove() {
        return remove(REMOVE, "Removes the sponsor's own mail server; invitations are refused until another is "
            + "verified.", QbPermissions.PUBLICATION_WRITE, pb -> pb.steps(CallerChecks::refuseBanned),
            ctx -> ctx.request().actorId());
    }

    /** As {@link #set(String, String, String, Consumer, Function)}: none under the key is 404. */
    public static ProcessDefinition<RemoveInput, RemoveOutput, ProcessContext> remove(String name, String description,
        String permission, Consumer<ProcessDefinitionBuilder<RemoveInput, RemoveOutput, ProcessContext>> prelude,
        Function<ProcessContext, Object> key) {
        return ProcessDefinition.define(name, 1, RemoveInput.class, RemoveOutput.class, ProcessContext.class, pb -> {
            pb.description(description)
                .permissions(permission)
                .requiresVerifiedEmail()
                .contextFactory(MailServerProcesses::context)
                .outputMapper(ctx -> ctx.get(OUTPUT, RemoveOutput.class));
            prelude.accept(pb);
            pb.compute("Whose server", (metadata, ctx) -> ctx.put(SPONSOR_ID, String.valueOf(key.apply(ctx))))
                .step("Lock the sponsor's server", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbMailServers.lockName(ctx.get(SPONSOR_ID))))
                .step("Load the server", QueryEntities.<ProcessContext>of(QbMailServers.DEFAULT_DATASET,
                    ctx -> QbMailServers.bySponsor(ctx.get(SPONSOR_ID)), SERVER))
                .compute("Remove it", (metadata, ctx) -> {
                    EntityInstance server = existing(ctx);
                    if (server == null) {
                        throw new EntityNotFoundException(QbMailServers.ENTITY + " not found");
                    }
                    ctx.changes().delete(QbMailServers.ENTITY, server.id(), server.version());
                    ctx.put(OUTPUT, new RemoveOutput(String.valueOf((Object) ctx.get(SPONSOR_ID))));
                });
        });
    }

    private static ProcessContext context(ProcessStart start, Object input) {
        ProcessContext ctx = new ProcessContext(start);
        ctx.put(INPUT, input);
        return ctx;
    }

    /** What is checked before anything is sealed or sent: the TLS mode, and a password on the first save. */
    static void check(ProcessContext ctx) {
        SetInput input = ctx.get(INPUT, SetInput.class);
        if (QbMailServers.tls(input.tls()) == null) {
            ctx.reject(new Violation("tls", MailServerCodes.INVALID, "tls must be SMTPS or STARTTLS, not "
                + input.tls(), Map.of("detail", "tls must be SMTPS or STARTTLS")));
        }
        boolean passwordGiven = input.password() != null && !input.password().isBlank();
        if (!passwordGiven && existing(ctx) == null) {
            ctx.reject(new Violation("password", MailServerCodes.PASSWORD, "The password of the mail server is "
                + "required", Map.of()));
        }
    }

    static EntityInstance existing(ProcessContext ctx) {
        List<EntityInstance> rows = ctx.contains(SERVER) ? ctx.get(SERVER, List.class) : List.of();
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** The sealed password the row will hold: the one just sealed, else the stored one. */
    static String sealed(ProcessContext ctx) {
        Object fresh = ctx.contains(SEALED) ? ctx.get(SEALED) : null;
        if (fresh != null) {
            return fresh.toString();
        }
        EntityInstance existing = existing(ctx);
        return existing == null ? null : existing.get("sealedPassword");
    }

    /** The sponsor's address: the verified one, else the account's (the process already requires a verified one). */
    static String address(ProcessContext ctx) {
        List<EntityInstance> users = ctx.contains(USER) ? ctx.get(USER, List.class) : List.of();
        EntityInstance user = users.isEmpty() ? null : users.getFirst();
        if (user == null) {
            return null;
        }
        Object verified = user.get("verifiedEmail");
        Object address = verified == null || verified.toString().isBlank() ? user.get("email") : verified;
        return address == null || address.toString().isBlank() ? null : address.toString();
    }

    static void record(ProcessContext ctx) {
        if (ctx.hasViolations()) {
            return;
        }
        SetInput input = ctx.get(INPUT, SetInput.class);
        String sponsorId = String.valueOf((Object) ctx.get(SPONSOR_ID));
        String error = ctx.contains(ERROR) ? ctx.get(ERROR, String.class) : null;
        Instant now = ctx.opTime();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("host", input.host().strip().toLowerCase(java.util.Locale.ROOT));
        values.put("port", input.port());
        values.put("tls", QbMailServers.tls(input.tls()).name());
        values.put("username", input.username().strip());
        values.put("sealedPassword", sealed(ctx));
        values.put("fromAddress", input.fromAddress().strip());
        values.put("fromName", input.fromName() == null || input.fromName().isBlank() ? null : input.fromName().strip());
        values.put("verifiedAt", error == null ? now : null);
        values.put("testedAt", now);
        values.put("lastError", error);
        EntityInstance existing = existing(ctx);
        if (existing == null) {
            values.put("sponsorId", sponsorId);
            ctx.changes().insert(QbMailServers.ENTITY, values);
        } else {
            ctx.changes().update(QbMailServers.ENTITY, existing.id(), existing.version(), values);
        }
        ctx.put(OUTPUT, new SetOutput(sponsorId, error == null, error, now,
            ctx.contains(SENT_TO) ? ctx.get(SENT_TO, String.class) : null));
    }

    private static List<Object> uuids(Object id) {
        List<Object> ids = new ArrayList<>();
        if (id != null) {
            try {
                ids.add(UUID.fromString(id.toString()));
            } catch (IllegalArgumentException e) {
                // Not a platform user (tests act as such): no address is found.
            }
        }
        return ids;
    }

    private MailServerProcesses() {}
}
