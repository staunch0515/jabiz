package com.jabiz.quizbuks.conversation;

import com.jabiz.entity.Violation;
import com.jabiz.param.ParamValues;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.process.StepSpec;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.identity.AccountEditing;
import com.jabiz.quizbuks.identity.BanRules;
import com.jabiz.quizbuks.identity.CallerChecks;
import com.jabiz.quizbuks.identity.QbAccounts;
import com.jabiz.quizbuks.play.QbPlay;
import com.jabiz.quizbuks.publishing.PublisherKind;
import com.jabiz.quizbuks.publishing.QbPublications;
import com.jabiz.quizbuks.referral.QbReferrals;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.LoadParams;
import com.jabiz.runtime.process.steps.PublishEvent;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * The conversations' processes (Q82, docs/quizbuks/plans/Q82-conversations.md):
 * <ul>
 *   <li>{@value #OPEN}: a taker writes to the sponsor of a publication they completed or referred people to; the
 *       first message opens the conversation, a later one goes into it (and opens it again when it was closed). Only
 *       the taker opens one (D-Q82-3);</li>
 *   <li>{@value #SEND}: either side writes into an open conversation, at most {@code qb.conversation.daily-cap} a day
 *       each; a sponsor's message tells the taker ({@value QbConversations#MESSAGE_EVENT}, D-Q82-9);</li>
 *   <li>{@value #CLOSE}, {@value #READ}: either side closes it, or marks it read up to now;</li>
 *   <li>{@value #BLOCK}, {@value #UNBLOCK}: the taker blocks a conversation's sponsor (closing every conversation
 *       with them) and removes the block again (D-Q82-6);</li>
 *   <li>{@value #EXPIRE}: the daily job's item, closing a conversation without a message for
 *       {@code qb.conversation.close-days} days ({@code internal()}).</li>
 * </ul>
 * A conversation's writes take its lock ({@link QbConversations#lockName}, by taker and publication: a conversation's
 * pair never changes, so opening and the others share it) after reading the conversation outside it, then read it
 * again inside. Blocking takes the taker's account lock and closes the conversations at their versions read (a send
 * at the same moment fails as a conflict and finds the block when tried again). None of them takes a publication's,
 * start or wallet lock: answering is not touched. Not a conversation of the caller's is as none (404).
 */
public final class ConversationProcesses {

    public static final String OPEN = "QB_CONVERSATION_OPEN";
    public static final String SEND = "QB_CONVERSATION_SEND";
    public static final String CLOSE = "QB_CONVERSATION_CLOSE";
    public static final String READ = "QB_CONVERSATION_READ";
    public static final String BLOCK = "QB_CONVERSATION_BLOCK";
    public static final String UNBLOCK = "QB_CONVERSATION_UNBLOCK";
    public static final String EXPIRE = "QB_CONVERSATION_EXPIRE";

    static final String INPUT = "input";
    static final String OUTPUT = "output";
    static final String PARAMS = "params";
    static final String CARD = "card";
    static final String ATTEMPT = "attempt";
    static final String LINK = "referralLink";
    static final String BLOCK_ROW = "block";
    static final String SPONSOR_BAN = "sponsorBan";
    static final String OTHER_BAN = "otherBan";
    static final String FOUND = "found";
    static final String CONVERSATION = "conversation";
    static final String SENT_TODAY = "sentToday";
    static final String THEIRS = "theirConversations";
    static final String EVENT = "event";

    /** The most conversations a taker has with one sponsor that a block closes in one go (one per publication). */
    static final int MAX_PER_SPONSOR = 10_000;

    /**
     * @param publicationId the publication the conversation is about
     * @param body          the message
     */
    public record OpenInput(@NotNull UUID publicationId, @NotNull @Size(max = 4_000) String body) {}

    /**
     * @param conversationId the conversation written to
     * @param body           the message
     */
    public record SendInput(@NotBlank @Size(max = 36) String conversationId,
        @NotNull @Size(max = 4_000) String body) {}

    /** @param conversationId the conversation closed, read, or whose sponsor is blocked */
    public record ConversationRef(@NotBlank @Size(max = 36) String conversationId) {}

    /** @param blockId the block removed */
    public record UnblockInput(@NotBlank @Size(max = 36) String blockId) {}

    /**
     * @param conversationId the conversation
     * @param messageId      the message written (null when none was)
     * @param created        whether the conversation began with it
     */
    public record MessageOutput(String conversationId, String messageId, boolean created) {}

    /**
     * @param conversationId the conversation
     * @param status         its status after the process
     */
    public record StatusOutput(String conversationId, String status) {}

    /**
     * @param blockId the block (null after it was removed)
     * @param closed  how many conversations the block closed
     */
    public record BlockOutput(String blockId, int closed) {}

    private ConversationProcesses() {}

    private static <I, O> void common(ProcessDefinitionBuilder<I, O, ProcessContext> pb, String description) {
        pb.description(description)
            .permissions(QbPermissions.CONVERSATION_USE)
            .requiresVerifiedEmail()
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(INPUT, input);
                return ctx;
            });
        CallerChecks.refuseBanned(pb);
    }

    // ---- open ------------------------------------------------------------------------------------------------------

    public static ProcessDefinition<OpenInput, MessageOutput, ProcessContext> open() {
        return ProcessDefinition.define(OPEN, 1, OpenInput.class, MessageOutput.class, ProcessContext.class, pb -> {
            common(pb, "Writes to the sponsor of a publication the caller completed or referred people to: opens the "
                + "conversation about it, or writes into it (opening it again when it was closed).");
            pb.outputMapper(ctx -> ctx.get(OUTPUT, MessageOutput.class))
                .compute("Check the message", (metadata, ctx) -> checkBody(openInput(ctx).body()))
                .step("Load the parameters", LoadParams.<ProcessContext>of(ProcessContext::opTime, PARAMS,
                    QbParams.CONVERSATION_DAILY_CAP))
                .step("Load the card, the caller's attempt and referral link", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbPublications.defaultDataset(QbPublications.CARD), ctx -> EntityQuery
                        .builder().where(new QueryPredicate.Eq("publicationId", openInput(ctx).publicationId()))
                        .limit(1).build(), CARD),
                    QueryEntities.query(QbPublications.defaultDataset(QbPlay.ATTEMPT), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.And(List.of(
                            new QueryPredicate.Eq("userId", ctx.request().actorId()),
                            new QueryPredicate.Eq("publicationId", openInput(ctx).publicationId()))))
                        .limit(1).build(), ATTEMPT),
                    QueryEntities.query(QbReferrals.defaultDataset(QbReferrals.LINK), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.And(List.of(
                            new QueryPredicate.Eq("referrerId", ctx.request().actorId()),
                            new QueryPredicate.Eq("publicationId", openInput(ctx).publicationId().toString()))))
                        .limit(1).build(), LINK)))
                .compute("Refuse an unknown publication", (metadata, ctx) -> {
                    if (first(ctx, CARD) == null) {
                        throw new EntityNotFoundException(QbPublications.PUBLICATION + " "
                            + openInput(ctx).publicationId() + " not found");
                    }
                })
                .step("Load the caller's block of the sponsor and the sponsor's ban", QueryEntities
                    .<ProcessContext>all(
                        block(ctx -> ctx.request().actorId(), ctx -> first(ctx, CARD)),
                        ban(ctx -> first(ctx, CARD).get(PublisherKind.ID_FIELD), SPONSOR_BAN)))
                .compute("Check the caller may open it", (metadata, ctx) -> checkOpening(ctx))
                .step("Take the conversation's turn", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbConversations.lockName(ctx.request().actorId(), openInput(ctx).publicationId())))
                .step("Load the conversation", QueryEntities.<ProcessContext>of(
                    QbConversations.defaultDataset(QbConversations.CONVERSATION), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.And(List.of(
                            new QueryPredicate.Eq("takerId", ctx.request().actorId()),
                            new QueryPredicate.Eq("publicationId", openInput(ctx).publicationId()))))
                        .limit(1).build(), CONVERSATION))
                .step("Load the caller's messages of the day", sentToday())
                .compute("Write", (metadata, ctx) -> open(ctx));
        });
    }

    static void checkOpening(ProcessContext ctx) {
        EntityInstance card = first(ctx, CARD);
        EntityInstance attempt = first(ctx, ATTEMPT);
        String refusal = ConversationRules.openRefusal(new ConversationRules.Opening(
            PublisherKind.sponsor(card.get(PublisherKind.KIND_FIELD)),
            attempt != null && attempt.get("submittedAt") != null,
            first(ctx, LINK) != null,
            ctx.request().actorId().equals(String.valueOf((Object) card.get(PublisherKind.ID_FIELD))),
            first(ctx, BLOCK_ROW) != null,
            BanRules.banned(attributes(first(ctx, SPONSOR_BAN)))));
        if (refusal != null) {
            throw refusal(refusal);
        }
    }

    static void open(ProcessContext ctx) {
        OpenInput input = openInput(ctx);
        EntityInstance card = first(ctx, CARD);
        EntityInstance existing = first(ctx, CONVERSATION);
        Instant now = ctx.opTime();
        Object conversationId;
        boolean created = existing == null;
        if (created) {
            EntityInstance attempt = first(ctx, ATTEMPT);
            Map<String, Object> values = new LinkedHashMap<>();
            values.put(PublisherKind.KIND_FIELD, card.get(PublisherKind.KIND_FIELD));
            values.put(PublisherKind.ID_FIELD, card.get(PublisherKind.ID_FIELD));
            values.put("takerId", ctx.request().actorId());
            values.put("publicationId", input.publicationId());
            values.put("attemptId", attempt != null && attempt.get("submittedAt") != null
                ? String.valueOf(attempt.id()) : null);
            values.put("status", QbConversations.OPEN);
            values.put("openedAt", now);
            values.put("lastMessageAt", now);
            values.put("lastSender", QbConversations.TAKER);
            values.put("takerReadAt", now);
            conversationId = ctx.changes().insert(QbConversations.CONVERSATION, values);
        } else {
            checkCap(ctx);
            conversationId = existing.id();
            Map<String, Object> values = new LinkedHashMap<>();
            if (!QbConversations.OPEN.equals(existing.get("status"))) {
                // D-Q82-5: the taker's message opens a closed conversation again (the sponsor's cannot).
                values.put("status", QbConversations.OPEN);
                values.put("closedAt", null);
                values.put("closedBy", null);
            }
            values.put("lastMessageAt", now);
            values.put("lastSender", QbConversations.TAKER);
            values.put("takerReadAt", now);
            ctx.changes().update(QbConversations.CONVERSATION, existing.id(), existing.version(), values);
        }
        Object messageId = insertMessage(ctx, conversationId, card.get(PublisherKind.KIND_FIELD),
            card.get(PublisherKind.ID_FIELD), QbConversations.TAKER, input.body());
        ctx.put(OUTPUT, new MessageOutput(String.valueOf(conversationId), String.valueOf(messageId), created));
    }

    // ---- send ------------------------------------------------------------------------------------------------------

    public static ProcessDefinition<SendInput, MessageOutput, ProcessContext> send() {
        return ProcessDefinition.define(SEND, 1, SendInput.class, MessageOutput.class, ProcessContext.class, pb -> {
            common(pb, "Writes into an open conversation of the caller's, as its taker or its sponsor.");
            pb.outputMapper(ctx -> ctx.get(OUTPUT, MessageOutput.class))
                .compute("Check the message", (metadata, ctx) -> checkBody(ctx.get(INPUT, SendInput.class).body()))
                .step("Load the parameters", LoadParams.<ProcessContext>of(ProcessContext::opTime, PARAMS,
                    QbParams.CONVERSATION_DAILY_CAP));
            lockAndLoad(pb, ctx -> ctx.get(INPUT, SendInput.class).conversationId());
            pb.step("Load the caller's messages of the day, the block and the other side's ban", QueryEntities
                    .<ProcessContext>all(
                        sentTodayQuery(),
                        block(ctx -> conversation(ctx).get("takerId"), ctx -> conversation(ctx)),
                        ban(ctx -> QbConversations.TAKER.equals(side(ctx))
                            ? conversation(ctx).get(PublisherKind.ID_FIELD) : conversation(ctx).get("takerId"),
                            OTHER_BAN)))
                .compute("Write", (metadata, ctx) -> send(ctx))
                .step("Tell the taker of the sponsor's message", PublishEvent.<ProcessContext>when(
                    ctx -> ctx.contains(EVENT), QbConversations.MESSAGE_EVENT, ctx -> ctx.get(EVENT, Map.class)));
        });
    }

    static void send(ProcessContext ctx) {
        SendInput input = ctx.get(INPUT, SendInput.class);
        EntityInstance conversation = conversation(ctx);
        String side = side(ctx);
        String refusal = ConversationRules.sendRefusal((String) conversation.get("status"),
            first(ctx, BLOCK_ROW) != null, BanRules.banned(attributes(first(ctx, OTHER_BAN))));
        if (refusal != null) {
            throw refusal(refusal);
        }
        checkCap(ctx);
        Instant now = ctx.opTime();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("lastMessageAt", now);
        values.put("lastSender", side);
        values.put(QbConversations.TAKER.equals(side) ? "takerReadAt" : "sponsorReadAt", now);
        ctx.changes().update(QbConversations.CONVERSATION, conversation.id(), conversation.version(), values);
        Object messageId = insertMessage(ctx, conversation.id(), conversation.get(PublisherKind.KIND_FIELD),
            conversation.get(PublisherKind.ID_FIELD), side, input.body());
        if (QbConversations.SPONSOR.equals(side)) {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("conversationId", String.valueOf(conversation.id()));
            event.put("publicationId", String.valueOf((Object) conversation.get("publicationId")));
            event.put("messageId", String.valueOf(messageId));
            ctx.put(EVENT, event);
        }
        ctx.put(OUTPUT, new MessageOutput(String.valueOf(conversation.id()), String.valueOf(messageId), false));
    }

    // ---- close and read --------------------------------------------------------------------------------------------

    public static ProcessDefinition<ConversationRef, StatusOutput, ProcessContext> close() {
        return ProcessDefinition.define(CLOSE, 1, ConversationRef.class, StatusOutput.class, ProcessContext.class,
            pb -> {
                common(pb, "Closes a conversation of the caller's, as its taker or its sponsor.");
                pb.outputMapper(ctx -> ctx.get(OUTPUT, StatusOutput.class));
                lockAndLoad(pb, ctx -> ctx.get(INPUT, ConversationRef.class).conversationId());
                pb.compute("Close it", (metadata, ctx) -> {
                    EntityInstance conversation = conversation(ctx);
                    if (QbConversations.OPEN.equals(conversation.get("status"))) {
                        closeAt(ctx, conversation, side(ctx));
                    }
                    ctx.put(OUTPUT, new StatusOutput(String.valueOf(conversation.id()), QbConversations.CLOSED));
                });
            });
    }

    public static ProcessDefinition<ConversationRef, StatusOutput, ProcessContext> read() {
        return ProcessDefinition.define(READ, 1, ConversationRef.class, StatusOutput.class, ProcessContext.class,
            pb -> {
                common(pb, "Marks a conversation of the caller's read up to now.");
                pb.outputMapper(ctx -> ctx.get(OUTPUT, StatusOutput.class));
                lockAndLoad(pb, ctx -> ctx.get(INPUT, ConversationRef.class).conversationId());
                pb.compute("Mark it read", (metadata, ctx) -> {
                    EntityInstance conversation = conversation(ctx);
                    String field = QbConversations.TAKER.equals(side(ctx)) ? "takerReadAt" : "sponsorReadAt";
                    // Only when there is something new: opening a read conversation again writes nothing.
                    if (ConversationRules.unread(side(ctx), (String) conversation.get("lastSender"),
                        instant(conversation.get("lastMessageAt")), instant(conversation.get(field)))) {
                        ctx.changes().update(QbConversations.CONVERSATION, conversation.id(), conversation.version(),
                            Map.of(field, ctx.opTime()));
                    }
                    ctx.put(OUTPUT, new StatusOutput(String.valueOf(conversation.id()),
                        (String) conversation.get("status")));
                });
            });
    }

    // ---- block -----------------------------------------------------------------------------------------------------

    public static ProcessDefinition<ConversationRef, BlockOutput, ProcessContext> block() {
        return ProcessDefinition.define(BLOCK, 1, ConversationRef.class, BlockOutput.class, ProcessContext.class,
            pb -> {
                common(pb, "Blocks the sponsor of a conversation of the caller's: closes every conversation with "
                    + "them; none opens and none takes a message until the block is removed.");
                pb.outputMapper(ctx -> ctx.get(OUTPUT, BlockOutput.class))
                    .step("Find the conversation", find(ctx -> ctx.get(INPUT, ConversationRef.class)
                        .conversationId()))
                    .compute("Only the taker blocks", (metadata, ctx) -> {
                        EntityInstance found = first(ctx, FOUND);
                        if (found == null || !QbConversations.TAKER.equals(ConversationRules.sideOf(
                            ctx.request().actorId(), found.get("takerId"), found.get(PublisherKind.KIND_FIELD),
                            found.get(PublisherKind.ID_FIELD)))) {
                            throw notFound(ctx.get(INPUT, ConversationRef.class).conversationId());
                        }
                    })
                    .step("Take the caller's account turn", HoldLock.<ProcessContext>exclusive(
                        ctx -> "qb.account:" + ctx.request().actorId()))
                    .step("Load the block and the conversations with the sponsor", QueryEntities.<ProcessContext>all(
                        block(ctx -> ctx.request().actorId(), ctx -> first(ctx, FOUND)),
                        QueryEntities.query(QbConversations.defaultDataset(QbConversations.CONVERSATION),
                            ctx -> EntityQuery.builder().where(new QueryPredicate.And(List.of(
                                new QueryPredicate.Eq("takerId", ctx.request().actorId()),
                                new QueryPredicate.Eq(PublisherKind.KIND_FIELD,
                                    first(ctx, FOUND).get(PublisherKind.KIND_FIELD)),
                                new QueryPredicate.Eq(PublisherKind.ID_FIELD,
                                    first(ctx, FOUND).get(PublisherKind.ID_FIELD)),
                                new QueryPredicate.Eq("status", QbConversations.OPEN))))
                                .limit(MAX_PER_SPONSOR).build(), THEIRS)))
                    .compute("Block", (metadata, ctx) -> block(ctx));
            });
    }

    static void block(ProcessContext ctx) {
        EntityInstance found = first(ctx, FOUND);
        EntityInstance existing = first(ctx, BLOCK_ROW);
        Object blockId;
        if (existing != null) {
            blockId = existing.id();
        } else {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("takerId", ctx.request().actorId());
            values.put(PublisherKind.KIND_FIELD, found.get(PublisherKind.KIND_FIELD));
            values.put(PublisherKind.ID_FIELD, found.get(PublisherKind.ID_FIELD));
            values.put("createdAt", ctx.opTime());
            blockId = ctx.changes().insert(QbConversations.BLOCK, values);
        }
        List<EntityInstance> open = list(ctx, THEIRS);
        for (EntityInstance conversation : open) {
            closeAt(ctx, conversation, QbConversations.TAKER);
        }
        ctx.put(OUTPUT, new BlockOutput(String.valueOf(blockId), open.size()));
    }

    public static ProcessDefinition<UnblockInput, BlockOutput, ProcessContext> unblock() {
        return ProcessDefinition.define(UNBLOCK, 1, UnblockInput.class, BlockOutput.class, ProcessContext.class,
            pb -> {
                common(pb, "Removes a block of the caller's: the sponsor's conversations take messages again (the "
                    + "closed ones once the caller opens them again).");
                pb.outputMapper(ctx -> ctx.get(OUTPUT, BlockOutput.class))
                    .step("Take the caller's account turn", HoldLock.<ProcessContext>exclusive(
                        ctx -> "qb.account:" + ctx.request().actorId()))
                    .step("Load the block", QueryEntities.<ProcessContext>of(
                        QbConversations.defaultDataset(QbConversations.BLOCK), ctx -> EntityQuery.builder()
                            .where(new QueryPredicate.In("blockId", List.of(ctx.get(INPUT, UnblockInput.class)
                                .blockId()))).limit(1).build(), BLOCK_ROW))
                    .compute("Remove it", (metadata, ctx) -> {
                        EntityInstance block = first(ctx, BLOCK_ROW);
                        if (block == null || !ctx.request().actorId().equals(String.valueOf((Object) block.get(
                            "takerId")))) {
                            throw new EntityNotFoundException(QbConversations.BLOCK + " "
                                + ctx.get(INPUT, UnblockInput.class).blockId() + " not found");
                        }
                        ctx.changes().delete(QbConversations.BLOCK, block.id(), block.version());
                        ctx.put(OUTPUT, new BlockOutput(null, 0));
                    });
            });
    }

    // ---- expire ----------------------------------------------------------------------------------------------------

    public static ProcessDefinition<ConversationRef, StatusOutput, ProcessContext> expire() {
        return ProcessDefinition.define(EXPIRE, 1, ConversationRef.class, StatusOutput.class, ProcessContext.class,
            pb -> pb
                .description("Closes a conversation without a message for qb.conversation.close-days days (the item "
                    + "of the job " + QbConversations.EXPIRE_JOB + ").")
                .permissions(QbPermissions.CONVERSATION_SYSTEM)
                .internal()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(ctx -> ctx.contains(OUTPUT) ? ctx.get(OUTPUT, StatusOutput.class) : new StatusOutput(
                    ctx.get(INPUT, ConversationRef.class).conversationId(), null))
                .step("Load the parameters", LoadParams.<ProcessContext>of(ProcessContext::opTime, PARAMS,
                    QbParams.CONVERSATION_CLOSE_DAYS))
                .step("Find the conversation", find(ctx -> ctx.get(INPUT, ConversationRef.class).conversationId()))
                .step("Take the conversation's turn", HoldLock.<ProcessContext>exclusive(ctx -> first(ctx, FOUND)
                    == null ? QbConversations.lockName("none", ctx.get(INPUT, ConversationRef.class).conversationId())
                    : lockOf(first(ctx, FOUND))))
                .step("Load it again", QueryEntities.<ProcessContext>of(
                    QbConversations.defaultDataset(QbConversations.CONVERSATION), ctx -> byId(ctx.get(INPUT,
                        ConversationRef.class).conversationId()), CONVERSATION))
                .compute("Close it when still due", (metadata, ctx) -> {
                    EntityInstance conversation = first(ctx, CONVERSATION);
                    int days = QbParams.days(ctx.get(PARAMS, ParamValues.class), QbParams.CONVERSATION_CLOSE_DAYS);
                    // Already closed, or written to since the job chose it: nothing.
                    if (conversation != null && ConversationRules.due((String) conversation.get("status"),
                        instant(conversation.get("lastMessageAt")), ctx.opTime(), days)) {
                        closeAt(ctx, conversation, QbConversations.SYSTEM);
                        ctx.put(OUTPUT, new StatusOutput(String.valueOf(conversation.id()), QbConversations.CLOSED));
                    }
                }));
    }

    // ---- shared ----------------------------------------------------------------------------------------------------

    /**
     * Finds the conversation outside its lock (its taker and publication never change), refuses one not the caller's,
     * takes its lock and reads it again.
     */
    private static <I, O> void lockAndLoad(ProcessDefinitionBuilder<I, O, ProcessContext> pb,
        Function<ProcessContext, String> conversationId) {
        pb.step("Find the conversation", find(conversationId))
            .compute("Refuse a conversation not the caller's", (metadata, ctx) -> {
                if (sideOf(ctx, first(ctx, FOUND)) == null) {
                    throw notFound(conversationId.apply(ctx));
                }
            })
            .step("Take the conversation's turn", HoldLock.<ProcessContext>exclusive(ctx -> lockOf(first(ctx, FOUND))))
            .step("Load it again", QueryEntities.<ProcessContext>of(
                QbConversations.defaultDataset(QbConversations.CONVERSATION), ctx -> byId(conversationId.apply(ctx)),
                CONVERSATION));
    }

    private static StepSpec<QueryEntities.Metadata<ProcessContext>, ProcessContext> find(
        Function<ProcessContext, String> conversationId) {
        return QueryEntities.<ProcessContext>of(QbConversations.defaultDataset(QbConversations.CONVERSATION),
            ctx -> byId(conversationId.apply(ctx)), FOUND);
    }

    static String lockOf(EntityInstance conversation) {
        return QbConversations.lockName(conversation.get("takerId"), conversation.get("publicationId"));
    }

    private static EntityQuery byId(String id) {
        return EntityQuery.builder().where(new QueryPredicate.In("conversationId", id == null ? List.of()
            : List.of(id))).limit(1).build();
    }

    /** The caller's messages in the conversation of the last 24 hours, for the daily cap (none before it exists). */
    private static StepSpec<QueryEntities.AllMetadata<ProcessContext>, ProcessContext> sentToday() {
        return QueryEntities.<ProcessContext>all(sentTodayQuery());
    }

    private static QueryEntities.Metadata<ProcessContext> sentTodayQuery() {
        return QueryEntities.query(QbConversations.defaultDataset(QbConversations.MESSAGE), ctx -> {
            EntityInstance conversation = first(ctx, CONVERSATION);
            int cap = QbParams.count(ctx.get(PARAMS, ParamValues.class), QbParams.CONVERSATION_DAILY_CAP);
            return EntityQuery.builder().where(new QueryPredicate.And(List.of(
                new QueryPredicate.In("conversationId", conversation == null ? List.of()
                    : List.of(String.valueOf(conversation.id()))),
                new QueryPredicate.Eq("senderUserId", ctx.request().actorId()),
                new QueryPredicate.Gte("createdAt", ctx.opTime().minus(ConversationRules.DAY)))))
                .limit(cap + 1).build();
        }, SENT_TODAY);
    }

    /** The taker's block of the conversation's or the card's sponsor (at most one). */
    private static QueryEntities.Metadata<ProcessContext> block(Function<ProcessContext, Object> takerId,
        Function<ProcessContext, EntityInstance> publisherOf) {
        return QueryEntities.query(QbConversations.defaultDataset(QbConversations.BLOCK), ctx -> {
            EntityInstance publisher = publisherOf.apply(ctx);
            return EntityQuery.builder().where(new QueryPredicate.And(List.of(
                new QueryPredicate.Eq("takerId", String.valueOf(takerId.apply(ctx))),
                new QueryPredicate.Eq(PublisherKind.KIND_FIELD, publisher.get(PublisherKind.KIND_FIELD)),
                new QueryPredicate.Eq(PublisherKind.ID_FIELD, publisher.get(PublisherKind.ID_FIELD)))))
                .limit(1).build();
        }, BLOCK_ROW);
    }

    private static QueryEntities.Metadata<ProcessContext> ban(Function<ProcessContext, Object> userId, String key) {
        return QueryEntities.query(QbAccounts.defaultDataset(QbAccounts.BAN), ctx -> EntityQuery.builder()
            .where(new QueryPredicate.In("userId", AccountEditing.uuids(userId.apply(ctx)))).limit(1).build(), key);
    }

    private static void checkBody(String body) {
        List<Violation> problems = ConversationRules.bodyProblems(body);
        if (!problems.isEmpty()) {
            throw new BusinessRuleViolationException(problems);
        }
    }

    private static void checkCap(ProcessContext ctx) {
        int cap = QbParams.count(ctx.get(PARAMS, ParamValues.class), QbParams.CONVERSATION_DAILY_CAP);
        List<Instant> sent = new ArrayList<>();
        for (EntityInstance message : list(ctx, SENT_TODAY)) {
            sent.add(instant(message.get("createdAt")));
        }
        if (ConversationRules.overDailyCap(sent, ctx.opTime(), cap)) {
            throw new BusinessRuleViolationException(new Violation("body", ConversationCodes.DAILY_CAP,
                "You sent " + cap + " messages in this conversation in a day", Map.of("limit", cap)));
        }
    }

    private static Object insertMessage(ProcessContext ctx, Object conversationId, Object publisherKind,
        Object publisherId, String side, String body) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("conversationId", String.valueOf(conversationId));
        values.put(PublisherKind.KIND_FIELD, publisherKind);
        values.put(PublisherKind.ID_FIELD, publisherId);
        values.put("sender", side);
        values.put("senderUserId", ctx.request().actorId());
        values.put("body", ConversationRules.stored(body));
        values.put("createdAt", ctx.opTime());
        return ctx.changes().insert(QbConversations.MESSAGE, values);
    }

    private static void closeAt(ProcessContext ctx, EntityInstance conversation, String by) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("status", QbConversations.CLOSED);
        values.put("closedAt", ctx.opTime());
        values.put("closedBy", by);
        ctx.changes().update(QbConversations.CONVERSATION, conversation.id(), conversation.version(), values);
    }

    private static String sideOf(ProcessContext ctx, EntityInstance conversation) {
        return conversation == null ? null : ConversationRules.sideOf(ctx.request().actorId(),
            conversation.get("takerId"), conversation.get(PublisherKind.KIND_FIELD),
            conversation.get(PublisherKind.ID_FIELD));
    }

    /** The caller's side of the conversation read under its lock. */
    private static String side(ProcessContext ctx) {
        return sideOf(ctx, conversation(ctx));
    }

    private static EntityInstance conversation(ProcessContext ctx) {
        EntityInstance conversation = first(ctx, CONVERSATION);
        if (conversation == null) {
            throw notFound(null);
        }
        return conversation;
    }

    private static EntityNotFoundException notFound(Object conversationId) {
        return new EntityNotFoundException(QbConversations.CONVERSATION + " " + conversationId + " not found");
    }

    private static BusinessRuleViolationException refusal(String code) {
        String message = switch (code) {
            case ConversationCodes.NOT_ELIGIBLE -> "Only a taker who completed the quiz, or referred people to it, "
                + "writes to its sponsor";
            case ConversationCodes.BLOCKED -> "The sponsor is blocked";
            case ConversationCodes.CLOSED -> "The conversation is closed";
            default -> "The conversation takes no message now";
        };
        return new BusinessRuleViolationException(new Violation(null, code, message, Map.of()));
    }

    private static OpenInput openInput(ProcessContext ctx) {
        return ctx.get(INPUT, OpenInput.class);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> attributes(EntityInstance row) {
        return row == null ? null : row.attributes();
    }

    @SuppressWarnings("unchecked")
    static List<EntityInstance> list(ProcessContext ctx, String key) {
        Object value = ctx.contains(key) ? ctx.get(key) : null;
        return value == null ? List.of() : (List<EntityInstance>) value;
    }

    static EntityInstance first(ProcessContext ctx, String key) {
        List<EntityInstance> found = list(ctx, key);
        return found.isEmpty() ? null : found.getFirst();
    }

    static Instant instant(Object value) {
        return value == null ? null : value instanceof OffsetDateTime time ? time.toInstant()
            : value instanceof Instant time ? time : Instant.parse(value.toString());
    }
}
