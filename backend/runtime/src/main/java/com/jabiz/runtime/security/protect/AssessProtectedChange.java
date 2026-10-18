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
     * For the approvers (review H1, M3, M-a): the names of the users the change is for, who last set their credentials
     * and whether they are the user's own, their current addresses (who entered them, whether a mailed link proved
     * them, whether they lie outside {@code jabiz.security.protected-grants.trusted-email-domains}), and the history
     * of their credentials - its first line, the account's creation, always kept, the middle left out when long; and
     * (decision D51) which gained permissions an approver need not hold because a published SoD rule forbids them
     * alongside what the approver holds ({@code exemptions}). Read only for a change that waits for approval.
     */
    public record Described(List<String> affected, List<String> credentials, List<String> history,
        List<String> addresses, List<String> exemptions) {

        public Described(List<String> affected, List<String> credentials, List<String> history,
            List<String> addresses) {
            this(affected, credentials, history, addresses, List.of());
        }
    }

    /**
     * For the approvers (decision D51): each gained permission ({@code *} never) that an approver holding a permission
     * of the other group of a published SoD rule need not hold - "approver cannot hold X by rule R".
     */
    static List<String> exemptions(java.util.Set<String> gained, List<com.jabiz.security.SodRule> rules) {
        List<String> lines = new java.util.ArrayList<>();
        for (String permission : new java.util.TreeSet<>(gained)) {
            if (com.jabiz.context.RequestContext.ALL_PERMISSIONS.equals(permission)) {
                continue;
            }
            for (com.jabiz.security.SodRule rule : rules) {
                java.util.Set<String> other = rule.left().contains(permission) ? rule.right()
                    : rule.right().contains(permission) ? rule.left() : java.util.Set.of();
                if (!other.isEmpty()) {
                    lines.add("approver holding any of " + String.join(", ", new java.util.TreeSet<>(other))
                        + " cannot hold " + permission + " by rule " + rule.ruleCode() + ": not required of them");
                }
            }
        }
        return List.copyOf(lines);
    }

    /** The most characters of the credential histories, all users together. */
    static final int HISTORY = 6000;

    private Mono<Described> describe(ProtectedGrants.Assessment assessment) {
        if (!assessment.needsApproval()) {
            return Mono.just(new Described(List.of(), List.of(), List.of(), List.of()));
        }
        return reactor.core.publisher.Flux.fromIterable(assessment.affected())
            .concatMap(user -> grants.userName(user).zipWith(grants.credentials(user)))
            .collectList()
            .zipWith(grants.sodRules())
            .map(both -> {
                List<reactor.util.function.Tuple2<String, ProtectedGrants.Credentials>> found = both.getT1();
                int budget = Math.max(400, HISTORY / Math.max(1, found.size()));
                List<String> histories = new java.util.ArrayList<>();
                int used = 0;
                for (int i = 0; i < found.size(); i++) {
                    String entry = found.get(i).getT1() + ": " + keepEnds(found.get(i).getT2().history(), budget);
                    if (used + entry.length() > HISTORY && i > 0) {
                        histories.add("... and " + (found.size() - i) + " more users");
                        break;
                    }
                    histories.add(entry);
                    used += entry.length();
                }
                return new Described(found.stream().map(f -> f.getT1()).toList(),
                    found.stream().map(f -> f.getT1() + ": " + f.getT2().summary()).toList(), histories,
                    found.stream().map(f -> f.getT1() + ": " + f.getT2().address()
                        + (grants.settings().trustedAddress(f.getT2().email()) ? ""
                            : " - OUTSIDE THE TRUSTED DOMAINS")).toList(),
                    exemptions(assessment.gained(), both.getT2()));
            });
    }

    /**
     * {@code lines} joined, within about {@code budget} characters: the first line (the account's creation and its
     * address) always, then as many of the latest as fit, the ones between left out and counted.
     */
    static String keepEnds(List<String> lines, int budget) {
        String all = String.join("; ", lines);
        if (all.length() <= budget || lines.size() < 2) {
            return all;
        }
        String first = lines.getFirst();
        java.util.Deque<String> tail = new java.util.ArrayDeque<>();
        int length = first.length();
        for (int i = lines.size() - 1; i > 0; i--) {
            String line = lines.get(i);
            if (length + line.length() + 2 > budget - 40 && !tail.isEmpty()) {
                break;
            }
            tail.addFirst(line);
            length += line.length() + 2;
        }
        int left = lines.size() - 1 - tail.size();
        return first + (left > 0 ? "; ... " + left + " changes left out ...; " : "; ") + String.join("; ", tail);
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
