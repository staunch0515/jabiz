package com.jabiz.runtime.process.sponsor;

import com.jabiz.process.NoMetadata;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.DatasetEntityManager;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.process.StepHandler;
import com.jabiz.runtime.security.SecurityEntities;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * When no user has the name signed in with, the user whose verified e-mail address it is, regardless of case
 * (decision D36 item 4). Reads nothing when a user was found by name, or the name is no address; an unverified
 * address is like an unknown name.
 */
@Component
public class UserByEmailStep implements StepHandler<NoMetadata, LoginContext> {

    private final DatasetEntityManager entities;
    private final DatasetRegistry datasets;

    public UserByEmailStep(DatasetEntityManager entities, DatasetRegistry datasets) {
        this.entities = entities;
        this.datasets = datasets;
    }

    @Override
    public Mono<Void> execute(NoMetadata metadata, LoginContext ctx) {
        if (ctx.user().isPresent() || ctx.identifier() == null || !ctx.identifier().contains("@")) {
            return Mono.empty();
        }
        // Two at most: addresses are unique regardless of case, so a second match would be a broken invariant.
        EntityQuery query = EntityQuery.builder().where(new QueryPredicate.EqIgnoreCase("email", ctx.identifier()))
            .limit(2).build();
        return entities.queryAll(datasets.findById(SecurityEntities.USER_DATASET).orElseThrow(),
                SecurityEntities.SEC_USER, query)
            .collectList()
            .doOnNext(found -> {
                List<EntityInstance> verified = found.stream().filter(SecurityEntities::emailVerified).toList();
                if (verified.size() == 1) {
                    ctx.put(LoginContext.KEY_USERS, verified);
                }
            })
            .then();
    }
}
