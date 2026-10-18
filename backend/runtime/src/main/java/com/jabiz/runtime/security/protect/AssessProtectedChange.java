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

    /**
     * For the approvers (review H1, M3): the names of the users the change is for, and who last set their
     * credentials. Read only for a change that waits for approval.
     */
    public record Described(List<String> affected, List<String> credentials) {}

    private Mono<Described> describe(ProtectedGrants.Assessment assessment) {
        if (!assessment.needsApproval()) {
            return Mono.just(new Described(List.of(), List.of()));
        }
        return reactor.core.publisher.Flux.fromIterable(assessment.affected())
            .concatMap(user -> grants.userName(user).zipWith(grants.credentials(user)))
            .collectList()
            .map(found -> new Described(found.stream().map(f -> f.getT1()).toList(),
                found.stream().map(f -> f.getT1() + ": " + f.getT2().summary()).toList()));
    }

    @Override
    public Mono<Void> execute(Metadata<C> metadata, C ctx) {
        return Mono.defer(() -> {
            ProtectedGrants.Draft draft = metadata.draft().apply(ctx);
            if (draft == null) {
                return Mono.empty();
            }
            // Serialized with every other change of access (review M2): what is read stays true until this commits.
            return grants.lock().then(grants.assess(draft, ctx.request().actorId(), ctx.processSeqId(), ctx.opTime()))
                .zipWith(grants.names(draft.userId(), draft.roleId()))
                .flatMap(found -> describe(found.getT1()).map(described -> {
                    ctx.put(metadata.key(), found.getT1());
                    ctx.put(metadata.key() + ".names", List.copyOf(found.getT2()));
                    ctx.put(metadata.key() + ".described", described);
                    return described;
                }))
                .then();
        });
    }
}
