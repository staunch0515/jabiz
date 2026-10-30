package com.jabiz.quizbuks.operator;

import com.jabiz.entity.Violation;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.identity.AccountEditing;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.RequireScope;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The caller's place on an operator's staff, which every act of an operator's member starts with: the membership is
 * held shared for the process ({@link QbOperators#memberLockName}), read, and the caller refused unless they are a
 * current member of an operator that runs. The sign-in guard keeps others out of the entry, but an access token
 * issued before a removal works until it expires, so each act reads the membership itself (as
 * {@code CallerChecks.refuseBanned} reads the ban).
 */
public final class OperatorChecks {

    /** The caller's operator, once {@link #member} passed. */
    public static final String OPERATOR_ID = "callerOperatorId";
    /** Whether the caller is their operator's lead. */
    public static final String LEAD = "callerLead";
    static final String MEMBERSHIP = "callerMembership";

    /** Adds the steps that hold and check the caller's membership; afterwards {@link #operatorId} names the operator. */
    public static <I, O> void member(ProcessDefinitionBuilder<I, O, ProcessContext> pb) {
        pb.step("Hold the caller's membership", HoldLock.<ProcessContext>shared(
                ctx -> QbOperators.memberLockName(ctx.request().actorId())))
            .step("Load the caller's membership", QueryEntities.<ProcessContext>of(
                QbOperators.defaultDataset(QbOperators.MEMBER), ctx -> current(ctx.request().actorId()), MEMBERSHIP))
            .compute("Check the caller's membership", (metadata, ctx) -> {
                EntityInstance member = first(ctx, MEMBERSHIP);
                if (member == null) {
                    throw new BusinessRuleViolationException(new Violation(null, OperatorCodes.NOT_MEMBER,
                        "The caller is no operator's member", Map.of()));
                }
                if (!Boolean.TRUE.equals(member.get("operatorActive"))) {
                    throw new BusinessRuleViolationException(new Violation(null, OperatorCodes.SUSPENDED,
                        "The caller's operator is suspended", Map.of()));
                }
                ctx.put(OPERATOR_ID, String.valueOf((Object) member.get("operatorId")));
                ctx.put(LEAD, Boolean.TRUE.equals(member.get("lead")));
            });
    }

    /** The caller's operator ({@link #member} passed). */
    public static String operatorId(ProcessContext ctx) {
        return ctx.get(OPERATOR_ID, String.class);
    }

    /** The current (not removed) membership of a user, one at most. */
    public static EntityQuery current(Object userId) {
        return EntityQuery.builder().where(new QueryPredicate.And(List.of(
            new QueryPredicate.In("userId", AccountEditing.uuids(userId)),
            new QueryPredicate.IsNull("removedAt")))).limit(1).build();
    }

    @SuppressWarnings("unchecked")
    /** Where {@link #ownSponsor} keeps the sponsor's assignment. */
    static final String SPONSOR_ASSIGNMENT = "ownSponsorAssignment";

    /**
     * Q59c-2 (platform decision D64): the sponsor a process acts on ({@code sponsorUserId}, read after the object was
     * loaded) must be the caller's operator's when an operator's member runs it; any other is not found (404).
     * Headquarters, and the processes of other entries, pass. A sponsor's publications, rewards and prizes are theirs.
     */
    public static <I, O> void ownSponsor(ProcessDefinitionBuilder<I, O, ProcessContext> pb,
        Function<ProcessContext, Object> sponsorUserId) {
        pb.step("Load the sponsor's operator", QueryEntities.<ProcessContext>of(
                QbOperators.defaultDataset(QbOperators.ASSIGNMENT), ctx -> {
                    Object sponsor = sponsorUserId.apply(ctx);
                    return EntityQuery.builder().where(new QueryPredicate.In("userId",
                        sponsor == null ? List.of() : List.of(String.valueOf(sponsor)))).limit(1).build();
                }, SPONSOR_ASSIGNMENT))
            .step("The sponsor is the operator's", RequireScope.<ProcessContext>of(QbOperators.SCOPE, ctx -> {
                EntityInstance assignment = first(ctx, SPONSOR_ASSIGNMENT);
                return assignment == null ? null : String.valueOf((Object) assignment.get("operatorId"));
            }, ctx -> "Sponsor " + sponsorUserId.apply(ctx)));
    }

    /**
     * The entry scope of the sponsor {@link #ownSponsor} loaded the operator of: their operator's, for the tasks made
     * about them (Q59c-2); null for a sponsor of headquarters'.
     */
    public static com.jabiz.context.EntryScope sponsorScope(ProcessContext ctx) {
        EntityInstance assignment = first(ctx, SPONSOR_ASSIGNMENT);
        return assignment == null ? null
            : new com.jabiz.context.EntryScope(QbOperators.SCOPE, String.valueOf((Object) assignment.get("operatorId")));
    }

    static List<EntityInstance> list(ProcessContext ctx, String key) {
        List<EntityInstance> found = (List<EntityInstance>) ctx.get(key);
        return found == null ? List.of() : found;
    }

    static EntityInstance first(ProcessContext ctx, String key) {
        List<EntityInstance> found = list(ctx, key);
        return found.isEmpty() ? null : found.getFirst();
    }

    private OperatorChecks() {}
}
