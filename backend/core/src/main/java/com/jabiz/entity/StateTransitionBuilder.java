package com.jabiz.entity;

import java.util.ArrayList;
import java.util.List;

public final class StateTransitionBuilder {
    private final List<StateTransitionRule> rules = new ArrayList<>();

    public FromClause from(String state) { return new FromClause(state); }

    public final class FromClause {
        private final String from;
        FromClause(String state) { this.from = state; }
        public StateTransitionBuilder to(String... targets) {
            rules.add(new StateTransitionRule(from, List.of(targets)));
            return StateTransitionBuilder.this;
        }
    }

    List<StateTransitionRule> build() { return List.copyOf(rules); }
}
