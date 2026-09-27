package com.jabiz.entity;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class StateTransitionBuilder {
    private final List<StateTransitionRule> rules = new ArrayList<>();
    private final Set<String> initial = new LinkedHashSet<>();

    public FromClause from(String state) { return new FromClause(state); }

    /**
     * Declares the states a new instance may start in. Needed when a lifecycle returns to its first state (for
     * example a review that sends a draft back to {@code DRAFT}); without it the initial states are those that are
     * left but never entered (docs/design/02-metamodel.md section 4).
     */
    public StateTransitionBuilder initial(String... states) {
        for (String state : states) {
            if (!initial.add(state)) {
                throw new IllegalArgumentException("initial state " + state + " is declared twice");
            }
        }
        return this;
    }

    public final class FromClause {
        private final String from;
        FromClause(String state) { this.from = state; }
        public StateTransitionBuilder to(String... targets) {
            rules.add(new StateTransitionRule(from, List.of(targets)));
            return StateTransitionBuilder.this;
        }
    }

    List<StateTransitionRule> build() { return List.copyOf(rules); }

    Set<String> initialStates() { return java.util.Collections.unmodifiableSet(new LinkedHashSet<>(initial)); }
}
