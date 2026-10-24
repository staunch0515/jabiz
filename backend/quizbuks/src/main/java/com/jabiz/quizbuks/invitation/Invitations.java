package com.jabiz.quizbuks.invitation;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.security.SecurityEntities;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * How the answering processes find a taker's invitation (Q25): by the account that claimed it, or by the address the
 * account verified (the mailed link signs in with it, platform decision D54; an account registered with the same
 * address is invited too). The steps read nothing for a public publication ({@code publicationId} null: an empty
 * {@code In}, no statement), so the statement budgets of public play stand.
 */
public final class Invitations {

    static final String ACCOUNT = "invitation.account";
    static final String FOUND = "invitation.found";

    /**
     * Loads the caller's account and the invitation of the publication {@code publicationId} names that is theirs:
     * claimed by their account, or addressed to their verified address and not claimed by another.
     */
    public static <I, O> void loadForTaker(ProcessDefinitionBuilder<I, O, ProcessContext> pb,
        Function<ProcessContext, Object> publicationId) {
        pb.step("Load the caller's account", QueryEntities.<ProcessContext>of(SecurityEntities.USER_DATASET,
                ctx -> EntityQuery.builder().where(new QueryPredicate.In("userId",
                    publicationId.apply(ctx) == null ? List.of() : uuids(ctx.request().actorId()))).limit(1).build(),
                ACCOUNT))
            .step("Load the caller's invitation", QueryEntities.<ProcessContext>of(QbInvitations.DEFAULT_DATASET,
                ctx -> {
                    Object publication = publicationId.apply(ctx);
                    if (publication == null) {
                        return EntityQuery.builder().where(new QueryPredicate.In("publicationId", List.of())).limit(1)
                            .build();
                    }
                    List<QueryPredicate> mine = new ArrayList<>();
                    mine.add(new QueryPredicate.Eq("userId", ctx.request().actorId()));
                    String address = verifiedAddress(ctx);
                    if (address != null) {
                        mine.add(new QueryPredicate.Eq("email", address));
                    }
                    return EntityQuery.builder().where(new QueryPredicate.And(List.of(
                        new QueryPredicate.Eq("publicationId", publication), new QueryPredicate.Or(mine))))
                        .limit(2).build();
                }, FOUND));
    }

    /** The caller's verified address in lower case, or null without one. */
    static String verifiedAddress(ProcessContext ctx) {
        EntityInstance account = first(ctx, ACCOUNT);
        Object address = account == null ? null : account.get("verifiedEmail");
        return address == null ? null : address.toString().toLowerCase(Locale.ROOT);
    }

    /**
     * The caller's invitation among those loaded, or null: one claimed by their account first, else one addressed to
     * their verified address that no other account claimed.
     */
    public static EntityInstance mine(ProcessContext ctx) {
        String actor = ctx.request().actorId();
        EntityInstance byAddress = null;
        for (EntityInstance invitation : list(ctx, FOUND)) {
            Object user = invitation.get("userId");
            if (actor.equals(user)) {
                return invitation;
            }
            if (user == null) {
                byAddress = invitation;
            }
        }
        return byAddress;
    }

    /** The values that claim {@code invitation} for the caller, when it is not yet claimed; empty otherwise. */
    public static Map<String, Object> claim(ProcessContext ctx, EntityInstance invitation) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (invitation.get("userId") == null) {
            values.put("userId", ctx.request().actorId());
        }
        return values;
    }

    static List<Object> uuids(Object id) {
        List<Object> ids = new ArrayList<>();
        try {
            ids.add(UUID.fromString(String.valueOf(id)));
        } catch (IllegalArgumentException notAUuid) {
            // An actor outside the user table (a system identity): no account, no invitation by address.
        }
        return ids;
    }

    @SuppressWarnings("unchecked")
    static List<EntityInstance> list(ProcessContext ctx, String key) {
        List<EntityInstance> found = (List<EntityInstance>) ctx.get(key);
        return found == null ? List.of() : found;
    }

    static EntityInstance first(ProcessContext ctx, String key) {
        List<EntityInstance> found = list(ctx, key);
        return found.isEmpty() ? null : found.getFirst();
    }

    private Invitations() {}
}
