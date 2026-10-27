package com.jabiz.quizbuks.publishing;

import com.jabiz.param.ParamValues;
import com.jabiz.quizbuks.setup.QbParams;

import java.util.ArrayList;
import java.util.List;

/**
 * How a publication's reward reaches the taker (docs/quizbuks/plans/Q22-reward-methods.md): Kudos from the sponsor's
 * wallet, paid by the platform ({@link #PLATFORM}, what every publication did before Q22); cash the sponsor hands the
 * taker, recorded as a cash reward both confirm ({@link #SPONSOR_CASH}); or a prize the taker collects at the
 * sponsor's place ({@link #OFFLINE_REDEEM}); or, since Q33 (docs/quizbuks/plans/Q33-digital-prize.md), a digital
 * prize (a code, a voucher number, a link) the system hands over or the sponsor sends ({@link #DIGITAL_PRIZE}). Only
 * {@link #PLATFORM} moves money: the others never lock the sponsor's wallet nor post to the ledger, and their reward
 * figure is the prize's value in Kudos. Which methods a sponsor may
 * publish with is set by an administrator ({@code QbSponsorRewardMethods}); without a row the parameter
 * {@code qb.reward.default-methods} decides ({@link #defaultMethods}, Q28).
 */
public final class RewardMethod {

    public static final String PLATFORM = "PLATFORM";
    public static final String SPONSOR_CASH = "SPONSOR_CASH";
    public static final String OFFLINE_REDEEM = "OFFLINE_REDEEM";
    public static final String DIGITAL_PRIZE = "DIGITAL_PRIZE";

    /**
     * S2 (docs/quizbuks/shopify/S2-storefront.md, S2-2): the reward is the channel's own affair (a shop hands out a
     * discount code): the engine scores and records whether the attempt passed, and settles nothing. Only a channel's
     * publication has it ({@link ChannelPublishing}); no sponsor may be allowed it, so it is not in {@link #ALL}.
     */
    public static final String CHANNEL = "CHANNEL";

    /** The methods a sponsor may be allowed (Q22). */
    public static final List<String> ALL = List.of(PLATFORM, SPONSOR_CASH, OFFLINE_REDEEM, DIGITAL_PRIZE);

    /** Every method a publication may have: a sponsor's, and a channel's ({@link #CHANNEL}). */
    public static final List<String> CODES = List.of(PLATFORM, SPONSOR_CASH, OFFLINE_REDEEM, DIGITAL_PRIZE, CHANNEL);

    public static final String DICTIONARY = "urn:jabiz:dict:quizbuks:reward-method";

    /** The methods a sponsor without a setting may use when the parameter is not readable: the platform's only. */
    public static final List<String> DEFAULT = List.of(PLATFORM);

    /**
     * The methods a sponsor without a setting of their own may use (Q28): the parameter
     * {@code qb.reward.default-methods} as read at the operation time, its declared value before setup; names that
     * are no method are ignored, so a mistyped value allows less, never more (none of them: the sponsor publishes
     * once an administrator sets their methods); blank, as before setup, means the platform's Kudos only.
     */
    public static List<String> defaultMethods(ParamValues params) {
        List<String> named = parse(QbParams.text(params, QbParams.REWARD_DEFAULT_METHODS));
        return ALL.stream().filter(named::contains).toList();
    }

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
