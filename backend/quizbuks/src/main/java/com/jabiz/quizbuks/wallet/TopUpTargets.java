package com.jabiz.quizbuks.wallet;

import java.util.Arrays;
import java.util.List;

/**
 * What a top-up becomes (Q87, D-Q87-2): the targets the administrators allow ({@code qb.topup.targets}) and the one
 * chosen when the sponsor names none ({@code qb.topup.default-target}). Pure Java; the processes decide with it and
 * the pages read the same parameters.
 */
public final class TopUpTargets {

    private TopUpTargets() {}

    /** The targets a parameter's value names, in its order; unknown names are dropped. */
    public static List<String> allowed(String targetsParam) {
        if (targetsParam == null) {
            return List.of();
        }
        return Arrays.stream(targetsParam.split(",")).map(String::strip).filter(QbTopUps.TARGETS::contains)
            .distinct().toList();
    }

    /**
     * The target of a top-up: the one given when it is allowed; none given, the default when allowed, else the
     * first allowed. Null when the given one is not allowed, or nothing is.
     */
    public static String resolve(String given, String targetsParam, String defaultParam) {
        List<String> allowed = allowed(targetsParam);
        if (given != null && !given.isBlank()) {
            return allowed.contains(given) ? given : null;
        }
        if (defaultParam != null && allowed.contains(defaultParam.strip())) {
            return defaultParam.strip();
        }
        return allowed.isEmpty() ? null : allowed.getFirst();
    }

    /** The wallet kind a target is credited to. */
    public static String walletKind(String target) {
        return QbTopUps.CASH.equals(target) ? QbWallets.CASH : QbWallets.SPONSOR;
    }

    /** The account a target is credited to: the sponsor's prepayments (2110) or their cash account (2160). */
    public static String account(String target) {
        return QbTopUps.CASH.equals(target) ? QbLedger.SPONSOR_CASH : QbLedger.SPONSOR_PREPAYMENTS;
    }

    /** A stored top-up's target; those from before Q87 have none stored and are Kudos. */
    public static String of(Object stored) {
        return QbTopUps.CASH.equals(stored) ? QbTopUps.CASH : QbTopUps.KUDOS;
    }
}
