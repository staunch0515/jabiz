package com.jabiz.quizbuks.messaging;

import com.jabiz.context.RequestContext;
import com.jabiz.entity.Violation;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.RunTemplate;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A user's own inbox (docs/quizbuks/plans/Q7-messages-legal.md, Q7c, D-Q7-8, D-Q7-9): marking a message or an
 * announcement read or clicked through ({@value #MARK}), marking everything read ({@value #READ_ALL}) and turning
 * promotions off ({@value #SETTINGS}, Q7-11: promotions only; system messages, rewards and reminders always come).
 * Each takes the user's inbox lock ({@link QbInbox#inboxLock}) before reading their rows again, so that two tabs
 * write one receipt. The user is the caller, never an input.
 *
 * <p>An item is marked only while it is in the caller's own inbox: the process runs the inbox's template for the one
 * item, as the caller ({@code qb.app.inbox} in the app, {@code qb.sponsor.inbox} in the sponsor console: the template
 * is the one place the matching is written), and anything it does not find is 404, another user's message as much as
 * an announcement for others or one withdrawn. Only the first reading and the first click count (Q7-14); "all read" is
 * a mark of the user's, not a reading (the statistics leave it out).
 */
public final class InboxProcesses {

    public static final String MARK = "QB_MESSAGE_MARK";
    public static final String READ_ALL = "QB_INBOX_READ_ALL";
    public static final String SETTINGS = "QB_INBOX_SETTINGS_SAVE";

    public static final String READ = "READ";
    public static final String CLICK = "CLICK";

    /**
     * @param kind   {@code MESSAGE} or {@code BROADCAST}
     * @param action {@code READ} (opened) or {@code CLICK} ("view the quiz"; reads it too)
     */
    public record MarkInput(@NotBlank @Size(max = 10) String kind, @NotBlank @Size(max = 36) String id,
        @NotBlank @Size(max = 10) String action) {}

    /** @param changed whether this was the first such mark */
    public record MarkOutput(boolean changed) {}

    public record ReadAllInput() {}

    public record SettingsInput(@NotNull Boolean promoOff) {}

    public record StateOutput(Instant readAllBefore, boolean promoOff) {}

    static final String INPUT = "input";
    static final String APP_ITEM = "appItem";
    static final String SPONSOR_ITEM = "sponsorItem";
    static final String MESSAGE = "message";
    static final String RECEIPT = "receipt";
    static final String STATE = "state";
    static final String OUTPUT = "output";

    private InboxProcesses() {}

    /** Whether the caller reads the sponsor console's inbox (its entry, or a sponsor's permissions outside the two). */
    static boolean sponsorConsole(RequestContext request) {
        if ("app".equals(request.entry())) {
            return false;
        }
        if ("sponsor".equals(request.entry())) {
            return true;
        }
        return !request.permissions().contains(QbPermissions.ME)
            && request.permissions().contains(QbPermissions.SPONSOR_ME);
    }

    public static ProcessDefinition<MarkInput, MarkOutput, ProcessContext> mark() {
        return ProcessDefinition.define(MARK, 1, MarkInput.class, MarkOutput.class, ProcessContext.class, pb -> {
            pb.description("Marks a message or an announcement of one's inbox read, or clicked through to its quiz.")
                .permissions(QbPermissions.ACCOUNT_READ)
                .requiresVerifiedEmail()
                .contextFactory((start, input) -> start(start, input))
                .outputMapper(ctx -> ctx.get(OUTPUT, MarkOutput.class));
            lock(pb);
            // The item as the caller's inbox has it (their scope): one of the two runs, the other finds nothing.
            pb.step("Find it in the app's inbox", RunTemplate.<ProcessContext>of(QbInbox.APP_INBOX_TEMPLATE,
                    ctx -> itemParams(ctx, !sponsorConsole(ctx.request())), APP_ITEM))
                .step("Find it in the sponsor console's", RunTemplate.<ProcessContext>of(
                    QbInbox.SPONSOR_INBOX_TEMPLATE, ctx -> itemParams(ctx, sponsorConsole(ctx.request())),
                    SPONSOR_ITEM))
                .step("Load its row", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbInbox.defaultDataset(QbInbox.MESSAGE), ctx -> MessageProcesses.byId(
                        "messageId", message(ctx) ? markInput(ctx).id() : null), MESSAGE),
                    QueryEntities.query(QbInbox.defaultDataset(QbInbox.RECEIPT), ctx -> receiptQuery(ctx), RECEIPT)))
                .compute("Mark it", (metadata, ctx) -> mark(ctx));
        });
    }

    private static Map<String, Object> itemParams(ProcessContext ctx, boolean enabled) {
        MarkInput input = markInput(ctx);
        Map<String, Object> params = new HashMap<>();
        params.put("at", ctx.opTime());
        params.put("itemKind", input.kind());
        params.put("itemId", input.id());
        params.put("enabled", enabled);
        return params;
    }

    private static boolean message(ProcessContext ctx) {
        return QbInbox.ITEM_MESSAGE.equals(markInput(ctx).kind());
    }

    private static EntityQuery receiptQuery(ProcessContext ctx) {
        if (message(ctx)) {
            return MessageProcesses.byId("receiptId", null);
        }
        Object broadcastId = MessageProcesses.uuid(markInput(ctx).id());
        return EntityQuery.builder().where(new QueryPredicate.And(List.of(
            new QueryPredicate.Eq("userId", ctx.request().actorId()),
            new QueryPredicate.In("broadcastId", broadcastId == null ? List.of() : List.of(broadcastId)))))
            .limit(1).build();
    }

    @SuppressWarnings("unchecked")
    static void mark(ProcessContext ctx) {
        MarkInput input = markInput(ctx);
        if (!List.of(READ, CLICK).contains(input.action())
            || !List.of(QbInbox.ITEM_MESSAGE, QbInbox.ITEM_BROADCAST).contains(input.kind())) {
            throw new BusinessRuleViolationException(List.of(new Violation("action", BroadcastCodes.INVALID_VALUE,
                "kind MESSAGE or BROADCAST, action READ or CLICK", Map.of("field", "action", "value",
                    input.kind() + " " + input.action()))));
        }
        List<Map<String, Object>> found = new java.util.ArrayList<>();
        found.addAll((List<Map<String, Object>>) ctx.get(APP_ITEM));
        found.addAll((List<Map<String, Object>>) ctx.get(SPONSOR_ITEM));
        if (found.isEmpty()) {
            throw new EntityNotFoundException("Inbox item " + input.kind() + " " + input.id() + " not found");
        }
        boolean click = CLICK.equals(input.action());
        Map<String, Object> item = found.getFirst();
        if (click && item.get("publicationId") == null && !message(ctx)) {
            // Only an announcement of a quiz is clicked through; anything else is only read.
            click = false;
        }
        EntityInstance row = MessageProcesses.first(ctx, message(ctx) ? MESSAGE : RECEIPT);
        Map<String, Object> values = new LinkedHashMap<>();
        if (row == null || row.get("readAt") == null) {
            values.put("readAt", ctx.opTime());
        }
        if (click && (row == null || row.get("clickedAt") == null)) {
            values.put("clickedAt", ctx.opTime());
        }
        if (values.isEmpty()) {
            ctx.put(OUTPUT, new MarkOutput(false));
            return;
        }
        if (message(ctx)) {
            ctx.changes().update(QbInbox.MESSAGE, row.id(), row.version(), values);
        } else if (row == null) {
            Map<String, Object> receipt = new LinkedHashMap<>();
            receipt.put("userId", ctx.request().actorId());
            receipt.put("broadcastId", MessageProcesses.uuid(input.id()));
            receipt.put("readAt", values.get("readAt"));
            receipt.put("clickedAt", values.get("clickedAt"));
            ctx.changes().insert(QbInbox.RECEIPT, receipt);
        } else {
            ctx.changes().update(QbInbox.RECEIPT, row.id(), row.version(), values);
        }
        ctx.put(OUTPUT, new MarkOutput(true));
    }

    // ---- the inbox's state -----------------------------------------------------------------------------------------

    public static ProcessDefinition<ReadAllInput, StateOutput, ProcessContext> readAll() {
        return ProcessDefinition.define(READ_ALL, 1, ReadAllInput.class, StateOutput.class, ProcessContext.class,
            pb -> {
                pb.description("Marks everything in one's inbox read.")
                    .permissions(QbPermissions.ACCOUNT_READ)
                    .requiresVerifiedEmail()
                    .contextFactory((start, input) -> start(start, input))
                    .outputMapper(ctx -> ctx.get(OUTPUT, StateOutput.class));
                lock(pb);
                loadState(pb);
                pb.compute("Mark everything read", (metadata, ctx) -> writeState(ctx, ctx.opTime(), null));
            });
    }

    public static ProcessDefinition<SettingsInput, StateOutput, ProcessContext> settings() {
        return ProcessDefinition.define(SETTINGS, 1, SettingsInput.class, StateOutput.class, ProcessContext.class,
            pb -> {
                pb.description("Turns the promotions of one's inbox off or on again.")
                    .permissions(QbPermissions.ACCOUNT_READ)
                    .requiresVerifiedEmail()
                    .contextFactory((start, input) -> start(start, input))
                    .outputMapper(ctx -> ctx.get(OUTPUT, StateOutput.class));
                lock(pb);
                loadState(pb);
                pb.compute("Save", (metadata, ctx) -> writeState(ctx, null,
                    ctx.get(INPUT, SettingsInput.class).promoOff()));
            });
    }

    private static <I, O> void loadState(ProcessDefinitionBuilder<I, O, ProcessContext> pb) {
        pb.step("Load the inbox's state", QueryEntities.<ProcessContext>of(QbInbox.defaultDataset(QbInbox.STATE),
            ctx -> MessageProcesses.byId("userId", ctx.request().actorId()), STATE));
    }

    /** Writes the caller's state: "all read" now (when given), promotions off or on (when given). */
    private static void writeState(ProcessContext ctx, Instant readAllBefore, Boolean promoOff) {
        EntityInstance state = MessageProcesses.first(ctx, STATE);
        Map<String, Object> values = new LinkedHashMap<>();
        if (readAllBefore != null) {
            values.put("readAllBefore", readAllBefore);
        }
        if (promoOff != null) {
            values.put("promoOff", promoOff);
        }
        Instant before = readAllBefore;
        boolean off = Boolean.TRUE.equals(promoOff);
        if (state == null) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("userId", ctx.request().actorId());
            row.put("readAllBefore", readAllBefore);
            row.put("promoOff", off);
            ctx.changes().insert(QbInbox.STATE, row);
        } else {
            before = readAllBefore != null ? readAllBefore : MessageProcesses.instant(state.get("readAllBefore"));
            off = promoOff != null ? promoOff : Boolean.TRUE.equals(state.get("promoOff"));
            if (promoOff != null && promoOff.equals(state.get("promoOff")) && readAllBefore == null) {
                values.clear();
            }
            if (!values.isEmpty()) {
                ctx.changes().update(QbInbox.STATE, state.id(), state.version(), values);
            }
        }
        ctx.put(OUTPUT, new StateOutput(before, off));
    }

    // ---- shared ----------------------------------------------------------------------------------------------------

    private static ProcessContext start(com.jabiz.process.ProcessStart start, Object input) {
        ProcessContext ctx = new ProcessContext(start);
        ctx.put(INPUT, input);
        return ctx;
    }

    private static <I, O> void lock(ProcessDefinitionBuilder<I, O, ProcessContext> pb) {
        pb.step("Lock the inbox", HoldLock.<ProcessContext>exclusive(
            ctx -> QbInbox.inboxLock(ctx.request().actorId())));
    }

    private static MarkInput markInput(ProcessContext ctx) {
        return ctx.get(INPUT, MarkInput.class);
    }
}
