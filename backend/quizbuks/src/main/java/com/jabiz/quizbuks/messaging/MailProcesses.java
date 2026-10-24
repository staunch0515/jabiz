package com.jabiz.quizbuks.messaging;

import com.jabiz.event.DomainEvent;
import com.jabiz.i18n.MessageCatalog;
import com.jabiz.mail.MailRecipient;
import com.jabiz.mail.MailTemplate;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.process.StepSpec;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.identity.BanRules;
import com.jabiz.quizbuks.identity.QbAccounts;
import com.jabiz.quizbuks.publishing.PublicationAdmin;
import com.jabiz.quizbuks.publishing.PublicationNotices;
import com.jabiz.quizbuks.publishing.PublicationStatus;
import com.jabiz.quizbuks.publishing.QbPublications;
import com.jabiz.quizbuks.wallet.PayoutAccountStatus;
import com.jabiz.quizbuks.wallet.QbPayouts;
import com.jabiz.quizbuks.wallet.QbTopUps;
import com.jabiz.quizbuks.wallet.QbWallets;
import com.jabiz.quizbuks.wallet.TransferRules;
import com.jabiz.quizbuks.wallet.TransferStatus;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.mail.SendMail;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.security.SecurityEntities;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * The processes that mail QuizBuks's events (Q7a, docs/quizbuks/plans/Q7-messages-legal.md D-Q7-1, D-Q7-2): each is
 * the consumer of one or two events (or the item of a job), {@code internal()} (platform decision D41: only the system
 * runs it), with the permission {@value QbPermissions#MESSAGING_SYSTEM} that no role holds. Each reads the record the
 * event names (never the event's own figures, which only point at it) and checks that what the mail says still holds,
 * reads the recipient's address and language, and sends through the platform's {@link SendMail}: in the consumer's
 * transaction, so the mail is recorded once per event; delivery and unsubscribing are the platform's (a notification
 * the recipient turned off is recorded as skipped, mail switched off by {@code jabiz.mail.enabled} likewise).
 *
 * <ul>
 *   <li>{@value #TRANSFER}: {@code qb.transfer.sent} and {@code qb.transfer.failed}, to the taker, while the transfer
 *       is still so;</li>
 *   <li>{@value #PAYOUT_ACCOUNT}: {@code qb.payout-account.restricted}, to the taker while the account is still held
 *       back, at most once per user in {@link NotificationMails#NOTICE_INTERVAL};</li>
 *   <li>{@value #TOPUP}: {@code qb.topup.paid}, to the sponsor;</li>
 *   <li>{@value #PUBLICATION}: {@code qb.publication.status}, to the sponsor, the template by the kind of move; short
 *       of funds at most once per sponsor per {@link NotificationMails#NOTICE_INTERVAL}, naming every publication
 *       still short, one that comes sooner deferred (the notice is marked pending) to {@value #FUNDS_SHORT_DUE}; no
 *       "ended" for a publication an administrator took down or a banned sponsor;</li>
 *   <li>{@value #FUNDS_SHORT_DUE}: the item of the hourly job {@value #FUNDS_SHORT_JOB}, a sponsor whose deferred
 *       short-of-funds mail is due: sent when a publication is still short, otherwise only the mark cleared.</li>
 * </ul>
 * The throttled mail is kept in {@link QbMessaging#NOTICE} under the recipient's {@link QbMessaging#noticeLock}: never
 * a publication's lock, and no answering or payment process takes it. Nothing is sent to a user without an address,
 * for an event older than {@link NotificationMails#MAX_AGE} (transactional mail: {@link
 * NotificationMails#TRANSACTIONAL_MAX_AGE}), or for a record that is not there: the process ends without a mail and
 * without failing (a failing consumer would be retried for ever). Texts that depend on the language (why a transfer
 * failed, a publication ended, when it starts) are taken from the messages in the recipient's language, the one the
 * platform renders the mail in.
 */
public final class MailProcesses {

    public static final String TRANSFER = "QB_MAIL_TRANSFER";
    public static final String PAYOUT_ACCOUNT = "QB_MAIL_PAYOUT_ACCOUNT";
    public static final String TOPUP = "QB_MAIL_TOPUP";
    public static final String PUBLICATION = "QB_PUBLICATION_NOTIFY";
    public static final String FUNDS_SHORT_DUE = "QB_MAIL_FUNDS_SHORT_DUE";

    public static final String TRANSFER_SENT_CONSUMER = "qb.mail-transfer-sent";
    public static final String TRANSFER_FAILED_CONSUMER = "qb.mail-transfer-failed";
    public static final String PAYOUT_RESTRICTED_CONSUMER = "qb.mail-payout-restricted";
    public static final String TOPUP_PAID_CONSUMER = "qb.mail-topup-paid";
    public static final String PUBLICATION_CONSUMER = "qb.publication-notify";

    /** The hourly per-sponsor job that sends the deferred short-of-funds mail once their window has passed. */
    public static final String FUNDS_SHORT_JOB = "qb.mail-funds-short-due";
    /** The sponsors whose deferred short-of-funds mail is due by a time. */
    public static final String FUNDS_SHORT_DUE_TEMPLATE = "qb.system.mail-funds-short-due";

    /** The two outcomes of a transfer that are mailed. */
    public static final String SENT = "SENT";
    public static final String FAILED = "FAILED";

    /** The kinds of throttled notice ({@link QbMessaging#NOTICE}'s {@code kind}). */
    public static final String FUNDS_SHORT_NOTICE = PublicationNotices.FUNDS_SHORT;
    public static final String PAYOUT_NOTICE = "PAYOUT_ACTION_NEEDED";

    /**
     * @param kind       {@value #SENT} or {@value #FAILED}
     * @param occurredAt when the event was published
     */
    public record TransferMailInput(@NotBlank String transferId, @NotBlank String kind, @NotNull Instant occurredAt) {}

    public record PayoutMailInput(@NotBlank String userId, @NotNull Instant occurredAt) {}

    public record TopUpMailInput(@NotBlank String topUpId, @NotNull Instant occurredAt) {}

    /** The payload of {@link PublicationNotices#EVENT}, and when it was published. */
    public record PublicationMailInput(@NotBlank String publicationId, @NotBlank String sponsorId,
        @NotBlank String kind, String reason, String name, String startAt, @NotNull Instant occurredAt) {}

    /** The input of {@value #FUNDS_SHORT_DUE}: the sponsor, from the job's template. */
    public record SponsorRef(@NotBlank @Size(max = 128) String sponsorId) {}

    /** @param template the template mailed; null when none was */
    public record MailOutput(String template) {}

    static final String INPUT = "input";
    static final String OCCURRED = "occurredAt";
    static final String RECORD = "record";
    static final String USER = "user";
    static final String WALLET = "wallet";
    static final String COUNTER = "counter";
    static final String CARD = "card";
    static final String BAN = "ban";
    static final String NOTICE = "notice";
    /** The template to send and its parameters, once decided. */
    static final String SEND = "send";
    static final String PARAMS = "params";
    static final String LOCALE = "locale";

    /** At most this many publications are named in one short-of-funds mail; more are shown as "…". */
    static final int MAX_LISTED = 10;

    private MailProcesses() {}

    // ---- transfers -------------------------------------------------------------------------------------------------

    public static ProcessDefinition<TransferMailInput, MailOutput, ProcessContext> transfer(MessageCatalog messages) {
        return define(TRANSFER, "Mails a taker that their transfer was sent or failed.", TransferMailInput.class,
            TransferMailInput::occurredAt, pb -> pb
                .step("Load the transfer", QueryEntities.<ProcessContext>of(
                    QbPayouts.defaultDataset(QbPayouts.TRANSFER), ctx -> byId("transferId",
                        input(ctx, TransferMailInput.class).transferId()), RECORD))
                .step("Load the taker", loadUser(ctx -> record(ctx) == null ? null : record(ctx).get("userId"))),
            ctx -> {
                EntityInstance transfer = record(ctx);
                if (transfer == null) {
                    return;
                }
                String kind = input(ctx, TransferMailInput.class).kind();
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("amount", MailValues.kudos(transfer.get("amount")));
                // Only what is still true: after a long backlog the transfer may have been reversed since.
                if (SENT.equals(kind) && TransferStatus.SENT.equals(transfer.get("status"))) {
                    params.put("fee", MailValues.kudos(transfer.get("fee")));
                    params.put("net", MailValues.kudos(transfer.get("net")));
                    decide(ctx, NotificationMails.TRANSFER_SENT, params);
                } else if (FAILED.equals(kind) && TransferStatus.FAILED.equals(transfer.get("status"))) {
                    // Whether the taker can do something about it, never Stripe's own words (D-Q7-1).
                    String reason = TransferRules.ACCOUNT_FAILURES.contains(transfer.get("failureCode"))
                        ? "ACCOUNT" : "OTHER";
                    params.put("reason", text(messages, ctx, "qb.mail.transfer-failure." + reason));
                    decide(ctx, NotificationMails.TRANSFER_FAILED, params);
                }
            }, messages, NotificationMails.TRANSFER_SENT, NotificationMails.TRANSFER_FAILED);
    }

    static TransferMailInput transferInput(DomainEvent event, String kind) {
        return new TransferMailInput(text(event, "transferId"), kind, event.createdTime());
    }

    // ---- payout accounts -------------------------------------------------------------------------------------------

    public static ProcessDefinition<PayoutMailInput, MailOutput, ProcessContext> payoutAccount(
        MessageCatalog messages) {
        return define(PAYOUT_ACCOUNT, "Mails a taker that Stripe needs more from them to pay out.",
            PayoutMailInput.class, PayoutMailInput::occurredAt, pb -> pb
                .step("Lock the taker's notices", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbMessaging.noticeLock(input(ctx, PayoutMailInput.class).userId())))
                .step("Load the last notice", loadNotice(ctx -> input(ctx, PayoutMailInput.class).userId(),
                    PAYOUT_NOTICE))
                .step("Load the payout account", QueryEntities.<ProcessContext>of(
                    QbPayouts.defaultDataset(QbPayouts.ACCOUNT), ctx -> byId("userId",
                        input(ctx, PayoutMailInput.class).userId()), RECORD))
                .step("Load the taker", loadUser(ctx -> record(ctx) == null ? null : record(ctx).get("userId"))),
            ctx -> {
                EntityInstance account = record(ctx);
                // Only while Stripe still holds it back: the taker may have given the details meanwhile. An account
                // that flips between enabled and held back is mailed at most once a day: the first mail says it all.
                if (account == null || !PayoutAccountStatus.RESTRICTED.equals(account.get("status"))
                    || !addressed(ctx) || withinWindow(ctx)) {
                    return;
                }
                sent(ctx, String.valueOf((Object) account.get("userId")), PAYOUT_NOTICE);
                decide(ctx, NotificationMails.PAYOUT_ACTION_NEEDED, Map.of());
            }, messages, NotificationMails.PAYOUT_ACTION_NEEDED);
    }

    static PayoutMailInput payoutInput(DomainEvent event) {
        return new PayoutMailInput(text(event, "userId"), event.createdTime());
    }

    // ---- top-ups ---------------------------------------------------------------------------------------------------

    public static ProcessDefinition<TopUpMailInput, MailOutput, ProcessContext> topUp(MessageCatalog messages) {
        return define(TOPUP, "Mails a sponsor that their top-up is on their wallet.", TopUpMailInput.class,
            TopUpMailInput::occurredAt, pb -> pb
                .step("Load the top-up", QueryEntities.<ProcessContext>of(QbTopUps.defaultDataset(QbTopUps.TOPUP),
                    ctx -> byId("topUpId", input(ctx, TopUpMailInput.class).topUpId()), RECORD))
                .step("Load the sponsor", loadUser(ctx -> record(ctx) == null ? null
                    : record(ctx).get("sponsorId"))),
            ctx -> {
                EntityInstance topUp = record(ctx);
                if (topUp != null && topUp.get("paidAt") != null) {
                    // Kudos granted by hand (Q18b) are no payment received.
                    decide(ctx, QbTopUps.GRANT.equals(topUp.get("manualKind")) ? NotificationMails.TOPUP_GRANTED
                        : NotificationMails.TOPUP_PAID, Map.of("amount", MailValues.kudos(topUp.get("amount"))));
                }
            }, messages, NotificationMails.TOPUP_PAID, NotificationMails.TOPUP_GRANTED);
    }

    static TopUpMailInput topUpInput(DomainEvent event) {
        return new TopUpMailInput(text(event, "topUpId"), event.createdTime());
    }

    // ---- publications ----------------------------------------------------------------------------------------------

    public static ProcessDefinition<PublicationMailInput, MailOutput, ProcessContext> publication(
        MessageCatalog messages, MailValues values) {
        return define(PUBLICATION, "Mails a sponsor that their publication was approved, sent back, taken down, is "
                + "short of funds or ended.", PublicationMailInput.class, PublicationMailInput::occurredAt, pb -> pb
                // Only the throttled kind needs the lock and its row, both the sponsor's (one mail a day for all their
                // publications): the others are mailed once per event. Never a publication's lock.
                .step("Lock the sponsor's notices", HoldLock.<ProcessContext>exclusive(ctx -> fundsShort(ctx)
                    ? QbMessaging.noticeLock(input(ctx, PublicationMailInput.class).sponsorId()) : null))
                .step("Load the last notice", loadNotice(ctx -> fundsShort(ctx)
                    ? input(ctx, PublicationMailInput.class).sponsorId() : null, FUNDS_SHORT_NOTICE))
                // The recipient, the name and the start come from the publication itself, not from the event.
                .step("Load the publication", QueryEntities.<ProcessContext>of(
                    QbPublications.defaultDataset(QbPublications.PUBLICATION), ctx -> byId("publicationId",
                        uuid(input(ctx, PublicationMailInput.class).publicationId())), RECORD))
                .step("Load the short publications", loadShort(ctx -> fundsShort(ctx)
                    ? input(ctx, PublicationMailInput.class).sponsorId() : null))
                .step("Load the wallet", loadWallet(ctx -> fundsShort(ctx) && record(ctx) != null
                    ? record(ctx).get("sponsorId") : null))
                .step("Load the counter", QueryEntities.<ProcessContext>of(
                    QbPublications.defaultDataset(QbPublications.COUNTER), ctx -> byId("publicationId",
                        ended(ctx) ? uuid(input(ctx, PublicationMailInput.class).publicationId()) : null), COUNTER))
                .step("Load the sponsor's ban", QueryEntities.<ProcessContext>of(
                    QbAccounts.defaultDataset(QbAccounts.BAN), ctx -> byId("userId", ended(ctx)
                        ? uuid(input(ctx, PublicationMailInput.class).sponsorId()) : null), BAN))
                .step("Load the sponsor", loadUser(ctx -> record(ctx) == null ? null
                    : record(ctx).get("sponsorId"))),
            ctx -> publicationMail(ctx, messages, values), messages, NotificationMails.PUBLICATION_APPROVED,
            NotificationMails.PUBLICATION_REJECTED, NotificationMails.PUBLICATION_TAKEN_DOWN,
            NotificationMails.PUBLICATION_FUNDS_SHORT, NotificationMails.PUBLICATION_ENDED);
    }

    private static boolean fundsShort(ProcessContext ctx) {
        return PublicationNotices.FUNDS_SHORT.equals(input(ctx, PublicationMailInput.class).kind());
    }

    private static boolean ended(ProcessContext ctx) {
        return PublicationNotices.ENDED.equals(input(ctx, PublicationMailInput.class).kind());
    }

    static void publicationMail(ProcessContext ctx, MessageCatalog messages, MailValues values) {
        PublicationMailInput input = input(ctx, PublicationMailInput.class);
        EntityInstance publication = record(ctx);
        if (publication == null || !String.valueOf((Object) publication.get("sponsorId")).equals(input.sponsorId())) {
            return;
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", publication.get("name"));
        switch (input.kind()) {
            case PublicationNotices.APPROVED -> {
                // Live at once when its start had come by the approval; the start as the publication has it.
                Instant startAt = instant(publication.get("startAt"));
                params.put("start", startAt == null || !startAt.isAfter(input.occurredAt())
                    ? text(messages, ctx, "qb.mail.approved.live")
                    : text(messages, ctx, "qb.mail.approved.from").replace("{startAt}", values.time(startAt)));
                decide(ctx, NotificationMails.PUBLICATION_APPROVED, params);
            }
            case PublicationNotices.REJECTED -> {
                params.put("reason", input.reason() == null ? "" : input.reason());
                decide(ctx, NotificationMails.PUBLICATION_REJECTED, params);
            }
            case PublicationNotices.TAKEN_DOWN -> {
                params.put("reason", input.reason() == null ? "" : input.reason());
                decide(ctx, NotificationMails.PUBLICATION_TAKEN_DOWN, params);
            }
            case PublicationNotices.FUNDS_SHORT -> {
                if (freshNotification(ctx) && addressed(ctx)) {
                    fundsShortMail(ctx, input.sponsorId());
                }
            }
            case PublicationNotices.ENDED -> {
                // A publication an administrator took down (also by a ban, also once it ran out of time later) or
                // of a banned sponsor: the takedown mail said what there is to say.
                EntityInstance ban = first(ctx, BAN);
                if (PublicationAdmin.takenDown(publication) || ban != null && BanRules.banned(ban.attributes())) {
                    return;
                }
                params.put("reason", text(messages, ctx, "qb.mail.end-reason." + input.reason()));
                EntityInstance counter = first(ctx, COUNTER);
                params.put("completions", MailValues.count(counter == null ? null : counter.get("completions")));
                decide(ctx, NotificationMails.PUBLICATION_ENDED, params);
            }
            default -> { }
        }
    }

    /**
     * The short-of-funds mail of a sponsor, under their notices' lock: at most once per
     * {@link NotificationMails#NOTICE_INTERVAL}. Within the window the notice is marked pending and the job
     * {@value #FUNDS_SHORT_JOB} sends it once the window has passed, if a publication is still short then. The mail
     * names every publication of the sponsor short of funds when it is sent; none left (topped up meanwhile): no mail.
     * The row is kept whether the mail is then sent or skipped as unsubscribed.
     */
    private static void fundsShortMail(ProcessContext ctx, String sponsorId) {
        List<EntityInstance> shortOnes = list(ctx, CARD);
        if (shortOnes.isEmpty()) {
            return;
        }
        if (withinWindow(ctx)) {
            EntityInstance notice = first(ctx, NOTICE);
            if (!Boolean.TRUE.equals(notice.get("pending"))) {
                ctx.changes().update(QbMessaging.NOTICE, notice.id(), notice.version(), Map.of("pending", true));
            }
            return;
        }
        sent(ctx, sponsorId, FUNDS_SHORT_NOTICE);
        decide(ctx, NotificationMails.PUBLICATION_FUNDS_SHORT, fundsShortParams(ctx, shortOnes));
    }

    private static Map<String, Object> fundsShortParams(ProcessContext ctx, List<EntityInstance> shortOnes) {
        EntityInstance wallet = first(ctx, WALLET);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("publications", MailValues.names(shortOnes.stream().map(card -> (Object) card.get("name"))
            .toList(), MAX_LISTED));
        params.put("pendingCount", MailValues.count(wallet == null ? null : wallet.get("pendingCount")));
        params.put("pendingAmount", MailValues.kudos(wallet == null ? null : wallet.get("pendingAmount")));
        return params;
    }

    static PublicationMailInput publicationInput(DomainEvent event) {
        return new PublicationMailInput(text(event, "publicationId"), text(event, "sponsorId"), text(event, "kind"),
            text(event, "reason"), text(event, "name"), text(event, "startAt"), event.createdTime());
    }

    // ---- the deferred short-of-funds mail --------------------------------------------------------------------------

    /**
     * {@value #FUNDS_SHORT_DUE}, one sponsor of the job {@value #FUNDS_SHORT_JOB}: under the sponsor's notices' lock,
     * the notice read again; when it is still pending and its window has passed, the publications short of funds now
     * are mailed (and the window starts again), or, none being short any more, only the mark is cleared.
     */
    public static ProcessDefinition<SponsorRef, MailOutput, ProcessContext> fundsShortDue(MessageCatalog messages) {
        return define(FUNDS_SHORT_DUE, "Mails a sponsor the short-of-funds notice deferred within the day.",
            SponsorRef.class, input -> null, pb -> pb
                .step("Lock the sponsor's notices", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbMessaging.noticeLock(input(ctx, SponsorRef.class).sponsorId())))
                .step("Load the last notice", loadNotice(ctx -> input(ctx, SponsorRef.class).sponsorId(),
                    FUNDS_SHORT_NOTICE))
                .step("Load the short publications", loadShort(ctx -> input(ctx, SponsorRef.class).sponsorId()))
                .step("Load the wallet", loadWallet(ctx -> input(ctx, SponsorRef.class).sponsorId()))
                .step("Load the sponsor", loadUser(ctx -> input(ctx, SponsorRef.class).sponsorId())),
            ctx -> {
                EntityInstance notice = first(ctx, NOTICE);
                if (notice == null || !Boolean.TRUE.equals(notice.get("pending")) || withinWindow(ctx)) {
                    return;
                }
                List<EntityInstance> shortOnes = list(ctx, CARD);
                if (shortOnes.isEmpty() || !addressed(ctx)) {
                    ctx.changes().update(QbMessaging.NOTICE, notice.id(), notice.version(), Map.of("pending", false));
                    return;
                }
                sent(ctx, input(ctx, SponsorRef.class).sponsorId(), FUNDS_SHORT_NOTICE);
                decide(ctx, NotificationMails.PUBLICATION_FUNDS_SHORT, fundsShortParams(ctx, shortOnes));
            }, messages, NotificationMails.PUBLICATION_FUNDS_SHORT);
    }

    // ---- shared ----------------------------------------------------------------------------------------------------

    /**
     * A mail process: its reads, the decision (which puts {@link #SEND} and {@link #PARAMS} through {@link #decide}),
     * and a {@link SendMail} step per template it may send, of which at most one sends.
     *
     * @param occurredAt when the event was published; null for a job's item (now)
     */
    @SafeVarargs
    private static <I> ProcessDefinition<I, MailOutput, ProcessContext> define(String name, String description,
        Class<I> inputType, Function<I, Instant> occurredAt,
        java.util.function.Consumer<ProcessDefinitionBuilder<I, MailOutput, ProcessContext>> reads,
        java.util.function.Consumer<ProcessContext> decision, MessageCatalog messages, MailTemplate... templates) {
        return ProcessDefinition.define(name, 1, inputType, MailOutput.class, ProcessContext.class, pb -> {
            pb.description(description)
                .permissions(QbPermissions.MESSAGING_SYSTEM)
                .internal()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    Instant at = occurredAt.apply(input);
                    ctx.put(OCCURRED, at == null ? start.opTime() : at);
                    return ctx;
                })
                .outputMapper(ctx -> new MailOutput(ctx.contains(SEND) ? (String) ctx.get(SEND) : null));
            reads.accept(pb);
            pb.compute("Decide on the mail", (metadata, ctx) -> {
                if (!fresh(ctx, NotificationMails.TRANSACTIONAL_MAX_AGE)) {
                    return;
                }
                decision.accept(ctx);
                if (ctx.contains(SEND) && (!addressed(ctx) || !fresh(ctx, maxAge(ctx.get(SEND))))) {
                    ctx.put(SEND, null);
                }
                if (ctx.contains(SEND)) {
                    ctx.put(LOCALE, locale(messages, first(ctx, USER)));
                }
            });
            for (MailTemplate template : templates) {
                Map<String, Function<ProcessContext, ?>> params = new LinkedHashMap<>();
                for (String param : template.params()) {
                    params.put(param, ctx -> params(ctx).get(param));
                }
                pb.step("Mail " + template.name(), SendMail.<ProcessContext>when(
                    ctx -> template.name().equals(ctx.contains(SEND) ? ctx.get(SEND) : null), template,
                    ctx -> MailRecipient.user(first(ctx, USER).get("userId"), null, (Locale) ctx.get(LOCALE)),
                    params));
            }
        });
    }

    /** How old an event may be for a template: notifications go stale sooner than transactional mail. */
    private static Duration maxAge(Object template) {
        return NotificationMails.ALL.stream().filter(t -> t.name().equals(template)).findFirst()
            .filter(MailTemplate::unsubscribable).map(t -> NotificationMails.MAX_AGE)
            .orElse(NotificationMails.TRANSACTIONAL_MAX_AGE);
    }

    /** Decides to send {@code template} with {@code params} (each declared parameter, as text). */
    static void decide(ProcessContext ctx, MailTemplate template, Map<String, Object> params) {
        ctx.put(SEND, template.name());
        ctx.put(PARAMS, Map.copyOf(params));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> params(ProcessContext ctx) {
        return (Map<String, Object>) ctx.get(PARAMS);
    }

    /** Whether the event is recent enough for a mail that goes stale after {@code maxAge}. */
    static boolean fresh(ProcessContext ctx, Duration maxAge) {
        Instant occurredAt = (Instant) ctx.get(OCCURRED);
        return !ctx.opTime().isAfter(occurredAt.plus(maxAge));
    }

    /** Whether the event is recent enough for a notification (before the notice row is written). */
    private static boolean freshNotification(ProcessContext ctx) {
        return fresh(ctx, NotificationMails.MAX_AGE);
    }

    /** Whether the recipient was found and has an address. */
    static boolean addressed(ProcessContext ctx) {
        EntityInstance user = first(ctx, USER);
        Object email = user == null ? null : user.get("email");
        return email != null && !email.toString().isBlank();
    }

    /** Whether the last throttled notice ({@link #NOTICE}) went within {@link NotificationMails#NOTICE_INTERVAL}. */
    private static boolean withinWindow(ProcessContext ctx) {
        EntityInstance notice = first(ctx, NOTICE);
        Instant last = notice == null ? null : instant(notice.get("lastSentAt"));
        return last != null && ctx.opTime().isBefore(last.plus(NotificationMails.NOTICE_INTERVAL));
    }

    /** Records that a throttled notice goes now: the window starts again, nothing pending. */
    private static void sent(ProcessContext ctx, String recipientId, String kind) {
        EntityInstance notice = first(ctx, NOTICE);
        if (notice == null) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("recipientId", recipientId);
            row.put("kind", kind);
            row.put("lastSentAt", ctx.opTime());
            row.put("pending", false);
            ctx.changes().insert(QbMessaging.NOTICE, row);
        } else {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("lastSentAt", ctx.opTime());
            values.put("pending", false);
            ctx.changes().update(QbMessaging.NOTICE, notice.id(), notice.version(), values);
        }
    }

    /** The recipient's throttled notice of {@code kind}; none when there is no recipient. */
    private static StepSpec<QueryEntities.Metadata<ProcessContext>, ProcessContext> loadNotice(
        Function<ProcessContext, Object> recipientId, String kind) {
        return QueryEntities.<ProcessContext>of(QbMessaging.defaultDataset(QbMessaging.NOTICE), ctx -> {
            List<Object> ids = new ArrayList<>();
            Object id = recipientId.apply(ctx);
            if (id != null) {
                ids.add(String.valueOf(id));
            }
            return EntityQuery.builder().where(new QueryPredicate.And(List.of(
                new QueryPredicate.In("recipientId", ids), new QueryPredicate.Eq("kind", kind)))).limit(1).build();
        }, NOTICE);
    }

    /** Every publication of the sponsor short of funds now, by name (none without a sponsor). */
    private static StepSpec<QueryEntities.Metadata<ProcessContext>, ProcessContext> loadShort(
        Function<ProcessContext, Object> sponsorId) {
        return QueryEntities.<ProcessContext>of(QbPublications.defaultDataset(QbPublications.CARD), ctx -> {
            List<Object> sponsors = new ArrayList<>();
            Object id = sponsorId.apply(ctx);
            if (id != null) {
                sponsors.add(String.valueOf(id));
            }
            return EntityQuery.builder().where(new QueryPredicate.And(List.of(
                    new QueryPredicate.In("sponsorId", sponsors),
                    new QueryPredicate.Eq("status", PublicationStatus.FUNDS_SHORT))))
                .orderBy("name", true).limit(MAX_LISTED + 1).build();
        }, CARD);
    }

    private static StepSpec<QueryEntities.Metadata<ProcessContext>, ProcessContext> loadWallet(
        Function<ProcessContext, Object> sponsorId) {
        return QueryEntities.<ProcessContext>of(QbWallets.defaultDataset(QbWallets.WALLET), ctx -> {
            Object id = sponsorId.apply(ctx);
            return id != null ? QbWallets.query(QbWallets.SPONSOR, id) : byId("ownerId", null);
        }, WALLET);
    }

    /**
     * The language the platform renders the mail in: the user's, else the default, as supported ({@link SendMail}
     * resolves it the same way); given to the recipient so that the texts the process adds are in the same language.
     */
    static Locale locale(MessageCatalog messages, EntityInstance user) {
        Object locale = user == null ? null : user.get("locale");
        return messages.supported(locale == null || locale.toString().isBlank() ? messages.defaultLocale()
            : Locale.forLanguageTag(locale.toString()));
    }

    /** A message in the recipient's language. */
    private static String text(MessageCatalog messages, ProcessContext ctx, String code) {
        Locale locale = locale(messages, first(ctx, USER));
        return messages.find(code, locale).or(() -> messages.find(code, messages.defaultLocale())).orElse("");
    }

    private static StepSpec<QueryEntities.Metadata<ProcessContext>, ProcessContext> loadUser(
        Function<ProcessContext, Object> userId) {
        return QueryEntities.<ProcessContext>of(SecurityEntities.USER_DATASET, ctx -> byId("userId",
            uuid(userId.apply(ctx))), USER);
    }

    /** At most one row whose {@code field} is {@code id}; none when there is no id. */
    private static EntityQuery byId(String field, Object id) {
        List<Object> ids = new ArrayList<>();
        if (id != null) {
            ids.add(id);
        }
        return EntityQuery.builder().where(new QueryPredicate.In(field, ids)).limit(1).build();
    }

    private static UUID uuid(Object id) {
        if (id == null) {
            return null;
        }
        try {
            return id instanceof UUID uuid ? uuid : UUID.fromString(id.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Instant instant(Object value) {
        return value == null ? null : value instanceof java.time.OffsetDateTime time ? time.toInstant()
            : value instanceof Instant time ? time : Instant.parse(value.toString());
    }

    private static <T> T input(ProcessContext ctx, Class<T> type) {
        return ctx.get(INPUT, type);
    }

    private static EntityInstance record(ProcessContext ctx) {
        return first(ctx, RECORD);
    }

    @SuppressWarnings("unchecked")
    private static List<EntityInstance> list(ProcessContext ctx, String key) {
        List<EntityInstance> rows = ctx.contains(key) ? (List<EntityInstance>) ctx.get(key) : null;
        return rows == null ? List.of() : rows;
    }

    @SuppressWarnings("unchecked")
    private static EntityInstance first(ProcessContext ctx, String key) {
        if (!ctx.contains(key)) {
            return null;
        }
        List<EntityInstance> rows = (List<EntityInstance>) ctx.get(key);
        return rows == null || rows.isEmpty() ? null : rows.getFirst();
    }

    private static String text(DomainEvent event, String key) {
        Object value = event.payload().get(key);
        return value == null ? null : String.valueOf(value);
    }
}
