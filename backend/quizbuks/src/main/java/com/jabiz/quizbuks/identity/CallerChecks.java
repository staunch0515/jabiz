package com.jabiz.quizbuks.identity;

import com.jabiz.entity.Violation;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.QueryEntities;

import java.util.Map;
import java.util.function.Function;

/**
 * Checks of the caller that QuizBuks writes share (docs/quizbuks/plans/Q2-accounts.md, D-Q2-4 and review S2). A ban
 * takes effect at sign-in and at the next refresh, but an access token issued before it works until it expires
 * (platform gap 3), so every QuizBuks write by a person reads their ban itself and refuses at once (422
 * {@code QB_USER_BANNED}), before anything else is read or written.
 */
public final class CallerChecks {

    static final String CALLER_BAN = "callerBan";
    static final String OTHER_BAN = "otherBan";

    /** Adds the steps that refuse a banned caller. Only platform users (UUIDs) can be banned. */
    public static <I, O> void refuseBanned(ProcessDefinitionBuilder<I, O, ProcessContext> pb) {
        pb.step("Load the caller's ban", QueryEntities.<ProcessContext>of(QbAccounts.defaultDataset(QbAccounts.BAN),
                ctx -> EntityQuery.builder().where(new QueryPredicate.In("userId",
                    AccountEditing.uuids(ctx.request().actorId()))).limit(1).build(), CALLER_BAN))
            .compute("Refuse a banned caller", (metadata, ctx) -> {
                EntityInstance ban = AccountEditing.first(ctx, CALLER_BAN);
                if (BanRules.banned(ban == null ? null : ban.attributes())) {
                    throw new BusinessRuleViolationException(banned());
                }
            });
    }

    /**
     * Adds the steps that refuse with {@code refusal} when the user {@code userId} names is banned: someone the caller
     * acts for (Q39: the sponsor whose prizes a staff member hands out). Nothing is refused for no user.
     */
    public static <I, O> void refuseBannedUser(ProcessDefinitionBuilder<I, O, ProcessContext> pb,
        Function<ProcessContext, Object> userId, Violation refusal) {
        pb.step("Load their ban", QueryEntities.<ProcessContext>of(QbAccounts.defaultDataset(QbAccounts.BAN),
                ctx -> EntityQuery.builder().where(new QueryPredicate.In("userId",
                    AccountEditing.uuids(userId.apply(ctx)))).limit(1).build(), OTHER_BAN))
            .compute("Refuse for a banned user", (metadata, ctx) -> {
                EntityInstance ban = AccountEditing.first(ctx, OTHER_BAN);
                if (BanRules.banned(ban == null ? null : ban.attributes())) {
                    throw new BusinessRuleViolationException(refusal);
                }
            });
    }

    /** The refusal of a banned user. */
    public static Violation banned() {
        return new Violation(null, AccountCodes.USER_BANNED, "The account is banned", Map.of());
    }

    private CallerChecks() {}
}
