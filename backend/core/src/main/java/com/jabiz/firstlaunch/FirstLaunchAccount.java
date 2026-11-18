package com.jabiz.firstlaunch;

import java.util.List;
import java.util.Objects;

/**
 * An account the first launch creates (section {@code accounts} of the configuration): invited once the content
 * phase is done, given {@code roles} once the person has accepted the invitation and bound a second factor
 * (docs/design/23-first-launch.md section 5).
 *
 * @param key      the account's name inside the configuration, which other sections refer to
 * @param email    the address the invitation goes to
 * @param name     the display name
 * @param language the language of the person's mail ({@code null}: the site's)
 * @param roles    the role codes the account is given
 * @param report   whether the report of the first launch is mailed to this account
 */
public record FirstLaunchAccount(String key, String email, String name, String language, List<String> roles,
    boolean report) {

    public FirstLaunchAccount {
        Objects.requireNonNull(key, "key must not be null");
        roles = roles == null ? List.of() : List.copyOf(roles);
    }

    @Override
    public String toString() {
        // The address is personal data: logs name the account by its key.
        return "FirstLaunchAccount[key=" + key + ", roles=" + roles + ", report=" + report + "]";
    }
}
