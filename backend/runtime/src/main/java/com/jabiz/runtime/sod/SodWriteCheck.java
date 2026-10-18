package com.jabiz.runtime.sod;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.operation.Operation;
import com.jabiz.runtime.storage.StorageEngine;
import com.jabiz.runtime.temporal.TemporalWriteCheck;
import com.jabiz.temporal.Timeline;
import com.jabiz.temporal.VersionPlanner.PlannedVersion;
import com.jabiz.runtime.security.SecurityEntities;
import com.jabiz.security.SodRule;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Optional;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Prevents conflicts of duties where access is given (docs/design/18-numbering-approvals-tasks.md section 4.2): a
 * role assignment ({@code SecUserRole}) or a grant ({@code SecRolePermission}) coming alive - inserted, its deletion
 * reverted, its scheduled deletion cancelled - that would leave a user with a permission of each group of an SoD rule
 * is refused ({@code SOD_CONFLICT}, 422), on every write path: it runs where every temporal write passes
 * ({@link TemporalWriteCheck}; 16r, decision D48, reverts and cancellations included). Changes of access are serialized
 * for the rest of the transaction ({@code jabiz.sod} advisory lock, taken before anything is read), so that two
 * concurrent changes cannot each pass alone. {@code *} is not checked (it holds every permission and is always in the
 * conflict report).
 */
@Component
public class SodWriteCheck implements TemporalWriteCheck {

    private final SodService sod;

    public SodWriteCheck(SodService sod) {
        this.sod = sod;
    }

    @Override
    public Mono<List<Violation>> check(StorageEngine engine, EntityDefinition def, UUID id, Timeline timeline,
        List<PlannedVersion> versions, Operation operation, Object grant) {
        boolean assignment = def.name.equals(SecurityEntities.USER_ROLE);
        if (!assignment && !def.name.equals(SecurityEntities.ROLE_PERMISSION)) {
            return Mono.just(List.of());
        }
        Optional<Map<String, Object>> alive = TemporalWriteCheck.comesAlive(timeline, versions);
        if (alive.isEmpty()) {
            return Mono.just(List.of());
        }
        Map<String, Object> values = alive.get();
        return verify(assignment, values).thenReturn(List.<Violation>of());
    }

    private Mono<Void> verify(boolean assignment, Map<String, Object> values) {
        UUID user = com.jabiz.runtime.storage.Rows.uuid(values.get("userId"));
        UUID role = com.jabiz.runtime.storage.Rows.uuid(values.get("roleId"));
        if (assignment && user != null && role != null) {
            return sod.lock().then(sod.rules()).flatMap(rules -> rules.isEmpty() ? Mono.empty()
                : sod.holdings(List.of(user)).zipWith(sod.grantsOf(role)).flatMap(found -> {
                    Set<String> held = new LinkedHashSet<>(found.getT2().keySet());
                    SodService.Holding holding = found.getT1().get(user);
                    if (holding != null) {
                        held.addAll(holding.permissions());
                    }
                    return refuse(rules, Map.of(user, held), "roleId");
                }));
        }
        if (!assignment && role != null && values.get("permission") instanceof String permission) {
            // Checked whether the role is enabled or not: enabling it later is not a write checked here.
            return sod.lock().then(sod.rules()).flatMap(rules -> rules.isEmpty() ? Mono.empty()
                : usersWith(rules, role, permission));
        }
        return Mono.empty();
    }

    private Mono<Void> usersWith(List<SodRule> rules, UUID role, String permission) {
        return sod.usersOf(role).flatMap(users -> users.isEmpty() ? Mono.empty()
            : sod.holdings(users).flatMap(holdings -> {
                Map<UUID, Set<String>> after = new LinkedHashMap<>();
                for (UUID user : users) {
                    Set<String> held = new LinkedHashSet<>();
                    SodService.Holding holding = holdings.get(user);
                    if (holding != null) {
                        held.addAll(holding.permissions());
                    }
                    held.add(permission);
                    after.put(user, held);
                }
                return refuse(rules, after, "permission");
            }));
    }

    private static Mono<Void> refuse(List<SodRule> rules, Map<UUID, Set<String>> permissionsByUser, String field) {
        List<Violation> violations = new ArrayList<>();
        permissionsByUser.forEach((user, permissions) -> {
            for (SodRule rule : rules) {
                if (rule.violatedBy(permissions)) {
                    violations.add(new Violation(field, PlatformErrorCodes.SOD_CONFLICT,
                        "Segregation of duties rule " + rule.ruleCode() + ": user " + user
                            + " would hold permissions of both its groups",
                        Map.of("rule", rule.ruleCode(), "user", user.toString())));
                }
            }
        });
        return violations.isEmpty() ? Mono.empty() : Mono.error(new BusinessRuleViolationException(violations));
    }
}
