package com.jabiz.quizbuks.benefit;

import com.jabiz.entity.Violation;
import com.jabiz.param.ParamValues;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.conversation.ConversationRules;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.LoadParams;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static com.jabiz.quizbuks.benefit.BenefitSteps.INPUT;
import static com.jabiz.quizbuks.benefit.BenefitSteps.OUTPUT;
import static com.jabiz.quizbuks.benefit.BenefitSteps.first;
import static com.jabiz.quizbuks.benefit.BenefitSteps.list;

/**
 * Conversations between a sponsor and a benefit's provider (Q89a, D-Q89-4): the sponsor holding
 * {@code BENEFIT_SPONSOR} writes about a listed benefit of another sponsor ({@value #OPEN}: the first message opens
 * the conversation, a later one goes into it); either side writes ({@value #SEND}) and marks it read ({@value #READ}).
 * Plain text up to {@value BenefitRules#BODY_LENGTH} characters, contact and payment details allowed (the sponsors
 * pay each other outside the platform); {@code qb.conversation.daily-cap} messages a side a day, as in the takers'
 * conversations. One lock per conversation, by its benefit and sponsor; no other lock is held with it.
 */
public final class BenefitConversationProcesses {

    public static final String OPEN = "QB_BENEFIT_CONVERSATION_OPEN";
    public static final String SEND = "QB_BENEFIT_CONVERSATION_SEND";
    public static final String READ = "QB_BENEFIT_CONVERSATION_READ";

    static final String PARAMS = "params";
    static final String OFFER = "offer";
    static final String FOUND = "found";
    static final String CONVERSATION = "conversation";
    static final String SENT_TODAY = "sentToday";

    /** @param offerId the benefit written about; @param body the message */
    public record OpenInput(@NotBlank @Size(max = 36) String offerId, @NotNull @Size(max = 8_000) String body) {}

    /** @param conversationId the conversation written to; @param body the message */
    public record SendInput(@NotBlank @Size(max = 36) String conversationId,
        @NotNull @Size(max = 8_000) String body) {}

    public record ConversationRef(@NotBlank @Size(max = 36) String conversationId) {}

    /** @param created whether the conversation began with this message */
    public record MessageOutput(String conversationId, String messageId, boolean created) {}

    public record ReadOutput(String conversationId) {}

    private BenefitConversationProcesses() {}

    // ---- open ------------------------------------------------------------------------------------------------------

    public static ProcessDefinition<OpenInput, MessageOutput, ProcessContext> open() {
        return ProcessDefinition.define(OPEN, 1, OpenInput.class, MessageOutput.class, ProcessContext.class, pb -> {
            BenefitSteps.common(pb, "Writes to the provider of a listed benefit about buying it: opens the "
                + "conversation, or writes into it.");
            pb.outputMapper(ctx -> ctx.get(OUTPUT, MessageOutput.class))
                .compute("Check the message", (metadata, ctx) -> checkBody(openInput(ctx).body()))
                .step("Load the parameters", LoadParams.<ProcessContext>of(ProcessContext::opTime, PARAMS,
                    QbParams.CONVERSATION_DAILY_CAP))
                .step("Load the benefit", QueryEntities.<ProcessContext>of(QbBenefits.defaultDataset(QbBenefits.OFFER),
                    ctx -> BenefitSteps.byId("offerId", openInput(ctx).offerId()), OFFER))
                .compute("Refuse an unknown benefit or the caller's own", (metadata, ctx) -> {
                    EntityInstance offer = first(ctx, OFFER);
                    if (offer == null) {
                        throw new EntityNotFoundException(QbBenefits.OFFER + " " + openInput(ctx).offerId());
                    }
                    if (ctx.request().actorId().equals(offer.get("providerId"))) {
                        throw BenefitSteps.refusal(BenefitCodes.OWN_OFFER, "This is your own benefit", Map.of());
                    }
                })
                .step("Load the provider's ban", QueryEntities.<ProcessContext>all(
                    BenefitSteps.ban(ctx -> first(ctx, OFFER).get("providerId"))))
                .compute("Refuse a suspended provider", (metadata, ctx) -> BenefitSteps.refuseBannedOther(ctx));
            BenefitSteps.loadGate(pb, QbParams.TREE_GATE_BENEFIT_SPONSOR);
            pb.step("Take the conversation's turn", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbBenefits.conversationLock(openInput(ctx).offerId(), ctx.request().actorId())))
                .step("Load the conversation", QueryEntities.<ProcessContext>of(
                    QbBenefits.defaultDataset(QbBenefits.CONVERSATION), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.And(List.of(
                            new QueryPredicate.Eq("offerId", openInput(ctx).offerId()),
                            new QueryPredicate.Eq("sponsorId", ctx.request().actorId()))))
                        .limit(1).build(), CONVERSATION))
                .step("Load the caller's messages of the day", QueryEntities.<ProcessContext>all(sentToday()))
                .compute("Write", (metadata, ctx) -> open(ctx));
        });
    }

    static void open(ProcessContext ctx) {
        EntityInstance offer = first(ctx, OFFER);
        EntityInstance existing = first(ctx, CONVERSATION);
        Instant now = ctx.opTime();
        Object conversationId;
        boolean created = existing == null;
        if (created) {
            // Asking about a benefit begins with the certification and a benefit the catalog shows (D-Q89-3).
            BenefitSteps.requireGate(ctx, QbParams.TREE_GATE_BENEFIT_SPONSOR);
            if (!QbBenefits.LISTED.equals(offer.get("status"))) {
                throw BenefitSteps.refusal(BenefitCodes.OFFER_UNAVAILABLE, "The benefit is not offered now", Map.of());
            }
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("offerId", String.valueOf(offer.id()));
            values.put("sponsorId", ctx.request().actorId());
            values.put("providerId", offer.get("providerId"));
            values.put("openedAt", now);
            values.put("lastMessageAt", now);
            values.put("lastSender", QbBenefits.SPONSOR);
            values.put("sponsorReadAt", now);
            conversationId = ctx.changes().insert(QbBenefits.CONVERSATION, values);
        } else {
            checkCap(ctx);
            conversationId = existing.id();
            ctx.changes().update(QbBenefits.CONVERSATION, existing.id(), existing.version(), Map.of(
                "lastMessageAt", now, "lastSender", QbBenefits.SPONSOR, "sponsorReadAt", now));
        }
        Object messageId = insertMessage(ctx, conversationId, ctx.request().actorId(), offer.get("providerId"),
            QbBenefits.SPONSOR, openInput(ctx).body());
        ctx.put(OUTPUT, new MessageOutput(String.valueOf(conversationId), String.valueOf(messageId), created));
    }

    // ---- send and read ---------------------------------------------------------------------------------------------

    public static ProcessDefinition<SendInput, MessageOutput, ProcessContext> send() {
        return ProcessDefinition.define(SEND, 1, SendInput.class, MessageOutput.class, ProcessContext.class, pb -> {
            BenefitSteps.common(pb, "Writes into a conversation about a benefit, as its sponsor or its provider.");
            pb.outputMapper(ctx -> ctx.get(OUTPUT, MessageOutput.class))
                .compute("Check the message", (metadata, ctx) -> checkBody(ctx.get(INPUT, SendInput.class).body()))
                .step("Load the parameters", LoadParams.<ProcessContext>of(ProcessContext::opTime, PARAMS,
                    QbParams.CONVERSATION_DAILY_CAP));
            lockAndLoad(pb, ctx -> ctx.get(INPUT, SendInput.class).conversationId());
            pb.step("Load the caller's messages of the day and the other side's ban", QueryEntities.<ProcessContext>all(
                    sentToday(), BenefitSteps.ban(ctx -> QbBenefits.SPONSOR.equals(side(ctx))
                        ? conversation(ctx).get("providerId") : conversation(ctx).get("sponsorId"))))
                .compute("Write", (metadata, ctx) -> {
                    BenefitSteps.refuseBannedOther(ctx);
                    checkCap(ctx);
                    EntityInstance conversation = conversation(ctx);
                    String side = side(ctx);
                    Instant now = ctx.opTime();
                    ctx.changes().update(QbBenefits.CONVERSATION, conversation.id(), conversation.version(), Map.of(
                        "lastMessageAt", now, "lastSender", side, readField(side), now));
                    Object messageId = insertMessage(ctx, conversation.id(), conversation.get("sponsorId"),
                        conversation.get("providerId"), side, ctx.get(INPUT, SendInput.class).body());
                    ctx.put(OUTPUT, new MessageOutput(String.valueOf(conversation.id()), String.valueOf(messageId),
                        false));
                });
        });
    }

    public static ProcessDefinition<ConversationRef, ReadOutput, ProcessContext> read() {
        return ProcessDefinition.define(READ, 1, ConversationRef.class, ReadOutput.class, ProcessContext.class, pb -> {
            BenefitSteps.common(pb, "Marks a conversation about a benefit read up to now.");
            pb.outputMapper(ctx -> ctx.get(OUTPUT, ReadOutput.class));
            lockAndLoad(pb, ctx -> ctx.get(INPUT, ConversationRef.class).conversationId());
            pb.compute("Mark it read", (metadata, ctx) -> {
                EntityInstance conversation = conversation(ctx);
                String side = side(ctx);
                // Only when there is something new: opening a read conversation again writes nothing.
                if (BenefitRules.unread(side, conversation.get("lastSender"),
                    BenefitSteps.instant(conversation.get("lastMessageAt")),
                    BenefitSteps.instant(conversation.get(readField(side))))) {
                    ctx.changes().update(QbBenefits.CONVERSATION, conversation.id(), conversation.version(),
                        Map.of(readField(side), ctx.opTime()));
                }
                ctx.put(OUTPUT, new ReadOutput(String.valueOf(conversation.id())));
            });
        });
    }

    // ---- shared ----------------------------------------------------------------------------------------------------

    /** Finds the conversation, refuses one not the caller's (404), takes its turn and reads it again. */
    private static <I, O> void lockAndLoad(ProcessDefinitionBuilder<I, O, ProcessContext> pb,
        Function<ProcessContext, String> conversationId) {
        pb.step("Find the conversation", QueryEntities.<ProcessContext>of(
                QbBenefits.defaultDataset(QbBenefits.CONVERSATION),
                ctx -> BenefitSteps.byId("conversationId", conversationId.apply(ctx)), FOUND))
            .compute("Refuse a conversation not the caller's", (metadata, ctx) -> {
                EntityInstance found = first(ctx, FOUND);
                if (found == null || BenefitRules.sideOf(ctx.request().actorId(), found.get("sponsorId"),
                    found.get("providerId")) == null) {
                    throw notFound(conversationId.apply(ctx));
                }
            })
            .step("Take the conversation's turn", HoldLock.<ProcessContext>exclusive(ctx -> QbBenefits.conversationLock(
                first(ctx, FOUND).get("offerId"), first(ctx, FOUND).get("sponsorId"))))
            .step("Load it again", QueryEntities.<ProcessContext>of(QbBenefits.defaultDataset(QbBenefits.CONVERSATION),
                ctx -> BenefitSteps.byId("conversationId", conversationId.apply(ctx)), CONVERSATION));
    }

    private static QueryEntities.Metadata<ProcessContext> sentToday() {
        return QueryEntities.query(QbBenefits.defaultDataset(QbBenefits.MESSAGE), ctx -> {
            EntityInstance conversation = first(ctx, CONVERSATION);
            int cap = QbParams.count(ctx.get(PARAMS, ParamValues.class), QbParams.CONVERSATION_DAILY_CAP);
            return EntityQuery.builder().where(new QueryPredicate.And(List.of(
                new QueryPredicate.In("conversationId", conversation == null ? List.of()
                    : List.of(String.valueOf(conversation.id()))),
                new QueryPredicate.Eq("senderUserId", ctx.request().actorId()),
                new QueryPredicate.Gte("createdAt", ctx.opTime().minus(Duration.ofDays(1))))))
                .limit(cap + 1).build();
        }, SENT_TODAY);
    }

    private static void checkBody(String body) {
        List<Violation> problems = BenefitRules.bodyProblems(body);
        if (!problems.isEmpty()) {
            throw new BusinessRuleViolationException(problems);
        }
    }

    private static void checkCap(ProcessContext ctx) {
        int cap = QbParams.count(ctx.get(PARAMS, ParamValues.class), QbParams.CONVERSATION_DAILY_CAP);
        List<Instant> sent = list(ctx, SENT_TODAY).stream()
            .map(message -> BenefitSteps.instant(message.get("createdAt"))).toList();
        if (ConversationRules.overDailyCap(sent, ctx.opTime(), cap)) {
            throw new BusinessRuleViolationException(new Violation("body", BenefitCodes.DAILY_CAP,
                "You sent " + cap + " messages in this conversation in a day", Map.of("limit", cap)));
        }
    }

    private static Object insertMessage(ProcessContext ctx, Object conversationId, Object sponsorId,
        Object providerId, String side, String body) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("conversationId", String.valueOf(conversationId));
        values.put("sponsorId", sponsorId);
        values.put("providerId", providerId);
        values.put("sender", side);
        values.put("senderUserId", ctx.request().actorId());
        values.put("body", body.strip());
        values.put("createdAt", ctx.opTime());
        return ctx.changes().insert(QbBenefits.MESSAGE, values);
    }

    private static String readField(String side) {
        return QbBenefits.SPONSOR.equals(side) ? "sponsorReadAt" : "providerReadAt";
    }

    private static String side(ProcessContext ctx) {
        EntityInstance conversation = conversation(ctx);
        return BenefitRules.sideOf(ctx.request().actorId(), conversation.get("sponsorId"),
            conversation.get("providerId"));
    }

    private static EntityInstance conversation(ProcessContext ctx) {
        EntityInstance conversation = first(ctx, CONVERSATION);
        if (conversation == null) {
            throw notFound(null);
        }
        return conversation;
    }

    private static EntityNotFoundException notFound(Object conversationId) {
        return new EntityNotFoundException(QbBenefits.CONVERSATION + " " + conversationId + " not found");
    }

    private static OpenInput openInput(ProcessContext ctx) {
        return ctx.get(INPUT, OpenInput.class);
    }
}
