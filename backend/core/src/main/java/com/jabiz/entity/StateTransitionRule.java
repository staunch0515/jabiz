package com.jabiz.entity;

import java.util.List;

public record StateTransitionRule(String from, List<String> to) {
    public StateTransitionRule {
        to = to != null ? List.copyOf(to) : List.of();
    }

    /** Returns true if this rule allows moving from its source state to the target state. */
    public boolean canTransitionTo(String targetState) {
        return to.contains(targetState);
    }
}
