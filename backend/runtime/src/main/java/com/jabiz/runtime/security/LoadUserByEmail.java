package com.jabiz.runtime.security;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.StepSpec;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.process.steps.QueryEntities;

import java.util.List;
import java.util.function.Function;

/**
 * Loads the user whose e-mail address is the given one regardless of case (decision D36: addresses are unique
 * regardless of case), through the user dataset, verified or not: a platform step of the account processes.
 * <pre>{@code
 * .step("Find the address", LoadUserByEmail.into(ctx -> input(ctx).email(), "users"))
 * }</pre>
 * The context receives a {@code List<EntityInstance>}: empty when nobody has the address (or none is given), at most
 * two (a second would be a broken invariant, which {@link #only} treats as nobody).
 */
public final class LoadUserByEmail {

    private LoadUserByEmail() {}

    public static <C extends ProcessContext> StepSpec<QueryEntities.Metadata<C>, C> into(Function<C, String> email,
        String targetKey) {
        return QueryEntities.of(SecurityEntities.USER_DATASET, ctx -> query(email.apply(ctx)), targetKey);
    }

    /** The users with the address regardless of case; none for no address. */
    static EntityQuery query(String email) {
        QueryPredicate where = email == null || email.isBlank()
            ? new QueryPredicate.In("userId", List.of())
            : new QueryPredicate.EqIgnoreCase("email", email.strip());
        return EntityQuery.builder().where(where).limit(2).build();
    }

    /** The one user found, or null for none (or, against the invariant, more than one). */
    @SuppressWarnings("unchecked")
    public static com.jabiz.runtime.EntityInstance only(ProcessContext ctx, String key) {
        List<com.jabiz.runtime.EntityInstance> users = (List<com.jabiz.runtime.EntityInstance>) ctx.get(key);
        return users == null || users.size() != 1 ? null : users.getFirst();
    }
}
