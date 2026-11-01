package com.jabiz.quizbuks.invitation;

import com.jabiz.entity.Violation;
import com.jabiz.mail.MailRecipient;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.publishing.PublicationLanguages;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.identity.CallerChecks;
import com.jabiz.quizbuks.identity.QbAccounts;
import com.jabiz.quizbuks.mailserver.QbMailServers;
import com.jabiz.quizbuks.messaging.MailValues;
import com.jabiz.quizbuks.publishing.Audience;
import com.jabiz.quizbuks.publishing.PublicationStatus;
import com.jabiz.quizbuks.publishing.QbPublications;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.mail.SendMail;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.LoadEntity;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A sponsor's invitation list (docs/quizbuks/plans/Q25-invitations.md):
 * <ul>
 *   <li>{@code QB_INVITATION_ADD}: adds addresses to an invited publication's list (those already on it are skipped),
 *       within {@code quizbuks.invitations.max-per-publication};</li>
 *   <li>{@code QB_INVITATION_REMOVE}: removes an invitation nobody started;</li>
 *   <li>{@code QB_INVITATION_SEND}: queues the mail of every invitation not yet sent, within the sponsor's daily quota
 *       ({@code quizbuks.invitations.sends-per-day}, days in {@code jabiz.fiscal-year-zone}); refused without a
 *       verified mail server of the sponsor's own (Q29, {@code QB_INVITATION_NO_MAIL_SERVER});</li>
 *   <li>{@code QB_INVITATION_MAIL} (internal, the job {@code qb.invitation-mail}, one transaction per invitation):
 *       sends one queued invitation's mail with its one-time sign-in link (platform decision D55) through the
 *       sponsor's own server ({@code SendMail.via}, platform decision D56), never the platform's.</li>
 * </ul>
 * The invitee's side (claiming, starting, answering) is in the answering processes through {@link Invitations}.
 */
public final class InvitationProcesses {

    public static final String ADD = "QB_INVITATION_ADD";
    public static final String REMOVE = "QB_INVITATION_REMOVE";
    public static final String SEND = "QB_INVITATION_SEND";
    public static final String MAIL = "QB_INVITATION_MAIL";

    public static final String MAIL_JOB = "qb.invitation-mail";
    public static final String QUEUED_TEMPLATE = "qb.system.invitations-queued";

    /** @param emails the addresses to invite, in any case; duplicates and addresses already invited are skipped */
    public record AddInput(@NotNull UUID publicationId,
        @NotEmpty @Size(max = QbInvitations.MAX_PER_CALL) List<@NotNull @Size(max = QbInvitations.EMAIL_LENGTH)
            String> emails) {}

    /** @param added how many were added; @param skipped how many were already on the list; @param total the list's size */
    public record AddOutput(UUID publicationId, int added, int skipped, int total) {}

    public record InvitationRef(@NotBlank @Size(max = 36) String invitationId) {}

    public record RemoveOutput(String invitationId, UUID publicationId, String email) {}

    public record PublicationRef(@NotNull UUID publicationId) {}

    /** @param queued how many mails were queued; @param remainingToday the sponsor's sends left today */
    public record SendOutput(UUID publicationId, int queued, int remainingToday) {}

    public record MailOutput(String invitationId, String status) {}

    static final String INPUT = "invitation.input";
    static final String OUTPUT = "invitation.output";
    static final String ID = "invitation.id";
    static final String PUBLICATION_ID = "invitation.publicationId";
    static final String PUBLICATION = "invitation.publication";
    static final String EXISTING = "invitation.existing";
    static final String LIST = "invitation.list";
    static final String INVITATION = "invitation.one";
    static final String QUOTA = "invitation.quota";
    static final String CARD = "invitation.card";
    static final String SPONSOR = "invitation.sponsor";
    static final String SENDING = "invitation.sending";
    static final String MAIL_SERVER = "invitation.mailServer";

    /** The entry the mailed link signs in at and the page it leads to: the publication's in the app. */
    static final String ENTRY = "app";

    /**
     * Where the mailed link leads: the publication's page under the app's front end, as a path of the site (the
     * entry's app-path, {@code /taker/} since Q30, plus {@code p/<id>}); the platform refuses a path outside it.
     */
    static String returnPath(String appPath, Object publicationId) {
        String root = appPath == null || appPath.isBlank() ? "/" : appPath.endsWith("/") ? appPath : appPath + "/";
        return root + "p/" + publicationId;
    }

    /** The limits of the lists (configuration {@code quizbuks.invitations.*}) and the zone the daily quota's days are in. */
    public record Limits(int maxPerPublication, int sendsPerDay, ZoneId zone) {

        public Limits {
            if (maxPerPublication < 1 || sendsPerDay < 1) {
                throw new IllegalArgumentException("quizbuks.invitations.max-per-publication and sends-per-day must be "
                    + "positive: " + maxPerPublication + ", " + sendsPerDay);
            }
        }

        String day(Instant at) {
            return LocalDate.ofInstant(at, zone).toString();
        }
    }

    // ---- add -------------------------------------------------------------------------------------------------------

    public static ProcessDefinition<AddInput, AddOutput, ProcessContext> add(Limits limits) {
        return ProcessDefinition.define(ADD, 1, AddInput.class, AddOutput.class, ProcessContext.class, pb -> {
            pb.description("Adds addresses to the invitation list of one of the caller's publications.")
                .permissions(QbPermissions.PUBLICATION_WRITE)
                .requiresVerifiedEmail()
                .steps(CallerChecks::refuseBanned)
                .contextFactory((start, input) -> context(start, input, input.publicationId()))
                .outputMapper(ctx -> ctx.get(OUTPUT, AddOutput.class));
            lockAndLoadPublication(pb);
            pb.step("Load the list", QueryEntities.<ProcessContext>of(QbInvitations.DEFAULT_DATASET,
                    ctx -> EntityQuery.builder().where(new QueryPredicate.Eq("publicationId", ctx.get(PUBLICATION_ID)))
                        .limit(limits.maxPerPublication() + 1).build(), LIST))
                .compute("Add the addresses", (metadata, ctx) -> add(ctx, limits));
        });
    }

    static void add(ProcessContext ctx, Limits limits) {
        AddInput input = ctx.get(INPUT, AddInput.class);
        EntityInstance publication = ctx.get(PUBLICATION, EntityInstance.class);
        if (!invited(ctx, publication)) {
            return;
        }
        Set<String> addresses = new LinkedHashSet<>();
        for (String email : input.emails()) {
            String address = QbInvitations.normalize(email);
            if (address == null) {
                ctx.reject(new Violation("emails", InvitationCodes.EMAIL, "Not an e-mail address: " + email,
                    Map.of("email", String.valueOf(email))));
                return;
            }
            addresses.add(address);
        }
        List<EntityInstance> existing = Invitations.list(ctx, LIST);
        Set<String> known = new LinkedHashSet<>();
        for (EntityInstance invitation : existing) {
            known.add(String.valueOf((Object) invitation.get("email")));
        }
        int skipped = 0;
        List<String> fresh = new ArrayList<>();
        for (String address : addresses) {
            if (known.contains(address)) {
                skipped++;
            } else {
                fresh.add(address);
            }
        }
        if (existing.size() + fresh.size() > limits.maxPerPublication()) {
            ctx.reject(new Violation("emails", InvitationCodes.LIMIT, "The list would hold "
                + (existing.size() + fresh.size()) + " addresses, more than " + limits.maxPerPublication(),
                Map.of("max", String.valueOf(limits.maxPerPublication()), "current", String.valueOf(existing.size()),
                    "adding", String.valueOf(fresh.size()))));
            return;
        }
        for (String address : fresh) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("publicationId", publication.id());
            values.put("publisherKind", publication.get("publisherKind"));
            values.put("publisherId", publication.get("publisherId"));
            values.put("email", address);
            values.put("status", InvitationStatus.ADDED);
            values.put("addedAt", ctx.opTime());
            values.put("sentCount", BigDecimal.ZERO);
            ctx.changes().insert(QbInvitations.INVITATION, values);
        }
        ctx.put(OUTPUT, new AddOutput(uuid(publication.id()), fresh.size(), skipped, existing.size() + fresh.size()));
    }

    // ---- remove ----------------------------------------------------------------------------------------------------

    public static ProcessDefinition<InvitationRef, RemoveOutput, ProcessContext> remove() {
        return ProcessDefinition.define(REMOVE, 1, InvitationRef.class, RemoveOutput.class, ProcessContext.class,
            pb -> pb
                .description("Removes an invitation of the caller's that nobody started.")
                .permissions(QbPermissions.PUBLICATION_WRITE)
                .requiresVerifiedEmail()
                .steps(CallerChecks::refuseBanned)
                .contextFactory((start, input) -> {
                    ProcessContext ctx = context(start, input, null);
                    ctx.put(ID, input.invitationId());
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get(OUTPUT, RemoveOutput.class))
                // The sponsor's own (404 otherwise); the list's lock, which the mail of each row takes too.
                .step("Load the invitation", LoadEntity.by(QbInvitations.SPONSOR_DATASET, ID, INVITATION))
                .step("Lock the list", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbInvitations.listLockName(ctx.get(INVITATION, EntityInstance.class).get("publicationId"))))
                .step("Load the invitation again", LoadEntity.by(QbInvitations.SPONSOR_DATASET, ID, INVITATION))
                .compute("Remove it", (metadata, ctx) -> remove(ctx)));
    }

    static void remove(ProcessContext ctx) {
        EntityInstance invitation = ctx.get(INVITATION, EntityInstance.class);
        if (!InvitationStatus.removable(invitation.get("status"))) {
            ctx.reject(new Violation("invitationId", InvitationCodes.STATE, "The invitation is "
                + invitation.get("status"), Map.of("status", String.valueOf((Object) invitation.get("status")))));
            return;
        }
        ctx.changes().delete(QbInvitations.INVITATION, invitation.id(), invitation.version());
        ctx.put(OUTPUT, new RemoveOutput(String.valueOf(invitation.id()), uuid(invitation.get("publicationId")),
            invitation.get("email")));
    }

    // ---- send ------------------------------------------------------------------------------------------------------

    public static ProcessDefinition<PublicationRef, SendOutput, ProcessContext> send(Limits limits) {
        return ProcessDefinition.define(SEND, 1, PublicationRef.class, SendOutput.class, ProcessContext.class, pb -> {
            pb.description("Queues the invitation mail of every address of the publication not yet written to.")
                .permissions(QbPermissions.PUBLICATION_WRITE)
                .requiresVerifiedEmail()
                .steps(CallerChecks::refuseBanned)
                .contextFactory((start, input) -> context(start, input, input.publicationId()))
                .outputMapper(ctx -> ctx.get(OUTPUT, SendOutput.class));
            lockAndLoadPublication(pb);
            pb.step("Lock the sponsor's quota", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbInvitations.quotaLockName(ctx.request().actorId())))
                .step("Load the quota of the day", QueryEntities.<ProcessContext>of(QbInvitations.QUOTA_DATASET,
                    ctx -> EntityQuery.builder().where(new QueryPredicate.And(List.of(
                        new QueryPredicate.Eq("sponsorId", ctx.request().actorId()),
                        new QueryPredicate.Eq("day", limits.day(ctx.opTime()))))).limit(1).build(), QUOTA))
                .step("Load the invitations not yet sent", QueryEntities.<ProcessContext>of(
                    QbInvitations.DEFAULT_DATASET, ctx -> EntityQuery.builder().where(new QueryPredicate.And(List.of(
                        new QueryPredicate.Eq("publicationId", ctx.get(PUBLICATION_ID)),
                        new QueryPredicate.Eq("status", InvitationStatus.ADDED))))
                        .limit(limits.maxPerPublication()).build(), LIST))
                .step("Load the sponsor's mail server", QueryEntities.<ProcessContext>of(
                    QbMailServers.DEFAULT_DATASET, ctx -> QbMailServers.bySponsor(ctx.request().actorId()),
                    MAIL_SERVER))
                .compute("Queue the mail", (metadata, ctx) -> send(ctx, limits));
        });
    }

    static void send(ProcessContext ctx, Limits limits) {
        EntityInstance publication = ctx.get(PUBLICATION, EntityInstance.class);
        if (!invited(ctx, publication)) {
            return;
        }
        Object status = publication.get("status");
        if (!PublicationStatus.LIVE.equals(status)) {
            // Live only: the mailed link opens the page, which a scheduled publication does not show yet.
            ctx.reject(new Violation("publicationId", InvitationCodes.NOT_OPEN, "The publication is " + status
                + ": invitations are sent while it is live", Map.of("status", String.valueOf(status))));
            return;
        }
        if (!QbMailServers.usable(Invitations.first(ctx, MAIL_SERVER))) {
            // Q29: invitations leave through the sponsor's own server only; none, or one that failed its test.
            ctx.reject(new Violation("publicationId", InvitationCodes.NO_MAIL_SERVER, "The sponsor has no verified "
                + "mail server: invitations are sent through it", Map.of()));
            return;
        }
        List<EntityInstance> waiting = Invitations.list(ctx, LIST);
        if (waiting.isEmpty()) {
            ctx.reject(new Violation("publicationId", InvitationCodes.NONE, "No invitation waits for its mail",
                Map.of()));
            return;
        }
        EntityInstance quota = Invitations.first(ctx, QUOTA);
        long sent = quota == null ? 0 : number(quota.get("sent"));
        long remaining = limits.sendsPerDay() - sent;
        if (waiting.size() > remaining) {
            ctx.reject(new Violation("publicationId", InvitationCodes.QUOTA, waiting.size() + " invitations to send, "
                + remaining + " left of today's " + limits.sendsPerDay(), Map.of("waiting",
                    String.valueOf(waiting.size()), "remaining", String.valueOf(Math.max(remaining, 0)),
                    "perDay", String.valueOf(limits.sendsPerDay()))));
            return;
        }
        for (EntityInstance invitation : waiting) {
            ctx.changes().update(QbInvitations.INVITATION, invitation.id(), invitation.version(),
                Map.of("status", InvitationStatus.QUEUED));
        }
        if (quota == null) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("sponsorId", ctx.request().actorId());
            values.put("day", limits.day(ctx.opTime()));
            values.put("sent", BigDecimal.valueOf(waiting.size()));
            ctx.changes().insert(QbInvitations.QUOTA, values);
        } else {
            ctx.changes().update(QbInvitations.QUOTA, quota.id(), quota.version(),
                Map.of("sent", BigDecimal.valueOf(sent + waiting.size())));
        }
        ctx.put(OUTPUT, new SendOutput(uuid(publication.id()), waiting.size(),
            (int) (remaining - waiting.size())));
    }

    // ---- mail (the job's item) ---------------------------------------------------------------------------------------

    public static ProcessDefinition<InvitationRef, MailOutput, ProcessContext> mail(MailValues values, String appPath) {
        return ProcessDefinition.define(MAIL, 1, InvitationRef.class, MailOutput.class, ProcessContext.class, pb -> pb
            .description("Sends one queued invitation's mail with its sign-in link.")
            .permissions(QbPermissions.PUBLICATION_SYSTEM)
            .internal()
            .contextFactory((start, input) -> {
                ProcessContext ctx = context(start, input, null);
                ctx.put(ID, input.invitationId());
                return ctx;
            })
            .outputMapper(ctx -> ctx.get(OUTPUT, MailOutput.class))
            // A plain table: the row may be gone since the job selected it (removed meanwhile), which is nothing to do,
            // not a failure of the item.
            .step("Load the invitation", QueryEntities.<ProcessContext>of(QbInvitations.DEFAULT_DATASET,
                ctx -> byId(ctx.get(ID)), LIST))
            .step("Lock the list", HoldLock.<ProcessContext>exclusive(ctx -> {
                EntityInstance invitation = Invitations.first(ctx, LIST);
                return invitation == null ? null : QbInvitations.listLockName(invitation.get("publicationId"));
            }))
            .step("Load the invitation again", QueryEntities.<ProcessContext>of(QbInvitations.DEFAULT_DATASET,
                ctx -> byId(Invitations.first(ctx, LIST) == null ? null : ctx.get(ID)), LIST))
            .compute("Check it is queued", (metadata, ctx) -> {
                EntityInstance invitation = Invitations.first(ctx, LIST);
                ctx.put(SENDING, invitation != null && InvitationStatus.QUEUED.equals(invitation.get("status")));
            })
            .step("Load the card and the sponsor", QueryEntities.<ProcessContext>all(
                QueryEntities.query(QbPublications.defaultDataset(QbPublications.CARD), ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.In("publicationId", sending(ctx)
                        ? one(Invitations.first(ctx, LIST).get("publicationId")) : List.of()))
                    .limit(1).build(), CARD),
                QueryEntities.query(QbAccounts.defaultDataset(QbAccounts.SPONSOR), ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.In("userId", sending(ctx)
                        ? one(Invitations.first(ctx, LIST).get("publisherId")) : List.of()))
                    .limit(1).build(), SPONSOR),
                QueryEntities.query(QbMailServers.DEFAULT_DATASET, ctx -> QbMailServers.bySponsor(sending(ctx)
                    ? Invitations.first(ctx, LIST).get("publisherId") : null), MAIL_SERVER)))
            // The publication may have been paused, ended or taken down since the sponsor queued the mail, or the
            // sponsor's mail server removed or found failing (Q29): then no link goes out and the row waits as ADDED
            // for the sponsor to send again. The mail leaves through the sponsor's server (decision D56), never the
            // platform's.
            .step("Send the invitation", SendMail.<ProcessContext>via(ctx -> sending(ctx)
                    ? String.valueOf((Object) Invitations.first(ctx, LIST).get("publisherId")) : null)
                .when(ctx -> sending(ctx) && live(ctx) && serverUsable(ctx), InvitationMails.INVITATION,
                    ctx -> MailRecipient.address(Invitations.first(ctx, LIST).get("email"), null),
                    mailParams(values, appPath)))
            .compute("Record the send", (metadata, ctx) -> recordSend(ctx)));
    }

    private static EntityQuery byId(Object invitationId) {
        return EntityQuery.builder().where(new QueryPredicate.In("invitationId", invitationId == null ? List.of()
            : one(invitationId))).limit(1).build();
    }

    /** Whether the publication still takes answers: live (the card's state is the publication's). */
    private static boolean live(ProcessContext ctx) {
        EntityInstance card = Invitations.first(ctx, CARD);
        return card != null && PublicationStatus.LIVE.equals(card.get("status"));
    }

    private static boolean sending(ProcessContext ctx) {
        return Boolean.TRUE.equals(ctx.get(SENDING)) && !ctx.hasViolations();
    }

    /** Whether the sponsor still has a verified mail server of their own (Q29). */
    private static boolean serverUsable(ProcessContext ctx) {
        return QbMailServers.usable(Invitations.first(ctx, MAIL_SERVER));
    }

    private static String mainText(EntityInstance card, String field) {
        Object main = card.get("language");
        String text = PublicationLanguages.textIn(card, main == null ? null : main.toString(), field);
        return text == null ? "" : text;
    }

    private static Map<String, java.util.function.Function<ProcessContext, ?>> mailParams(MailValues values, String appPath) {
        Map<String, java.util.function.Function<ProcessContext, ?>> params = new LinkedHashMap<>();
        params.put("company", ctx -> {
            EntityInstance sponsor = Invitations.first(ctx, SPONSOR);
            return sponsor == null || sponsor.get("company") == null ? "" : sponsor.get("company");
        });
        // Q73b: the invitation is in the publication's main language (its card's `language`, the link's ?lang=).
        params.put("publicationName", ctx -> mainText(Invitations.first(ctx, CARD), PublicationLanguages.NAME));
        params.put("quizTitle", ctx -> mainText(Invitations.first(ctx, CARD), PublicationLanguages.QUIZ_TITLE));
        params.put("maxReward", ctx -> MailValues.amount(Invitations.first(ctx, CARD).get("maxReward")));
        params.put("endAt", ctx -> values.time(Invitations.first(ctx, CARD).get("endAt")));
        params.put("entry", ctx -> ENTRY);
        params.put("to", ctx -> returnPath(appPath, Invitations.first(ctx, LIST).get("publicationId")));
        // Q59b: the quiz's language, which the app takes for someone new to it (the link's ?lang=).
        params.put("lang", ctx -> Invitations.first(ctx, CARD).get("language"));
        return params;
    }

    static void recordSend(ProcessContext ctx) {
        EntityInstance invitation = Invitations.first(ctx, LIST);
        if (ctx.hasViolations()) {
            return;
        }
        if (invitation == null) {
            ctx.put(OUTPUT, new MailOutput(String.valueOf((Object) ctx.get(ID)), null));
            return;
        }
        if (sending(ctx)) {
            Map<String, Object> next = new LinkedHashMap<>();
            if (live(ctx) && serverUsable(ctx)) {
                next.put("status", InvitationStatus.SENT);
                next.put("sentCount", BigDecimal.valueOf(number(invitation.get("sentCount")) + 1));
                next.put("lastSentAt", ctx.opTime());
            } else {
                next.put("status", InvitationStatus.ADDED);
            }
            ctx.changes().update(QbInvitations.INVITATION, invitation.id(), invitation.version(), next);
            ctx.put(OUTPUT, new MailOutput(String.valueOf(invitation.id()), (String) next.get("status")));
            return;
        }
        ctx.put(OUTPUT, new MailOutput(String.valueOf(invitation.id()), invitation.get("status")));
    }

    // ---- shared ----------------------------------------------------------------------------------------------------

    private static ProcessContext context(com.jabiz.process.ProcessStart start, Object input, UUID publicationId) {
        ProcessContext ctx = new ProcessContext(start);
        ctx.put(INPUT, input);
        if (publicationId != null) {
            ctx.put(PUBLICATION_ID, publicationId);
        }
        return ctx;
    }

    /** Locks the list and loads the publication as the sponsor's own (404 when it is not theirs). */
    private static <I, O> void lockAndLoadPublication(ProcessDefinitionBuilder<I, O, ProcessContext> pb) {
        pb.step("Lock the list", HoldLock.<ProcessContext>exclusive(
                ctx -> QbInvitations.listLockName(ctx.get(PUBLICATION_ID))))
            .step("Load the publication", LoadEntity.by(QbPublications.sponsorDataset(QbPublications.PUBLICATION),
                PUBLICATION_ID, PUBLICATION));
    }

    /** Whether the publication takes an invitation list; otherwise 422 {@code QB_INVITATION_AUDIENCE}. */
    private static boolean invited(ProcessContext ctx, EntityInstance publication) {
        if (!Audience.invited(publication.get("audience"))) {
            ctx.reject(new Violation("publicationId", InvitationCodes.AUDIENCE, "The publication is open to "
                + "everybody in its regions: it has no invitation list", Map.of()));
            return false;
        }
        return true;
    }

    /** One value as the list an {@code In} takes (typed as objects, whatever the value is). */
    private static List<Object> one(Object value) {
        List<Object> values = new ArrayList<>();
        values.add(value);
        return values;
    }

    static long number(Object value) {
        return value == null ? 0 : new BigDecimal(value.toString()).longValueExact();
    }

    static UUID uuid(Object value) {
        return value == null ? null : value instanceof UUID id ? id : UUID.fromString(value.toString());
    }

    private InvitationProcesses() {}
}
