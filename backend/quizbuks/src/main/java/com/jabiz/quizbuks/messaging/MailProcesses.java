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
import com.jabiz.quizbuks.publishing.PublicationNotices;
import com.jabiz.quizbuks.publishing.PublicationStatus;
import com.jabiz.quizbuks.publishing.QbPublications;
import com.jabiz.quizbuks.wallet.PayoutAccountStatus;
import com.jabiz.quizbuks.wallet.QbPayouts;
import com.jabiz.quizbuks.wallet.QbTopUps;
import com.jabiz.quizbuks.wallet.QbWallets;
import com.jabiz.quizbuks.wallet.TransferRules;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.mail.SendMail;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.security.SecurityEntities;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
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
 * the consumer of one or two events, {@code internal()} (platform decision D41: only the system runs it), with the
 * permission {@value QbPermissions#MESSAGING_SYSTEM} that no role holds. Each reads the record the event names (never
 * the event's own figures, which only point at it), the recipient's address and language, and sends through the
 * platform's {@link SendMail}: in the consumer's transaction, so the mail is recorded once per event; delivery and
 * unsubscribing are the platform's (a notification the recipient turned off is recorded as skipped, mail switched off
 * by {@code jabiz.mail.enabled} likewise).
 *
 * <ul>
 *   <li>{@value #TRANSFER}: {@code qb.transfer.sent} and {@code qb.transfer.failed}, to the taker;</li>
 *   <li>{@value #PAYOUT_ACCOUNT}: {@code qb.payout-account.restricted}, to the taker while the account is still held
 *       back;</li>
 *   <li>{@value #TOPUP}: {@code qb.topup.paid}, to the sponsor;</li>
 *   <li>{@value #PUBLICATION}: {@code qb.publication.status}, to the sponsor, the template by the kind of move; short
 *       of funds at most once per sponsor per {@link NotificationMails#FUNDS_SHORT_INTERVAL} (under the sponsor's
 *       {@link QbMessaging#noticeLock}, never a publication's lock), naming every publication still short.</li>
 * </ul>
 * Nothing is sent to a user without an address, for an event older than {@link NotificationMails#MAX_AGE}, or for a
 * record that is not there: the process ends without a mail and without failing (a failing consumer would be retried
 * for ever). Texts that depend on the language (why a transfer failed or a publication ended) are taken from the
 * messages in the recipient's language, the one the platform renders the mail in.
 */
public final class MailProcesses {

    public static final String TRANSFER = "QB_MAIL_TRANSFER";
    public static final String PAYOUT_ACCOUNT = "QB_MAIL_PAYOUT_ACCOUNT";
    public static final String TOPUP = "QB_MAIL_TOPUP";
    public static final String PUBLICATION = "QB_PUBLICATION_NOTIFY";

    public static final String TRANSFER_SENT_CONSUMER = "qb.mail-transfer-sent";
    public static final String TRANSFER_FAILED_CONSUMER = "qb.mail-transfer-failed";
    public static final String PAYOUT_RESTRICTED_CONSUMER = "qb.mail-payout-restricted";
    public static final String TOPUP_PAID_CONSUMER = "qb.mail-topup-paid";
    public static final String PUBLICATION_CONSUMER = "qb.publication-notify";

    /** The two outcomes of a transfer that are mailed. */
    public static final String SENT = "SENT";
    public static final String FAILED = "FAILED";

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

    /** @param template the template mailed; null when none was */
    public record MailOutput(String template) {}

    static final String INPUT = "input";
    static final String RECORD = "record";
    static final String USER = "user";
    static final String WALLET = "wallet";
    static final String COUNTER = "counter";
    static final String CARD = "card";
    static final String NOTICE = "notice";
    /** The template to send and its parameters, once decided. */
    static final String SEND = "send";
    static final String PARAMS = "params";
    static final String LOCALE = "locale";

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
                if (SENT.equals(kind)) {
                    params.put("fee", MailValues.kudos(transfer.get("fee")));
                    params.put("net", MailValues.kudos(transfer.get("net")));
                    decide(ctx, NotificationMails.TRANSFER_SENT, params);
                } else if (FAILED.equals(kind)) {
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
                .step("Load the payout account", QueryEntities.<ProcessContext>of(
                    QbPayouts.defaultDataset(QbPayouts.ACCOUNT), ctx -> byId("userId",
                        input(ctx, PayoutMailInput.class).userId()), RECORD))
                .step("Load the taker", loadUser(ctx -> record(ctx) == null ? null : record(ctx).get("userId"))),
            ctx -> {
                EntityInstance account = record(ctx);
                // Only while Stripe still holds it back: the taker may have given the details meanwhile.
                if (account != null && PayoutAccountStatus.RESTRICTED.equals(account.get("status"))) {
                    decide(ctx, NotificationMails.PAYOUT_ACTION_NEEDED, Map.of());
                }
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
                    decide(ctx, NotificationMails.TOPUP_PAID, Map.of("amount",
                        MailValues.kudos(topUp.get("amount"))));
                }
            }, messages, NotificationMails.TOPUP_PAID);
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
                .step("Load the last notice", QueryEntities.<ProcessContext>of(
                    QbMessaging.defaultDataset(QbMessaging.NOTICE), ctx -> {
                        PublicationMailInput input = input(ctx, PublicationMailInput.class);
                        List<Object> ids = new ArrayList<>();
                        if (fundsShort(ctx)) {
                            ids.add(input.sponsorId());
                        }
                        return EntityQuery.builder().where(new QueryPredicate.And(List.of(
                            new QueryPredicate.In("sponsorId", ids),
                            new QueryPredicate.Eq("kind", input.kind())))).limit(1).build();
                    }, NOTICE))
                // The recipient and the name come from the publication itself, not from the event.
                .step("Load the publication", QueryEntities.<ProcessContext>of(
                    QbPublications.defaultDataset(QbPublications.PUBLICATION), ctx -> byId("publicationId",
                        uuid(input(ctx, PublicationMailInput.class).publicationId())), RECORD))
                // Every publication of the sponsor still short of funds now (the one of the event may be on again).
                .step("Load the short publications", QueryEntities.<ProcessContext>of(
                    QbPublications.defaultDataset(QbPublications.CARD), ctx -> {
                        List<Object> sponsors = new ArrayList<>();
                        if (fundsShort(ctx)) {
                            sponsors.add(input(ctx, PublicationMailInput.class).sponsorId());
                        }
                        return EntityQuery.builder().where(new QueryPredicate.And(List.of(
                                new QueryPredicate.In("sponsorId", sponsors),
                                new QueryPredicate.Eq("status", PublicationStatus.FUNDS_SHORT))))
                            .orderBy("name", true).limit(MAX_LISTED + 1).build();
                    }, CARD))
                .step("Load the wallet", QueryEntities.<ProcessContext>of(
                    QbWallets.defaultDataset(QbWallets.WALLET), ctx -> fundsShort(ctx) && record(ctx) != null
                        ? QbWallets.query(QbWallets.SPONSOR, record(ctx).get("sponsorId"))
                        : byId("ownerId", null), WALLET))
                .step("Load the counter", QueryEntities.<ProcessContext>of(
                    QbPublications.defaultDataset(QbPublications.COUNTER), ctx -> byId("publicationId",
                        PublicationNotices.ENDED.equals(input(ctx, PublicationMailInput.class).kind())
                            ? uuid(input(ctx, PublicationMailInput.class).publicationId()) : null), COUNTER))
                .step("Load the sponsor", loadUser(ctx -> record(ctx) == null ? null
                    : record(ctx).get("sponsorId"))),
            ctx -> publicationMail(ctx, messages, values), messages, NotificationMails.PUBLICATION_APPROVED,
            NotificationMails.PUBLICATION_REJECTED, NotificationMails.PUBLICATION_TAKEN_DOWN,
            NotificationMails.PUBLICATION_FUNDS_SHORT, NotificationMails.PUBLICATION_ENDED);
    }

    private static boolean fundsShort(ProcessContext ctx) {
        return PublicationNotices.FUNDS_SHORT.equals(input(ctx, PublicationMailInput.class).kind());
    }

    /** At most this many publications are named in one short-of-funds mail; more are counted. */
    static final int MAX_LISTED = 10;

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
                params.put("startAt", values.time(input.startAt()));
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
            case PublicationNotices.FUNDS_SHORT -> fundsShortMail(ctx, params);
            case PublicationNotices.ENDED -> {
                params.put("reason", text(messages, ctx, "qb.mail.end-reason." + input.reason()));
                EntityInstance counter = first(ctx, COUNTER);
                params.put("completions", MailValues.count(counter == null ? null : counter.get("completions")));
                decide(ctx, NotificationMails.PUBLICATION_ENDED, params);
            }
            default -> { }
        }
    }

    /**
     * At most once per {@link NotificationMails#FUNDS_SHORT_INTERVAL} per sponsor, naming every publication of theirs
     * that is short of funds when it is sent (none left, for a sponsor who topped up before: no mail); the row of the
     * last one is kept under the sponsor's notices' lock, whether the mail is then sent or skipped as unsubscribed.
     */
    private static void fundsShortMail(ProcessContext ctx, Map<String, Object> params) {
        List<EntityInstance> shortOnes = list(ctx, CARD);
        if (shortOnes.isEmpty()) {
            return;
        }
        EntityInstance notice = first(ctx, NOTICE);
        Instant last = notice == null ? null : instant(notice.get("lastSentAt"));
        if (last != null && ctx.opTime().isBefore(last.plus(NotificationMails.FUNDS_SHORT_INTERVAL))) {
            return;
        }
        if (!ctx.contains(USER) || !addressed(ctx)) {
            return;
        }
        if (notice == null) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sponsorId", input(ctx, PublicationMailInput.class).sponsorId());
            row.put("kind", PublicationNotices.FUNDS_SHORT);
            row.put("lastSentAt", ctx.opTime());
            ctx.changes().insert(QbMessaging.NOTICE, row);
        } else {
            ctx.changes().update(QbMessaging.NOTICE, notice.id(), notice.version(),
                Map.of("lastSentAt", ctx.opTime()));
        }
        EntityInstance wallet = first(ctx, WALLET);
        params.put("publications", MailValues.names(shortOnes.stream().map(card -> (Object) card.get("name"))
            .toList(), MAX_LISTED));
        params.put("pendingCount", MailValues.count(wallet == null ? null : wallet.get("pendingCount")));
        params.put("pendingAmount", MailValues.kudos(wallet == null ? null : wallet.get("pendingAmount")));
        decide(ctx, NotificationMails.PUBLICATION_FUNDS_SHORT, params);
    }

    static PublicationMailInput publicationInput(DomainEvent event) {
        return new PublicationMailInput(text(event, "publicationId"), text(event, "sponsorId"), text(event, "kind"),
            text(event, "reason"), text(event, "name"), text(event, "startAt"), event.createdTime());
    }

    // ---- shared ----------------------------------------------------------------------------------------------------

    /**
     * A mail process: its reads, the decision (which puts {@link #SEND} and {@link #PARAMS} through {@link #decide}),
     * and a {@link SendMail} step per template it may send, of which at most one sends.
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
                    return ctx;
                })
                .outputMapper(ctx -> new MailOutput(ctx.contains(SEND) ? (String) ctx.get(SEND) : null));
            reads.accept(pb);
            pb.compute("Decide on the mail", (metadata, ctx) -> {
                if (!fresh(ctx, occurredAt.apply(input(ctx, inputType)))) {
                    return;
                }
                decision.accept(ctx);
                if (ctx.contains(SEND) && !addressed(ctx)) {
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

    /** Decides to send {@code template} with {@code params} (each declared parameter, as text). */
    static void decide(ProcessContext ctx, MailTemplate template, Map<String, Object> params) {
        ctx.put(SEND, template.name());
        ctx.put(PARAMS, Map.copyOf(params));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> params(ProcessContext ctx) {
        return (Map<String, Object>) ctx.get(PARAMS);
    }

    /** Whether an event is recent enough to mail ({@link NotificationMails#MAX_AGE}). */
    static boolean fresh(ProcessContext ctx, Instant occurredAt) {
        return occurredAt != null && !ctx.opTime().isAfter(occurredAt.plus(NotificationMails.MAX_AGE));
    }

    /** Whether the recipient was found and has an address. */
    static boolean addressed(ProcessContext ctx) {
        EntityInstance user = first(ctx, USER);
        Object email = user == null ? null : user.get("email");
        return email != null && !email.toString().isBlank();
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
