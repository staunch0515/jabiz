package com.jabiz.runtime.sod;

import com.jabiz.context.RequestContext;
import com.jabiz.security.SodRule;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Collection;

/**
 * The check at the entry of processes (docs/design/18-numbering-approvals-tasks.md section 4.3), behind the check of
 * every change of access ({@link SodWriteCheck}): an actor who holds both groups of an SoD rule (a rule published
 * after the roles were given, say) cannot run a process that needs a permission of either group. Holders of
 * {@code *} are not stopped here; the conflict report lists them.
 */
@Component
public class SodGuard {

    private final SodService sod;

    public SodGuard(SodService sod) {
        this.sod = sod;
    }

    /** Fails with {@link SodConflictException} when {@code request} may not use {@code required} together. */
    public Mono<Void> check(RequestContext request, Collection<String> required) {
        if (required.isEmpty() || SodRule.coveredByAll(request.permissions())) {
            return Mono.empty();
        }
        return sod.rules().flatMap(rules -> {
            for (SodRule rule : rules) {
                if (rule.violatedBy(request.permissions())) {
                    for (String permission : required.stream().sorted().toList()) {
                        if (rule.involves(permission)) {
                            return Mono.error(new SodConflictException(permission, rule.ruleCode(),
                                "Segregation of duties rule " + rule.ruleCode() + ": you hold both of its groups of "
                                    + "permissions, so you cannot use " + permission));
                        }
                    }
                }
            }
            return Mono.empty();
        });
    }
}
