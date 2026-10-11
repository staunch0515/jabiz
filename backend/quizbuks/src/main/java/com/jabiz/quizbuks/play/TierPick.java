package com.jabiz.quizbuks.play;

import com.jabiz.quizbuks.publishing.CardTiers;

import java.util.List;
import java.util.Optional;

/**
 * The reward tier of a score (docs/quizbuks/plans/Q5-answering.md, D-Q5-2): the tier whose range holds it, both ends
 * included. A submitted publication's tiers cover every score from 0 to the full score without overlap
 * ({@code TierCoverage}), so a score always has exactly one.
 */
public final class TierPick {

    public static Optional<CardTiers.Tier> of(List<CardTiers.Tier> tiers, int score) {
        return tiers.stream().filter(tier -> tier.fromScore() <= score && score <= tier.toScore()).findFirst();
    }

    private TierPick() {}
}
