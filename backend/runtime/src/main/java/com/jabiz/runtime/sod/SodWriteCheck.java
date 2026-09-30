package com.jabiz.runtime.sod;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.entity.FieldWriteCheck;
import com.jabiz.runtime.security.SecurityEntities;
import com.jabiz.security.SodRule;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Prevents conflicts of duties where access is given (docs/design/18-numbering-approvals-tasks.md section 4.2): a new
 * role assignment ({@code SecUserRole}) or a new grant ({@code SecRolePermission}) that would leave a user with a
 * permission of each group of an SoD rule is refused ({@code SOD_CONFLICT}, 422), on every write path. Changes of
 * access are serialized for the rest of the transaction, so that two concurrent changes cannot each pass alone.
 * {@code *} is not checked (it holds every permission and is always in the conflict report).
 */
@Component
public class SodWriteCheck implements FieldWriteCheck {

    private final SodService sod;

    public SodWriteCheck(SodService sod) {
        this.sod = sod;
    }

    @Override
    public Mono<Void> verify(EntityDefinition def, Map<String, Object> values, Collection<String> changedFields) {
        if (def.name.equals(SecurityEntities.USER_ROLE) && changedFields.contains("roleId")
            && values.get("userId") instanceof UUID user && values.get("roleId") instanceof UUID role) {
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
        if (def.name.equals(SecurityEntities.ROLE_PERMISSION) && changedFields.contains("permission")
            && values.get("roleId") instanceof UUID role && values.get("permission") instanceof String permission) {
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
