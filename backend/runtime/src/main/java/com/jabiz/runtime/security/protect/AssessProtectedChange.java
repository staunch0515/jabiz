package com.jabiz.runtime.security.protect;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.StepSpec;
import com.jabiz.runtime.process.StepHandler;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Finds out what a change would do ({@link ProtectedGrants#assess}) and puts the {@link ProtectedGrants.Assessment}
 * into the context, with the names of its user and role ({@code key + ".names"}: user name, role code; empty when
 * there is none) for the approvers' task. A platform I/O step; nothing happens when the draft is null.
 */
@Component
public class AssessProtectedChange<C extends ProcessContext>
    implements StepHandler<AssessProtectedChange.Metadata<C>, C> {

    public record Metadata<C>(Function<C, ProtectedGrants.Draft> draft, String key) {
        public Metadata {
            Objects.requireNonNull(draft, "draft must not be null");
            Objects.requireNonNull(key, "key must not be null");
        }
    }

    public static <C extends ProcessContext> StepSpec<Metadata<C>, C> of(Function<C, ProtectedGrants.Draft> draft,
        String key) {
        return StepSpec.of(AssessProtectedChange.class, new Metadata<>(draft, key));
    }

    private final ProtectedGrants grants;

    public AssessProtectedChange(ProtectedGrants grants) {
        this.grants = grants;
    }

    @Override
    public Mono<Void> execute(Metadata<C> metadata, C ctx) {
        return Mono.defer(() -> {
            ProtectedGrants.Draft draft = metadata.draft().apply(ctx);
            if (draft == null) {
                return Mono.empty();
            }
            return grants.assess(draft, ctx.request().actorId(), ctx.processSeqId(), ctx.opTime())
                .zipWith(grants.names(draft.userId(), draft.roleId()))
                .doOnNext(found -> {
                    ctx.put(metadata.key(), found.getT1());
                    ctx.put(metadata.key() + ".names", List.copyOf(found.getT2()));
                })
                .then();
        });
    }
}
