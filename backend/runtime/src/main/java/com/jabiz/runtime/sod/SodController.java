package com.jabiz.runtime.sod;

import com.jabiz.runtime.approval.ApprovalPermissions;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.security.Permissions;
import com.jabiz.security.SodRule;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The conflict report (docs/design/18-numbering-approvals-tasks.md section 4.4): every user who holds a permission of
 * each group of an SoD rule, with the permissions and the roles they come from. Holders of {@code *} are always
 * listed ({@code wildcard}), whatever else they hold. Needs {@value ApprovalPermissions#SOD_READ}.
 */
@RestController
@RequestMapping("/api/sod")
class SodController {

    /**
     * @param left  the permissions of the rule's left group the user holds ({@code *} for a wildcard holder)
     * @param roles the roles granting them
     */
    record Conflict(String userId, String userName, String ruleCode, List<String> left, List<String> right,
        List<String> roles, boolean wildcard) {}

    private final SodService sod;

    SodController(SodService sod) {
        this.sod = sod;
    }

    @GetMapping("/conflicts")
    Mono<List<Conflict>> conflicts() {
        return RequestContexts.current().flatMap(request -> {
            Permissions.require(request, ApprovalPermissions.SOD_READ, "Reading the SoD conflict report");
            return sod.rules().zipWith(sod.holdings(null)).map(found -> {
                List<Conflict> conflicts = new ArrayList<>();
                for (SodService.Holding holding : found.getT2().values()) {
                    for (SodRule rule : found.getT1()) {
                        conflict(rule, holding).ifPresent(conflicts::add);
                    }
                }
                return conflicts;
            });
        });
    }

    private static java.util.Optional<Conflict> conflict(SodRule rule, SodService.Holding holding) {
        Map<String, Set<String>> held = holding.rolesByPermission();
        boolean wildcard = SodRule.coveredByAll(held.keySet());
        if (!wildcard && !rule.violatedBy(held.keySet())) {
            return java.util.Optional.empty();
        }
        List<String> left = held.keySet().stream().filter(rule.left()::contains).sorted().toList();
        List<String> right = held.keySet().stream().filter(rule.right()::contains).sorted().toList();
        Set<String> roles = new TreeSet<>();
        held.forEach((permission, grantedBy) -> {
            if (rule.involves(permission) || (wildcard && permission.equals("*"))) {
                roles.addAll(grantedBy);
            }
        });
        return java.util.Optional.of(new Conflict(String.valueOf(holding.userId()), holding.userName(),
            rule.ruleCode(), wildcard && left.isEmpty() ? List.of("*") : left,
            wildcard && right.isEmpty() ? List.of("*") : right, List.copyOf(roles), wildcard));
    }
}
