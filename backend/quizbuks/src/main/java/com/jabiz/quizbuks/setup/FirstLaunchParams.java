package com.jabiz.quizbuks.setup;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The parameters a first launch's configuration may set (F-LAUNCH, docs/quizbuks/plans/impl/F-LAUNCH.md section 4, H4).
 * Pure data, read by the first launch's handler when it checks the configuration and lists what may be set:
 * <ul>
 *   <li>{@link #SETTABLE}: neither money nor an amount; the configured value takes effect, if its
 *       {@link #VALUE_RULES rule} allows it;</li>
 *   <li>a money switch of {@link FirstLaunchSwitches}, and a key of {@link #FIXED_VALUES} (the publications' review),
 *       is {@link Verdict#FIXED} at its first launch's value ({@link #fixedValue}): the configuration cannot set it,
 *       whatever the value;</li>
 *   <li>an amount (a price, a limit in Kudos or yen, a rate) is {@link Verdict#AMOUNT}: refused;</li>
 *   <li>anything else is {@link Verdict#NOT_SETTABLE}: refused.</li>
 * </ul>
 * A key is added to {@link #SETTABLE} only when it moves no money and a wrong value is undone by an ordinary change.
 */
public final class FirstLaunchParams {

    /**
     * What the first launch's configuration may do with a parameter; {@link #VALUE_REFUSED} only for a settable key
     * whose {@link #VALUE_RULES rule} refuses the configured value.
     */
    public enum Verdict { SETTABLE, VALUE_REFUSED, FIXED, AMOUNT, NOT_SETTABLE }

    /** The keys the configuration may set, with the configured value taking effect. */
    public static final List<String> SETTABLE = Stream.of(List.of(
            // The review of a sponsor's onboarding (the first launch's own sponsors are approved by the run itself).
            QbParams.REVIEW_SPONSOR_AUTO,
            // AI quiz generation's daily requests per sponsor and how long its candidates are kept.
            QbParams.AI_SPONSOR_DAILY_JOBS,
            QbParams.AI_EXPIRE_DAYS,
            // The oldest app version still allowed, and the support address shown to users.
            QbParams.APP_MIN_VERSION,
            QbParams.SUPPORT_EMAIL,
            // How far ahead a new legal text may be announced.
            QbParams.LEGAL_MAX_ANNOUNCE_DAYS,
            // The methods every sponsor may publish with: offline redemption only at the launch (VALUE_RULES).
            QbParams.REWARD_DEFAULT_METHODS),
        // The receipts' issuer and the accounting system's account codes: the requester's facts, no money moves.
        QbParams.ISSUER, QbParams.JOURNAL_ACCOUNTS)
        .flatMap(List::stream).toList();

    /**
     * Rules on the configured value of a settable key; a key without one takes any value of its kind. The first launch
     * uses offline redemption only (plan section 4), so the default reward methods are none or {@code OFFLINE_REDEEM}.
     */
    public static final Map<String, Predicate<String>> VALUE_RULES = Map.of(
        QbParams.REWARD_DEFAULT_METHODS, value -> value == null || value.isBlank()
            || "OFFLINE_REDEEM".equals(value.strip()));

    /**
     * Keys that are not money switches but are {@link Verdict#FIXED} all the same, with their first launch's value:
     * publications are always reviewed before they go live (the requester, 2026-10-28; operations.md, the first stage's
     * settings). The value is the parameter's declared one, so the configuration has nothing to add.
     */
    public static final Map<String, Object> FIXED_VALUES = Map.of(QbParams.REVIEW_PUBLICATION_AUTO, false);

    private static final Map<String, QbParams.Param> PARAMS = QbParams.ALL.stream()
        .collect(Collectors.toUnmodifiableMap(QbParams.Param::key, Function.identity()));

    /** What the configuration may do with the parameter {@code key}. */
    public static Verdict verdict(String key) {
        if (key == null) {
            return Verdict.NOT_SETTABLE;
        }
        QbParams.Param param = PARAMS.get(key);
        if (param != null && amount(param)) {
            return Verdict.AMOUNT;
        }
        if (FIXED_VALUES.containsKey(key)) {
            return Verdict.FIXED;
        }
        if (param != null && SETTABLE.contains(key)) {
            return Verdict.SETTABLE;
        }
        Optional<FirstLaunchSwitches.Row> row = FirstLaunchSwitches.row(key);
        if (row.isPresent()) {
            return row.get().status() == FirstLaunchSwitches.Status.MANUAL ? Verdict.NOT_SETTABLE : Verdict.FIXED;
        }
        return Verdict.NOT_SETTABLE;
    }

    /** What the configuration may do with the parameter {@code key} set to {@code value} (text, as configured). */
    public static Verdict verdict(String key, String value) {
        Verdict verdict = verdict(key);
        if (verdict == Verdict.SETTABLE && VALUE_RULES.containsKey(key) && !VALUE_RULES.get(key).test(value)) {
            return Verdict.VALUE_REFUSED;
        }
        return verdict;
    }

    /**
     * The value a {@link Verdict#FIXED} money switch has at the first launch (empty for every other key, and for a row
     * whose declared value stands).
     */
    public static Optional<Object> fixedValue(String key) {
        if (verdict(key) != Verdict.FIXED) {
            return Optional.empty();
        }
        if (FIXED_VALUES.containsKey(key)) {
            return Optional.of(FIXED_VALUES.get(key));
        }
        return FirstLaunchSwitches.row(key)
            .filter(row -> row.status() == FirstLaunchSwitches.Status.ACTIVE && row.value() != null)
            .map(FirstLaunchSwitches.Row::value);
    }

    /**
     * Whether the parameter is an amount: money ({@code monetary}, Kudos or yen) or a number with decimals (rates,
     * prices per start, dollars).
     */
    static boolean amount(QbParams.Param param) {
        Object type = param.valueKind().get("type");
        if ("monetary".equals(type)) {
            return true;
        }
        return "numeric".equals(type) && param.valueKind().get("scale") instanceof Number scale
            && scale.intValue() > 0;
    }

    private FirstLaunchParams() {}
}
