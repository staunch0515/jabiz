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

    /**
     * F-S4b: the methods a Sponsor unlocks by lighting a node (the gate parameter of each,
     * {@code tree.SponsorGates}), besides the default and their own setting.
     */
    public static final java.util.Map<String, String> GATES = java.util.Map.of(
        DIGITAL_PRIZE, QbParams.TREE_GATE_DIGITAL_PRIZE, OFFLINE_REDEEM, QbParams.TREE_GATE_OFFLINE_REDEEM);

    /**
     * F-S4b (the requester, 2026-11-10): the methods a Sponsor may publish with: the default parameter's, their own
     * setting's (none without a row) and those a lit gate opens; in the order of {@link #ALL}.
     */
    public static List<String> available(List<String> defaults, List<String> own, java.util.Collection<String> openGates) {
        return ALL.stream().filter(method -> defaults.contains(method) || own.contains(method)
            || openGates.contains(GATES.get(method))).toList();
    }

    /** The methods a sponsor without a setting may use when the parameter is not readable: the platform's only. */
    public static final List<String> DEFAULT = List.of(PLATFORM);

    /**
     * The methods of the default (Q28) {@code qb.reward.default-methods} as read at the operation time; names that are no
     * method are ignored, so a mistyped value allows less, never more. F-S4b (the requester 2026-11-07, the controller
     * 2026-11-10): a blank value gives none (a new Sponsor publishes with what a lit node opens or an administrator
     * allows them); the parameter not there at all (before setup) gives the platform's Kudos, as before.
     */
    public static List<String> defaultMethods(ParamValues params) {
        return defaultMethods(params, false);
    }

    /**
     * As {@link #defaultMethods(ParamValues)}, and on a system that proposes the first launch's values ({@code firstLaunch}:
     * {@code production} or {@code staging}, F-F10a) the parameter not there gives none: the fallback to the platform's
     * Kudos is for a system that has not been set up, and such a system must not publish a Kudos reward by default.
     */
    public static List<String> defaultMethods(ParamValues params, boolean firstLaunch) {
        String value = params == null ? null : params.find(QbParams.REWARD_DEFAULT_METHODS, String.class).orElse(null);
        if (value == null) {
            return firstLaunch ? List.of() : DEFAULT;
        }
        if (value.isBlank()) {
            return List.of();
        }
        List<String> named = parse(value);
        return ALL.stream().filter(named::contains).toList();
    }

    /**
     * F-F10a: whether the switch {@code qb.reward.kudos.enabled} keeps {@link #PLATFORM} from being published: it is
     * {@code false}, or it is not there on a system that proposes the first launch's values ({@code firstLaunch}:
     * default-deny, the switch was never published). Not there on any other system (before setup) is not off, as the
     * default methods' parameter not there gives the platform's Kudos (F-S4b).
     */
    public static boolean kudosOff(ParamValues params, boolean firstLaunch) {
        Boolean value = params == null ? null : params.find(QbParams.REWARD_KUDOS_ENABLED, Boolean.class).orElse(null);
        return Boolean.FALSE.equals(value) || (firstLaunch && value == null);
    }

    /**
     * F-F10a: the methods without {@link #PLATFORM} when the switch is {@link #kudosOff off}, whoever has the method by
     * default, setting or lit node; unchanged otherwise. The one place the switch is applied: saving, copying,
     * submitting and importing a package all ask {@code PublicationEditing.allowedMethods}, and the sponsor console's
     * template says the same in SQL.
     */
    public static List<String> withKudosSwitch(List<String> methods, ParamValues params, boolean firstLaunch) {
        return kudosOff(params, firstLaunch) ? methods.stream().filter(method -> !PLATFORM.equals(method)).toList()
            : methods;
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
