package com.jabiz.quizbuks.publishing;

import java.util.ArrayList;
import java.util.List;

/**
 * How a publication's reward reaches the taker (docs/quizbuks/plans/Q22-reward-methods.md): Kudos from the sponsor's
 * wallet, paid by the platform ({@link #PLATFORM}, what every publication did before Q22); cash the sponsor hands the
 * taker, recorded as a cash reward both confirm ({@link #SPONSOR_CASH}); or a prize the taker collects at the
 * sponsor's place ({@link #OFFLINE_REDEEM}). Only {@link #PLATFORM} moves money: the others never lock the sponsor's
 * wallet nor post to the ledger, and their reward figure is the prize's value in Kudos. Which methods a sponsor may
 * publish with is set by an administrator ({@code QbSponsorRewardMethods}; {@link #PLATFORM} alone without a row).
 */
public final class RewardMethod {

    public static final String PLATFORM = "PLATFORM";
    public static final String SPONSOR_CASH = "SPONSOR_CASH";
    public static final String OFFLINE_REDEEM = "OFFLINE_REDEEM";

    public static final List<String> ALL = List.of(PLATFORM, SPONSOR_CASH, OFFLINE_REDEEM);

    public static final String DICTIONARY = "urn:jabiz:dict:quizbuks:reward-method";

    /** The methods a sponsor without a setting may use. */
    public static final List<String> DEFAULT = List.of(PLATFORM);

    /** Whether the method is {@link #PLATFORM} (null, as a publication from before Q22 is read, counts as it). */
    public static boolean platform(Object method) {
        return method == null || PLATFORM.equals(method);
    }

    /** The methods written as a setting's text ({@code PLATFORM,SPONSOR_CASH}), in the order of {@link #ALL}. */
    public static String format(List<String> methods) {
        return String.join(",", ALL.stream().filter(methods::contains).toList());
    }

    /** The methods of a setting's text; {@link #DEFAULT} for none. */
    public static List<String> parse(Object methods) {
        if (methods == null || methods.toString().isBlank()) {
            return DEFAULT;
        }
        List<String> parsed = new ArrayList<>();
        for (String method : methods.toString().split(",")) {
            if (!method.isBlank() && !parsed.contains(method.strip())) {
                parsed.add(method.strip());
            }
        }
        return List.copyOf(parsed);
    }

    private RewardMethod() {}
}
