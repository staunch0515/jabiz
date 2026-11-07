package com.jabiz.runtime.approval;

import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Settings of {@code APPROVAL_SCOPE_MOVE} (decision D76): {@code jabiz.approval.scope-move.max-items}, the most cases
 * (and, separately, the most task source keys) one call moves - {@value ApprovalScopeMove#DEFAULT_MAX_ITEMS} by
 * default, at most {@value ApprovalScopeMove#MAX_LIMIT}. Applications read it here to split a large reassignment into
 * calls of this size rather than repeating the number; a value out of range is a startup error ({@code APPROVAL}).
 */
@Component
public class ApprovalScopeMoveSettings implements PlatformCheck {

    public static final String MAX_ITEMS = "jabiz.approval.scope-move.max-items";

    private final int maxItems;

    public ApprovalScopeMoveSettings(
        @Value("${" + MAX_ITEMS + ":" + ApprovalScopeMove.DEFAULT_MAX_ITEMS + "}") int maxItems) {
        this.maxItems = maxItems;
    }

    /** The most cases, and the most task source keys, one call of {@code APPROVAL_SCOPE_MOVE} accepts. */
    public int maxItems() {
        return maxItems;
    }

    @Override
    public List<CheckProblem> check() {
        if (maxItems < 1 || maxItems > ApprovalScopeMove.MAX_LIMIT) {
            return List.of(CheckProblem.error("APPROVAL", MAX_ITEMS, "must be between 1 and "
                + ApprovalScopeMove.MAX_LIMIT + ", not " + maxItems));
        }
        return List.of();
    }
}
