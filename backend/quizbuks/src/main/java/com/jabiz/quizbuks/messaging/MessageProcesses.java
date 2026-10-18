package com.jabiz.quizbuks.messaging;

import com.jabiz.event.DomainEvent;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.play.QbPlay;
import com.jabiz.quizbuks.play.RewardStatus;
import com.jabiz.quizbuks.publishing.QbPublications;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.wallet.PayoutAccountStatus;
import com.jabiz.quizbuks.wallet.QbPayouts;
import com.jabiz.quizbuks.wallet.QbWallets;
import com.jabiz.quizbuks.wallet.TransferRules;
import com.jabiz.quizbuks.wallet.TransferStatus;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The personal messages (docs/quizbuks/plans/Q7-messages-legal.md, Q7c, D-Q7-7, D-Q7-8): only ever made by the
 * system, never in the processes of answering or paying (their events are delivered to these consumers afterwards).
 * <ul>
 *   <li>{@value #FROM_EVENT}, the consumer of five events: a reward paid ({@code qb.reward.settled}) or pending
 *       ({@code qb.reward.pending}), a transfer sent or failed, a payout account held back. It reads the record the
 *       event names (the attempt, the transfer, the account: the recipient and the amounts come from it, not from the
 *       event) and makes one message while what it says still holds;</li>
 *   <li>{@value #PAYOUT_REMINDER}, the item of the daily job {@value QbInbox#REMINDER_JOB} (Q7-16): a taker whose
 *       balance reached the transfer threshold without a payout account that can receive it is reminded, at most once
 *       in {@code qb.payout.reminder-days}.</li>
 * </ul>
 * Both are {@code internal()} (decision D41), with the permission {@value QbPermissions#MESSAGING_SYSTEM} that no role
 * holds. A message is made once per {@code sourceKey} (unique): a redelivered or replayed event finds it and makes
 * nothing. A new subscription is handed the events published before it (platform 11 §2.3): those older than
 * {@link #MAX_AGE} make nothing, as Q7a's mail, so that deploying the inbox does not fill it with old news.
 */
public final class MessageProcesses {

    public static final String FROM_EVENT = "QB_MESSAGE_FROM_EVENT";
    public static final String PAYOUT_REMINDER = "QB_PAYOUT_REMINDER";

    public static final String REWARD_SETTLED_CONSUMER = "qb.msg-reward-settled";
    public static final String REWARD_PENDING_CONSUMER = "qb.msg-reward-pending";
    public static final String TRANSFER_SENT_CONSUMER = "qb.msg-transfer-sent";
    public static final String TRANSFER_FAILED_CONSUMER = "qb.msg-transfer-failed";
    public static final String PAYOUT_RESTRICTED_CONSUMER = "qb.msg-payout-restricted";

    /** An event older than this makes no message (a replay to a new subscription, a long backlog). */
    public static final Duration MAX_AGE = Duration.ofDays(7);
    /** The default of {@code qb.payout.reminder-days}. */
    public static final int DEFAULT_REMINDER_DAYS = 30;

    private static final String UUID_PATTERN =
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

    /**
     * @param kind       the message to make ({@code QbInbox.REWARD_PAID} …)
     * @param sourceId   the attempt, the transfer or the user the event names
     * @param occurredAt when the event was published
     */
    public record EventInput(@NotBlank @Size(max = 30) String kind, @NotBlank @Size(max = 128) String sourceId,
        @Pattern(regexp = UUID_PATTERN) String publicationId, @NotNull Instant occurredAt) {}

    /** The item of the reminder job: a taker. */
    public record ReminderInput(@NotBlank @Size(max = 128) String userId) {}

    /** @param messageId the message made; null when none was */
    public record MessageOutput(String messageId) {}

    static final String INPUT = "input";
    static final String SOURCE_KEY = "sourceKey";
    static final String EXISTING = "existing";
    static final String ATTEMPT = "attempt";
    static final String CARD = "card";
    static final String TRANSFER = "transfer";
    static final String ACCOUNT = "account";
    static final String WALLET = "wallet";
    static final String LAST = "lastReminder";
    static final String PARAMS = "params";
    static final String OUTPUT = "output";

    private MessageProcesses() {}

    // ---- from events -----------------------------------------------------------------------------------------------

    public static ProcessDefinition<EventInput, MessageOutput, ProcessContext> fromEvent(MessageTexts texts) {
        return ProcessDefinition.define(FROM_EVENT, 1, EventInput.class, MessageOutput.class, ProcessContext.class,
            pb -> pb
                .description("Makes a taker's message from a reward, transfer or payout account event.")
                .permissions(QbPermissions.MESSAGING_SYSTEM)
                .internal()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    ctx.put(SOURCE_KEY, sourceKey(input));
                    return ctx;
                })
                .outputMapper(ctx -> new MessageOutput(ctx.contains(OUTPUT) ? (String) ctx.get(OUTPUT) : null))
                // One statement: the message made already, and the record the event names (only the one its kind
                // reads: the others have no key).
                .step("Load the source", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbInbox.defaultDataset(QbInbox.MESSAGE), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.Eq("sourceKey", ctx.get(SOURCE_KEY))).limit(1).build(), EXISTING),
                    QueryEntities.query(QbPlay.defaultDataset(QbPlay.ATTEMPT), ctx -> byId("attemptId",
                        reward(ctx) ? input(ctx).sourceId() : null), ATTEMPT),
                    QueryEntities.query(QbPublications.defaultDataset(QbPublications.CARD), ctx -> byId(
                        "publicationId", reward(ctx) ? uuid(input(ctx).publicationId()) : null), CARD),
                    QueryEntities.query(QbPayouts.defaultDataset(QbPayouts.TRANSFER), ctx -> byId("transferId",
                        transfer(ctx) ? input(ctx).sourceId() : null), TRANSFER),
                    QueryEntities.query(QbPayouts.defaultDataset(QbPayouts.ACCOUNT), ctx -> byId("userId",
                        QbInbox.PAYOUT_ACTION_NEEDED.equals(input(ctx).kind()) ? input(ctx).sourceId() : null),
                        ACCOUNT)))
                .compute("Make the message", (metadata, ctx) -> fromEvent(ctx, texts)));
    }

    /** One message per attempt and kind, per transfer and kind, per user held back and day. */
    static String sourceKey(EventInput input) {
        String id = input.sourceId();
        if (QbInbox.PAYOUT_ACTION_NEEDED.equals(input.kind())) {
            return "payout-action:" + id + ":" + input.occurredAt().atOffset(ZoneOffset.UTC).toLocalDate();
        }
        return input.kind().toLowerCase(java.util.Locale.ROOT).replace('_', '-') + ":" + id;
    }

    private static boolean reward(ProcessContext ctx) {
        String kind = input(ctx).kind();
        return QbInbox.REWARD_PAID.equals(kind) || QbInbox.REWARD_PENDING.equals(kind);
    }

    private static boolean transfer(ProcessContext ctx) {
        String kind = input(ctx).kind();
        return QbInbox.TRANSFER_SENT.equals(kind) || QbInbox.TRANSFER_FAILED.equals(kind);
    }

    static void fromEvent(ProcessContext ctx, MessageTexts texts) {
        EventInput input = input(ctx);
        if (first(ctx, EXISTING) != null || ctx.opTime().isAfter(input.occurredAt().plus(MAX_AGE))) {
            return;
        }
        Map<String, MessageTexts.Value> params = new LinkedHashMap<>();
        Draft draft = switch (input.kind()) {
            case QbInbox.REWARD_PAID, QbInbox.REWARD_PENDING -> rewardMessage(ctx, input, params);
            case QbInbox.TRANSFER_SENT, QbInbox.TRANSFER_FAILED -> transferMessage(ctx, texts, input, params);
            case QbInbox.PAYOUT_ACTION_NEEDED -> payoutMessage(ctx);
            default -> null;
        };
        if (draft != null) {
            insert(ctx, texts, draft, params, (String) ctx.get(SOURCE_KEY));
        }
    }

    /** What a message will be, but its texts. */
    record Draft(String recipientId, String type, String kind, Object publicationId, String action,
        String actionId) {}

    private static Draft rewardMessage(ProcessContext ctx, EventInput input, Map<String, MessageTexts.Value> params) {
        EntityInstance attempt = first(ctx, ATTEMPT);
        EntityInstance card = first(ctx, CARD);
        // Only while it is so: a pending reward paid since says so with its own message.
        String status = QbInbox.REWARD_PAID.equals(input.kind()) ? RewardStatus.PAID : RewardStatus.PENDING;
        if (attempt == null || card == null || !status.equals(attempt.get("rewardStatus"))
            || !String.valueOf((Object) attempt.get("publicationId")).equals(String.valueOf(card.id()))) {
            return null;
        }
        BigDecimal reward = money(attempt.get("reward"));
        BigDecimal fee = money(attempt.get("fee"));
        params.put("amount", MessageTexts.Value.of(MailValues.kudos(RewardStatus.PAID.equals(status)
            ? reward.subtract(fee) : reward)));
        params.put("name", MessageTexts.Value.of(card.get("name")));
        return new Draft(attempt.get("userId"), QbInbox.TYPE_REWARD, input.kind(), card.id(), QbInbox.ACTION_RESULT,
            String.valueOf(attempt.id()));
    }

    private static Draft transferMessage(ProcessContext ctx, MessageTexts texts, EventInput input,
        Map<String, MessageTexts.Value> params) {
        EntityInstance transfer = first(ctx, TRANSFER);
        String status = QbInbox.TRANSFER_SENT.equals(input.kind()) ? TransferStatus.SENT : TransferStatus.FAILED;
        if (transfer == null || !status.equals(transfer.get("status"))) {
            return null;
        }
        params.put("amount", MessageTexts.Value.of(MailValues.kudos(transfer.get("amount"))));
        params.put("net", MessageTexts.Value.of(MailValues.kudos(transfer.get("net"))));
        params.put("fee", MessageTexts.Value.of(MailValues.kudos(transfer.get("fee"))));
        if (TransferStatus.FAILED.equals(status)) {
            // Whether the taker can do something about it, never Stripe's own words (as the mail).
            params.put("reason", texts.message("qb.mail.transfer-failure."
                + (TransferRules.ACCOUNT_FAILURES.contains(transfer.get("failureCode")) ? "ACCOUNT" : "OTHER")));
        }
        return new Draft(transfer.get("userId"), QbInbox.TYPE_SYSTEM, input.kind(), null,
            TransferStatus.FAILED.equals(status) ? QbInbox.ACTION_PAYOUT : QbInbox.ACTION_WALLET, null);
    }

    private static Draft payoutMessage(ProcessContext ctx) {
        EntityInstance account = first(ctx, ACCOUNT);
        if (account == null || !PayoutAccountStatus.RESTRICTED.equals(account.get("status"))) {
            return null;
        }
        return new Draft(account.get("userId"), QbInbox.TYPE_SYSTEM, QbInbox.PAYOUT_ACTION_NEEDED, null,
            QbInbox.ACTION_PAYOUT, null);
    }

    private static void insert(ProcessContext ctx, MessageTexts texts, Draft draft,
        Map<String, MessageTexts.Value> params, String sourceKey) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("recipientId", draft.recipientId());
        values.put("type", draft.type());
        values.put("kind", draft.kind());
        values.put("title", texts.render(MessageTexts.titleCode(draft.kind()), params));
        values.put("body", texts.render(MessageTexts.bodyCode(draft.kind()), params));
        values.put("publicationId", draft.publicationId());
        values.put("action", draft.action());
        values.put("actionId", draft.actionId());
        values.put("sourceKey", sourceKey);
        values.put("createdAt", ctx.opTime());
        values.put("readAt", null);
        values.put("clickedAt", null);
        ctx.put(OUTPUT, String.valueOf(ctx.changes().insert(QbInbox.MESSAGE, values)));
    }

    static EventInput rewardInput(DomainEvent event, String kind) {
        return new EventInput(kind, text(event, "attemptId"), text(event, "publicationId"), event.createdTime());
    }

    static EventInput transferInput(DomainEvent event, String kind) {
        return new EventInput(kind, text(event, "transferId"), null, event.createdTime());
    }

    static EventInput payoutInput(DomainEvent event) {
        return new EventInput(QbInbox.PAYOUT_ACTION_NEEDED, text(event, "userId"), null, event.createdTime());
    }

    // ---- the payout reminder ---------------------------------------------------------------------------------------

    public static ProcessDefinition<ReminderInput, MessageOutput, ProcessContext> payoutReminder(MessageTexts texts) {
        return ProcessDefinition.define(PAYOUT_REMINDER, 1, ReminderInput.class, MessageOutput.class,
            ProcessContext.class, pb -> pb
                .description("Reminds a taker whose rewards reached the transfer threshold to open a payout account.")
                .permissions(QbPermissions.MESSAGING_SYSTEM)
                .internal()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(ctx -> new MessageOutput(ctx.contains(OUTPUT) ? (String) ctx.get(OUTPUT) : null))
                // The job's template chose the taker; everything is read again here, in one statement.
                .step("Load the taker's standing", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbWallets.defaultDataset(QbWallets.WALLET), ctx -> QbWallets.query(
                        QbWallets.TAKER, reminderInput(ctx).userId()), WALLET),
                    QueryEntities.query(QbPayouts.defaultDataset(QbPayouts.ACCOUNT), ctx -> byId("userId",
                        reminderInput(ctx).userId()), ACCOUNT),
                    QueryEntities.query(QbInbox.defaultDataset(QbInbox.MESSAGE), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.And(List.of(
                            new QueryPredicate.Eq("recipientId", reminderInput(ctx).userId()),
                            new QueryPredicate.Eq("kind", QbInbox.PAYOUT_REMINDER))))
                        .orderBy("createdAt", false).limit(1).build(), LAST),
                    ParamReads.query(PARAMS, List.of(QbParams.TRANSFER_THRESHOLD, QbParams.PAYOUT_REMINDER_DAYS))))
                .compute("Remind", (metadata, ctx) -> remind(ctx, texts)));
    }

    static void remind(ProcessContext ctx, MessageTexts texts) {
        String userId = reminderInput(ctx).userId();
        EntityInstance wallet = first(ctx, WALLET);
        EntityInstance account = first(ctx, ACCOUNT);
        EntityInstance last = first(ctx, LAST);
        Map<String, Object> params = ParamReads.values(ctx, PARAMS);
        BigDecimal threshold = decimal(params.get(QbParams.TRANSFER_THRESHOLD));
        int days = ParamReads.days(params.get(QbParams.PAYOUT_REMINDER_DAYS), DEFAULT_REMINDER_DAYS);
        if (wallet == null || threshold == null || threshold.signum() <= 0
            || QbWallets.balance(wallet).compareTo(threshold) < 0
            || account != null && PayoutAccountStatus.ENABLED.equals(account.get("status"))
            || last != null && ctx.opTime().isBefore(instant(last.get("createdAt")).plus(Duration.ofDays(days)))) {
            return;
        }
        Map<String, MessageTexts.Value> values = new LinkedHashMap<>();
        values.put("amount", MessageTexts.Value.of(MailValues.kudos(QbWallets.balance(wallet))));
        values.put("threshold", MessageTexts.Value.of(MailValues.kudos(threshold)));
        insert(ctx, texts, new Draft(userId, QbInbox.TYPE_REMINDER, QbInbox.PAYOUT_REMINDER, null,
            QbInbox.ACTION_PAYOUT, null), values, "payout-reminder:" + userId + ":"
            + ctx.opTime().atOffset(ZoneOffset.UTC).toLocalDate());
    }

    // ---- shared ----------------------------------------------------------------------------------------------------

    private static EventInput input(ProcessContext ctx) {
        return ctx.get(INPUT, EventInput.class);
    }

    private static ReminderInput reminderInput(ProcessContext ctx) {
        return ctx.get(INPUT, ReminderInput.class);
    }

    /** At most one row whose {@code field} is {@code id}; none when there is no id. */
    static EntityQuery byId(String field, Object id) {
        List<Object> ids = new ArrayList<>();
        if (id != null) {
            ids.add(id);
        }
        return EntityQuery.builder().where(new QueryPredicate.In(field, ids)).limit(1).build();
    }

    static UUID uuid(Object id) {
        if (id == null) {
            return null;
        }
        try {
            return id instanceof UUID uuid ? uuid : UUID.fromString(id.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static BigDecimal money(Object value) {
        return value == null ? BigDecimal.ZERO : value instanceof BigDecimal d ? d : new BigDecimal(value.toString());
    }

    static BigDecimal decimal(Object value) {
        try {
            return value == null || value.toString().isBlank() ? null : new BigDecimal(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Instant instant(Object value) {
        return value == null ? null : value instanceof OffsetDateTime time ? time.toInstant()
            : value instanceof Instant time ? time : Instant.parse(value.toString());
    }

    @SuppressWarnings("unchecked")
    static EntityInstance first(ProcessContext ctx, String key) {
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
