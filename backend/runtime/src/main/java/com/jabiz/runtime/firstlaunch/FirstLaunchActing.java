package com.jabiz.runtime.firstlaunch;

import com.jabiz.context.RequestContext;

import java.util.Locale;
import java.util.Set;

/**
 * The request context of a step that acts as an account (decision D82): the account's user id, no roles, no
 * permissions, no entry, no session. Only the first launch's runner builds it, and only {@code FIRST_LAUNCH_STEP}
 * honours it, after {@link FirstLaunchPowers#requireActable}; an architecture test keeps every other class away.
 */
final class FirstLaunchActing {

    private FirstLaunchActing() {}

    static RequestContext as(String userId, Locale locale, String requestId) {
        return new RequestContext(userId, null, locale, requestId, Set.of(), Set.of());
    }
}
