package com.jabiz.quizbuks.wallet;

import com.jabiz.runtime.EntityInstance;
import com.jabiz.entity.Violation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Where a top-up goes (Q87a, D-Q87-2, docs/quizbuks/plans/Q87-fee-account.md), pure Java: {@link #KUDOS} straight
 * into the sponsor's Kudos wallet (2110, as before Q87) or {@link #CASH} into their cash account (2160). Which targets
 * a sponsor may choose and where a top-up goes when they do not are controlled parameters
 * ({@code qb.topup.targets}, {@code qb.topup.default-target}); the code decides only how a target is credited.
 */
public final class TopUpTargets {

    public static final String KUDOS = "KUDOS";
    public static final String CASH = "CASH";
    public static final List<String> ALL = List.of(KUDOS, CASH);
    public static final String DICTIONARY = "urn:jabiz:dict:quizbuks:topup-target";

    /** The targets a parameter lists (comma-separated, any case, blanks ignored), known ones only, each once. */
    public static List<String> parse(String text) {
        List<String> targets = new ArrayList<>();
        if (text == null) {
            return targets;
        }
        for (String part : text.split(",")) {
            String target = part.trim().toUpperCase(Locale.ROOT);
            if (ALL.contains(target) && !targets.contains(target)) {
                targets.add(target);
            }
        }
        return List.copyOf(targets);
    }

    /**
     * The target a top-up goes to.
     *
     * @param requested what the sponsor chose; null for the default
     * @param allowed   {@code qb.topup.targets}
     * @param fallback  {@code qb.topup.default-target}
     * @return the target, or null with the {@link #problem}
     */
    public static String choose(String requested, String allowed, String fallback) {
        List<String> targets = parse(allowed);
        String target = requested == null ? (fallback == null ? null : fallback.trim().toUpperCase(Locale.ROOT))
            : requested;
        return target != null && targets.contains(target) ? target : null;
    }

    /** Why {@link #choose} gave no target: the chosen one is not offered, or nothing is configured. */
    public static Violation problem(String requested, String allowed) {
        return requested != null && !parse(allowed).isEmpty()
            ? new Violation("target", WalletCodes.TOPUP_TARGET, "Top-ups cannot go there now",
                Map.of("allowed", String.join(",", parse(allowed))))
            : new Violation(null, WalletCodes.TOPUP_UNAVAILABLE, "Where top-ups go is not set", Map.of());
    }

    /** A top-up's target; {@link #KUDOS} for a record of before Q87 (the column's default). */
    public static String of(EntityInstance topUp) {
        Object target = topUp == null ? null : topUp.get("target");
        return CASH.equals(target) ? CASH : KUDOS;
    }

    /** The wallet a target credits. */
    public static String walletKind(String target) {
        return CASH.equals(target) ? QbWallets.CASH : QbWallets.SPONSOR;
    }

    /** The ledger account a target credits. */
    public static String account(String target) {
        return CASH.equals(target) ? QbLedger.SPONSOR_CASH : QbLedger.SPONSOR_PREPAYMENTS;
    }

    /** Whether crediting it may pay pending rewards (only Kudos pay rewards). */
    public static boolean paysRewards(String target) {
        return !CASH.equals(target);
    }

    private TopUpTargets() {}
}
