package com.jabiz.runtime.firstlaunch.accounts;

import com.jabiz.i18n.MessageCatalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The first launch's report (plan F-LAUNCH section 6.5), in a reader's language: how the run ended, each account with
 * its roles and when it accepted its invitation, the setup window of protected grants, the grants handed over to the
 * administrators' approval, the accounts that kept only the waiting role, and what is left for people.
 */
final class WaitingReport {

    private static final java.util.regex.Pattern PLACEHOLDER = java.util.regex.Pattern.compile("\\{([a-zA-Z]+)}");

    private WaitingReport() {}

    static String summary(String kind, LoadWaitingState.State state, List<GrantPlan.Account> accounts,
        List<String> remaining, MessageCatalog messages, Locale locale) {
        List<String> lines = new ArrayList<>();
        lines.add(text(messages, locale, "DONE".equals(kind) ? "first-launch.report.done"
            : "first-launch.report.expired", Map.of()));
        lines.add("");
        for (GrantPlan.Account account : accounts) {
            FirstLaunchAccountStore.Facts facts = state.facts().get(account.userId());
            List<String> roles = new ArrayList<>();
            for (GrantPlan.Role role : account.roles()) {
                roles.add(role.code() + " " + account.state(role.code()));
            }
            lines.add(text(messages, locale, "first-launch.report.account", Map.of("key", account.key(),
                "name", facts == null || facts.displayName() == null ? "" : facts.displayName(),
                "roles", String.join(", ", roles),
                "accepted", account.acceptedAt() == null ? "-" : account.acceptedAt().toString())));
        }
        lines.add("");
        lines.add(text(messages, locale, state.armed() ? "first-launch.report.window-closed"
            : "first-launch.report.window-open", Map.of()));
        List<String> left = new ArrayList<>();
        GrantPlan.handedOver(accounts).forEach(pending -> left.add(text(messages, locale,
            "first-launch.report.handed-over", Map.of("key", pending.key(), "role", pending.role()))));
        accounts.stream().filter(account -> account.roles().stream()
                .noneMatch(role -> account.state(role.code()) == GrantPlan.RoleState.APPLIED))
            .forEach(account -> left.add(text(messages, locale, "first-launch.report.no-role",
                Map.of("key", account.key()))));
        left.addAll(remaining);
        left.add(text(messages, locale, "first-launch.report.remove-config", Map.of()));
        lines.add("");
        lines.add(text(messages, locale, "first-launch.report.remaining", Map.of()));
        left.forEach(line -> lines.add("- " + line));
        return String.join("\n", lines);
    }

    /** What the end records with its event: keys, user ids and role states only. */
    static String detail(List<GrantPlan.Account> accounts) {
        StringBuilder json = new StringBuilder("{\"accounts\":[");
        for (int i = 0; i < accounts.size(); i++) {
            GrantPlan.Account account = accounts.get(i);
            json.append(i == 0 ? "" : ",").append("{\"key\":").append(quoted(account.key()))
                .append(",\"userId\":").append(quoted(account.userId().toString())).append(",\"roles\":{");
            for (int j = 0; j < account.roles().size(); j++) {
                String code = account.roles().get(j).code();
                json.append(j == 0 ? "" : ",").append(quoted(code)).append(':')
                    .append(quoted(account.state(code).name()));
            }
            json.append("}}");
        }
        return json.append("]}").toString();
    }

    private static String quoted(String text) {
        StringBuilder quoted = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            if (c == '"' || c == '\\') {
                quoted.append('\\').append(c);
            } else if (c < 0x20) {
                quoted.append(String.format("\\u%04x", (int) c));
            } else {
                quoted.append(c);
            }
        }
        return quoted.append('"').toString();
    }

    private static String text(MessageCatalog messages, Locale locale, String key, Map<String, String> params) {
        // One pass: a value holding "{name}" (a display name) is not replaced again.
        java.util.regex.Matcher matcher = PLACEHOLDER.matcher(messages.find(key, locale).orElse(key));
        StringBuilder text = new StringBuilder();
        while (matcher.find()) {
            String value = params.get(matcher.group(1));
            matcher.appendReplacement(text, java.util.regex.Matcher.quoteReplacement(value == null ? matcher.group()
                : value));
        }
        matcher.appendTail(text);
        return text.toString();
    }
}
