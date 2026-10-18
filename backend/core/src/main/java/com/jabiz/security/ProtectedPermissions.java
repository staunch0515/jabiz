package com.jabiz.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The protected permissions (decision D48 item 1; docs/design/10-security.md section 16): giving a user one of them
 * (through a role assignment or a role's new permission), and resetting the credentials of a user who holds one,
 * takes effect only after a second administrator approves. {@code *} holds every permission and is always protected,
 * whatever the configuration says.
 */
public final class ProtectedPermissions {

    /** Permission codes the configuration may name (as approval levels and SoD rules do). */
    public static final Pattern PERMISSION = Pattern.compile("[A-Za-z0-9._:*-]{1,200}");

    /**
     * The default: who holds them changes rights ({@code security.user-role.write}, {@code security.role.write}),
     * decides the four eyes of everything else ({@code approval.decide}, {@code control.publish}), or writes the books
     * and the business parameters alone ({@code ledger.post}, {@code ledger.reverse}, {@code platform.param.write}).
     */
    public static final List<String> DEFAULT = List.of("*", "security.user-role.write", "security.role.write",
        "approval.decide", "control.publish", "ledger.post", "ledger.reverse", "platform.param.write");

    private final Set<String> permissions;

    private ProtectedPermissions(Set<String> permissions) {
        this.permissions = permissions;
    }

    /** The permissions configured; empty for {@link #DEFAULT}. {@code *} is always added. */
    public static ProtectedPermissions of(Collection<String> configured) {
        Set<String> set = new TreeSet<>();
        if (configured == null || configured.stream().allMatch(p -> p == null || p.isBlank())) {
            set.addAll(DEFAULT);
        } else {
            configured.stream().filter(p -> p != null && !p.isBlank()).map(String::strip).forEach(set::add);
        }
        set.add(ALL);
        return new ProtectedPermissions(Set.copyOf(set));
    }

    /** What is wrong with a configured list; empty when nothing is. */
    public static List<String> problems(Collection<String> configured) {
        List<String> problems = new ArrayList<>();
        if (configured == null) {
            return problems;
        }
        for (String permission : configured) {
            if (permission == null || permission.isBlank()) {
                continue;
            }
            String p = permission.strip();
            if (!PERMISSION.matcher(p).matches() || (p.contains("*") && !p.equals(ALL))) {
                problems.add("'" + p + "' is not a permission code (only * itself may contain *)");
            }
        }
        return problems;
    }

    private static final String ALL = "*";

    public boolean isProtected(String permission) {
        return permission != null && permissions.contains(permission);
    }

    /** The protected permissions among {@code granted}, sorted. */
    public Set<String> among(Collection<String> granted) {
        Set<String> found = new TreeSet<>();
        for (String permission : granted) {
            if (isProtected(permission)) {
                found.add(permission);
            }
        }
        return found;
    }

    /**
     * The protected permissions of {@code granted} a holder of {@code held} does not hold yet ({@code *} holds them
     * all), sorted.
     */
    public Set<String> gained(Collection<String> granted, Collection<String> held) {
        if (held.contains(ALL)) {
            return Set.of();
        }
        Set<String> found = among(granted);
        found.removeAll(held);
        return found;
    }

    /** The protected permissions, sorted. */
    public Set<String> permissions() {
        return new TreeSet<>(permissions);
    }
}
