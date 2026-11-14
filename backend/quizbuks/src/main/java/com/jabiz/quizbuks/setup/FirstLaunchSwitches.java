package com.jabiz.quizbuks.setup;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The one list of the money switches and the values they have at the first launch (F-F10, docs/quizbuks/plans/impl/
 * F-F10.md): each row is a parameter, the value it takes when the first launch's state is proposed, and who provides it.
 * Pure data, read by {@code QB_SETUP} ({@link #valueFor}), by the startup check ({@link FirstLaunchSwitchesCheck}) and
 * by the finance page's card (F-F10b). The declared values in {@link QbParams} stay what they were (today's behaviour,
 * which the tests rely on); a system whose {@code jabiz.web.environment} is {@link #appliesTo production or staging}
 * proposes these values instead. The first launch's value of a parameter is written here and nowhere else.
 *
 * <p>Rows of a charge group that does not exist yet are {@link Status#WAITING}: not proposed and not shown as "due",
 * only listed as waiting for their feature. Whoever adds the key to {@link QbParams#ALL} must turn the row
 * {@link Status#ACTIVE} in the same change: {@code FirstLaunchSwitchesTest} fails otherwise. No amount is written here:
 * a row of an amount has no value ({@code null}) and the declared one stands.
 */
public final class FirstLaunchSwitches {

    /** The environments whose {@code QB_SETUP} proposes the first launch's values: the real system and the staging one. */
    public static final Set<String> ENVIRONMENTS = Set.of("production", "staging");

    /**
     * How a row stands.
     * {@link #ACTIVE}: the key exists and {@code value} (if any) is what the first launch proposes.
     * {@link #WAITING}: the key does not exist yet, or does not do what the row means yet; {@code waitingFor} names the
     * feature. Not proposed.
     * {@link #MANUAL}: the parameter is deliberately not set up (not set means off or unlimited): at the first launch
     * an operator proposes a value through a controlled change when one is wanted; {@code QB_SETUP} proposes nothing.
     */
    public enum Status { ACTIVE, WAITING, MANUAL }

    /**
     * @param key        the parameter
     * @param group      the switch's group, the row of the first launch's table ({@code fundSwitch.group.<group>})
     * @param status     how the row stands
     * @param value      the first launch's value, typed as {@link QbParams.Param#value()} (Boolean or text); null for
     *                   "the declared value stands" (amounts), and for the intent of nothing
     * @param waitingFor the feature a {@link Status#WAITING} row waits for (F-M8)
     * @param why        why the value differs from the declared one; required then
     */
    public record Row(String key, String group, Status status, Object value, String waitingFor, String why) {}

    /** The group of each row, in the order the card shows them. */
    public static final List<Row> ALL = List.of(
        active(QbParams.FEE_CREDIT_GRANT_ENABLED, "grant", true,
            "the sponsors' onboarding platform fee Kudos is given and used first (declared off until the prices are set)"),
        keep(QbParams.FEE_CREDIT_GRANT_BASIC, "grant"),
        keep(QbParams.FEE_CREDIT_GRANT_CERTIFIED, "grant"),
        keep(QbParams.FEE_CREDIT_VALID_DAYS, "grant"),
        active(QbParams.HELP_ENABLED, "help", true,
            "the AI help is open to Takers and Sponsors at the launch (declared off until the AI notice is published)"),
        keep(QbParams.HELP_USER_DAILY, "help"),
        keep(QbParams.HELP_GLOBAL_MONTHLY_TOKENS, "help"),
        active(QbParams.AI_ENABLED, "ai", true,
            "AI quiz generation and translation are open at the launch; the proposal is published after the AI notice"),
        active(QbParams.FEE_MONTHLY, "fees", "0", null),
        active(QbParams.CREATOR_FEE_RATE, "fees", "0", null),
        active(QbParams.TRANSFER_FEE_RATE, "fees", "0", null),
        active(QbParams.TOPUP_TARGETS, "topup", "CASH",
            "a top-up goes into the cash account only: Kudos are not for use at the launch"),
        active(QbParams.TOPUP_DEFAULT_TARGET, "topup", "CASH",
            "the default must be among the targets offered, or a top-up that names none has nowhere to go"),
        active(QbParams.KUDOS_BUY_ENABLED, "kudos", false,
            "no cash into Kudos at the launch (declared on: it is what the tests rely on)"),
        active(QbParams.KUDOS_SELL_ENABLED, "kudos", false, null),
        active(QbParams.TRANSFER_ENABLED, "kudos", false, null),
        active(QbParams.WITHDRAWAL_BANK_ENABLED, "kudos", false, null),
        active(QbParams.REWARD_KUDOS_ENABLED, "kudos", false,
            "no publication offers a Kudos reward at the launch (declared on: today's behaviour)"),
        active(QbParams.REWARD_DEFAULT_METHODS, "kudos", "", null),
        // Kudos are only shown at the launch, not used: an entry fee is paid in Kudos, so a sponsor may not set one.
        active(QbParams.ENTRY_FEE_ENABLED, "kudos", false, null),
        // The charge groups of F-M: their rows turn ACTIVE in the pull request that adds the key (F-F10 plan, 2).
        // F-M6: on at the launch once the requester has confirmed the floor of the price per start (its least value
        // qb.distribution.unit-floor.min is a placeholder, 1 yen); the operators' prices are their own (no row).
        active(QbParams.DISTRIBUTION_BILLING_ENABLED, "distribution", true,
            "publications pay the distribution fee at the launch (declared off: the tests rely on it); the requester "
                + "confirms qb.distribution.unit-floor.min before the proposal is published"),
        keep(QbParams.DISTRIBUTION_UNIT_FLOOR_MIN, "distribution"),
        // F-M7 (§10.3-13): on at the launch, then whether an early end gives back the unused days, which the requester
        // decides apart (off: no refund is promised, the wizard says so).
        active(QbParams.AUDIENCE_BILLING_ENABLED, "audience", true,
            "publications pay for the audience rules they use at the launch (declared off: the tests rely on it)"),
        keep(QbParams.AUDIENCE_REFUND_UNUSED_DAYS, "audience"),
        // F-M8: on at the launch once the requester has confirmed the placeholders: the range of the operators' prices
        // qb.exposure.unit.min / .max, the fewest exposures bought qb.exposure.min-purchase and the most a top-up buys
        // qb.exposure.top-up.max. An operator without a price charges nothing, so each operator sets its own price
        // before the first publication that uses another's video (launch checklist).
        active(QbParams.EXPOSURE_BILLING_ENABLED, "exposure", true,
            "publications that use another's video pay for their exposures at the launch (declared off: the tests rely "
                + "on it); the requester confirms the range of the prices, the fewest bought and the biggest top-up first"),
        keep(QbParams.EXPOSURE_UNIT_MIN, "exposure"),
        keep(QbParams.EXPOSURE_UNIT_MAX, "exposure"),
        keep(QbParams.EXPOSURE_MIN_PURCHASE, "exposure"),
        keep(QbParams.EXPOSURE_TOP_UP_MAX, "exposure"),
        // F-M9a-1's key exists (declared off, controlled): requests are only charged once a-2 can charge the month's fees.
        waiting(QbParams.AI_FEE_ENABLED, "aifee", true, "F-M9"),
        // F-C7a's key exists (declared off, controlled) but drafting from a website is only charged once F-M9 can
        // charge the AI credit: it stays off at the launch and its row turns ACTIVE with F-M9's pull request.
        waiting(QbParams.SITE_PROFILE_ENABLED, "aifee", true, "F-M9"),
        waiting("qb.fee.auto-pay.enabled", "autopay", true, "F-M13"),
        manual(QbParams.FEE_CREDIT_MANUAL_MAX, "grant"),
        manual(QbParams.TREE_GRANT_MAX_PER_NODE, "grant"));

    /**
     * The keys that are already in {@link QbParams#ALL} while their row still {@link Status#WAITING}: the key exists,
     * what it means does not do yet. Any other waiting key found in {@code ALL} is a feature that forgot its row.
     */
    public static final Set<String> KEY_BEFORE_MEANING = Set.of(QbParams.SITE_PROFILE_ENABLED,
        QbParams.AI_FEE_ENABLED);

    /** Whether a system of this {@code jabiz.web.environment} proposes the first launch's values. */
    public static boolean appliesTo(String environment) {
        return environment != null && ENVIRONMENTS.contains(environment.trim().toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * The value {@code QB_SETUP} proposes for the parameter: the first launch's when {@code firstLaunch} and the row is
     * {@link Status#ACTIVE} with a value, else the declared one.
     */
    public static Object valueFor(QbParams.Param param, boolean firstLaunch) {
        if (!firstLaunch) {
            return param.value();
        }
        return row(param.key()).filter(row -> row.status() == Status.ACTIVE && row.value() != null)
            .map(Row::value).orElse(param.value());
    }

    public static Optional<Row> row(String key) {
        return ALL.stream().filter(row -> row.key().equals(key)).findFirst();
    }

    private static Row active(String key, String group, Object value, String why) {
        return new Row(key, group, Status.ACTIVE, value, null, why);
    }

    private static Row keep(String key, String group) {
        return new Row(key, group, Status.ACTIVE, null, null, null);
    }

    private static Row waiting(String key, String group, Object intent, String feature) {
        return new Row(key, group, Status.WAITING, intent, feature, null);
    }

    private static Row manual(String key, String group) {
        return new Row(key, group, Status.MANUAL, null, null, null);
    }

    private FirstLaunchSwitches() {}
}
