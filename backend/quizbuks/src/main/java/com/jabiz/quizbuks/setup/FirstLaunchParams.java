package com.jabiz.quizbuks.setup;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The parameters a first launch's configuration may set (F-LAUNCH, docs/quizbuks/plans/impl/F-LAUNCH.md section 4, H4).
 * Pure data, read by the first launch's handler when it checks the configuration and lists what may be set:
 * <ul>
 *   <li>{@link #SETTABLE}: neither money nor an amount; the configured value takes effect;</li>
 *   <li>a money switch of {@link FirstLaunchSwitches} is {@link Verdict#FIXED} at its first launch's value
 *       ({@link #fixedValue}): the configuration cannot change it, money parameters change only by four eyes;</li>
 *   <li>an amount (a price, a limit in Kudos or yen, a rate) is {@link Verdict#AMOUNT}: refused;</li>
 *   <li>anything else is {@link Verdict#NOT_SETTABLE}: refused.</li>
 * </ul>
 * A key is added to {@link #SETTABLE} only when it moves no money and a wrong value is undone by an ordinary change.
 */
public final class FirstLaunchParams {

    /** What the first launch's configuration may do with a parameter. */
    public enum Verdict { SETTABLE, FIXED, AMOUNT, NOT_SETTABLE }

    /** The keys the configuration may set, with the configured value taking effect. */
    public static final List<String> SETTABLE = List.of(
        // The review of a sponsor's onboarding (the first launch's own sponsors are approved by the run itself).
        QbParams.REVIEW_SPONSOR_AUTO,
        // AI quiz generation's daily requests per sponsor and how long its candidates are kept.
        QbParams.AI_SPONSOR_DAILY_JOBS,
        QbParams.AI_EXPIRE_DAYS,
        // The oldest app version still allowed, and the support address shown to users.
        QbParams.APP_MIN_VERSION,
        QbParams.SUPPORT_EMAIL);

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
        Optional<FirstLaunchSwitches.Row> row = FirstLaunchSwitches.row(key);
        if (row.isPresent()) {
            return row.get().status() == FirstLaunchSwitches.Status.MANUAL ? Verdict.NOT_SETTABLE : Verdict.FIXED;
        }
        return param != null && SETTABLE.contains(key) ? Verdict.SETTABLE : Verdict.NOT_SETTABLE;
    }

    /**
     * The value a {@link Verdict#FIXED} money switch has at the first launch (empty for every other key, and for a row
     * whose declared value stands).
     */
    public static Optional<Object> fixedValue(String key) {
        if (verdict(key) != Verdict.FIXED) {
            return Optional.empty();
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
