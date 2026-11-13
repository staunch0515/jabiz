package com.jabiz.quizbuks.operator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The arithmetic of an operator's share of the platform fees in pure Java (docs/quizbuks/plans/impl/F-M12.md §3.2 –
 * §3.4): which part of a fee enters the base, how a payment made with granted credit is cut into the fees it paid
 * (with tax, before tax, the part inside the base), what the operator bears of it (A: the platform bears it, so that
 * part of the fee is not shared in and nothing is taken back; B: the operator bears it, so the fee is shared as usual
 * and the credit is taken back), and how what is taken back is paid or carried. All amounts are whole yen; every
 * rounding says which way it goes.
 *
 * <p>The cumulative rounding is the point of {@link ChargeGifts}: the credit used on one fee is cut into rows over
 * time, each row the cumulative figure rounded down less what the rows before it already gave, so that the rows of a
 * fee always add up to the cumulative figure and a fee paid in full by credit gives exactly its whole amount.
 */
public final class OperatorShareRules {

    /** A: the platform bears the credit used (that part of the fee is not shared in; nothing is taken back). */
    public static final String PLATFORM = "PLATFORM";
    /** B: the operator bears it (the fee is shared in as usual; the credit used is taken back from the share). */
    public static final String OPERATOR = "OPERATOR";
    /** The two, in the order the parameter's dictionary shows them. There is no third (both): F-M12 question 1. */
    public static final List<String> TREATMENTS = List.of(PLATFORM, OPERATOR);

    /** The kind of an AI fee: only its mark-up is the operator's to share in, when it is shared in at all. */
    public static final String AI = "AI";

    /** The kinds of fee that enter the base unless set otherwise (no AI fee). */
    public static final List<String> DEFAULT_KINDS = List.of("MONTHLY", "MANUAL", "AUDIENCE", "DISTRIBUTION",
        "EXPOSURE");

    private OperatorShareRules() {}

    /**
     * What the settlements read of the parameters.
     *
     * @param shareKinds         the kinds of fee that enter the base
     * @param aiCostInBase       whether the cost part of an AI fee enters the base too
     * @param recoverOutsideBase whether the credit used on a kind outside {@code shareKinds} is taken back too, for
     *                           the part of the fee that is not cost
     * @param recoveryRate       the part of the credit used that is taken back, 0 to 1
     * @param treatments         by the grant's source, {@link #PLATFORM} or {@link #OPERATOR}
     */
    public record Terms(Set<String> shareKinds, boolean aiCostInBase, boolean recoverOutsideBase,
        BigDecimal recoveryRate, Map<String, String> treatments) {

        /** The version of {@link #toText()}. */
        static final String VERSION = "v1:";

        public Terms {
            shareKinds = Set.copyOf(shareKinds);
            treatments = Map.copyOf(treatments);
        }

        /**
         * The terms as text, to keep with a capped settlement (the fees it left are counted later at them): {@code
         * v1:kinds|aiCost|outside|rate|SOURCE=TREATMENT,...}, in a fixed order. The version prefix lets the format
         * grow; {@link #fromText} still reads the text without one (the first form).
         */
        public String toText() {
            String treated = new java.util.TreeMap<>(treatments).entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue()).collect(java.util.stream.Collectors.joining(","));
            return VERSION + String.join("|", String.join(",", new java.util.TreeSet<>(shareKinds)),
                String.valueOf(aiCostInBase),
                String.valueOf(recoverOutsideBase), recoveryRate.toPlainString(), treated);
        }

        /** The terms {@link #toText()} wrote. */
        public static Terms fromText(String text) {
            String body = text.startsWith(VERSION) ? text.substring(VERSION.length()) : text;
            if (body.matches("v\\d+:.*")) {
                throw new IllegalArgumentException("Terms text of a version this code does not read: " + text);
            }
            String[] parts = body.split("\\|", -1);
            Map<String, String> treated = new java.util.LinkedHashMap<>();
            if (!parts[4].isEmpty()) {
                for (String pair : parts[4].split(",")) {
                    String[] kv = pair.split("=", 2);
                    treated.put(kv[0], kv[1]);
                }
            }
            return new Terms(parseKinds(parts[0]), Boolean.parseBoolean(parts[1]), Boolean.parseBoolean(parts[2]),
                new BigDecimal(parts[3]), treated);
        }

        /** A grant of a source this has no word on is borne by the platform. */
        public String treatmentOf(String grantSource) {
            String treatment = grantSource == null ? null : treatments.get(grantSource);
            return OPERATOR.equals(treatment) ? OPERATOR : PLATFORM;
        }
    }

    /** The kinds a comma-separated parameter names, upper case, in order, without blanks and repeats. */
    public static Set<String> parseKinds(String text) {
        Set<String> kinds = new LinkedHashSet<>();
        if (text != null) {
            Arrays.stream(text.split(",")).map(String::strip).filter(kind -> !kind.isEmpty())
                .map(kind -> kind.toUpperCase(java.util.Locale.ROOT)).forEach(kinds::add);
        }
        return kinds;
    }

    /**
     * Whether a kinds list is usable: at least one kind, each of letters and underscores, comma-separated; letters in
     * either case (the list is read case-insensitively and turned to capitals, {@link #parseKinds}).
     */
    public static boolean validKinds(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (String kind : text.split(",", -1)) {
            if (!kind.strip().matches("[A-Za-z][A-Za-z_]*")) {
                return false;
            }
        }
        return true;
    }

    /** Whether a parameter value is one of the two treatments. */
    public static boolean treatment(String value) {
        return TREATMENTS.contains(value);
    }

    // ---- the base ---------------------------------------------------------------------------------------------------

    /**
     * The part of a fee (before tax) that stays out of the base: all of it for a kind outside the list (the line is
     * still written, so that adding the kind later does not sweep the history in), else the cost part the charger
     * stated; an AI fee's cost part enters when {@link Terms#aiCostInBase()} says so.
     */
    public static BigDecimal excluded(String kind, BigDecimal amount, BigDecimal shareExcluded, Terms terms) {
        if (!terms.shareKinds().contains(kind)) {
            return amount;
        }
        if (AI.equals(kind) && terms.aiCostInBase()) {
            return BigDecimal.ZERO;
        }
        return clamp(shareExcluded, amount);
    }

    /**
     * The part of a fee (before tax) whose credit used is taken back when the operator bears it: the part in the
     * base; for a kind outside the list, the part that is not cost when {@link Terms#recoverOutsideBase()} is on,
     * else nothing (the operator never had a share of it).
     */
    public static BigDecimal recoverBase(String kind, BigDecimal amount, BigDecimal shareExcluded, Terms terms) {
        if (terms.shareKinds().contains(kind)) {
            return amount.subtract(excluded(kind, amount, shareExcluded, terms));
        }
        return terms.recoverOutsideBase() ? amount.subtract(clamp(shareExcluded, amount)) : BigDecimal.ZERO;
    }

    private static BigDecimal clamp(BigDecimal value, BigDecimal amount) {
        BigDecimal given = value == null ? BigDecimal.ZERO : value;
        return given.max(BigDecimal.ZERO).min(amount);
    }

    // ---- cutting a payment into the fees it paid ----------------------------------------------------------------------

    /** ⌊value × numerator ÷ denominator⌋ in whole yen, rounded toward negative; 0 without a denominator. */
    public static BigDecimal ratioFloor(BigDecimal value, BigDecimal numerator, BigDecimal denominator) {
        if (denominator.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return value.multiply(numerator).divide(denominator, 0, RoundingMode.FLOOR);
    }

    /**
     * {@code total} cut by the largest remainder over fees of the given weights (their totals with tax): each fee gets
     * the whole yen of its share, the yen left over go one each to the shares with the largest remainders, ties to
     * the first in {@code order} (the fees' ids). The parts add up to {@code total} exactly, for a negative total
     * (given back) as for a positive.
     */
    /** Whether {@link #split} can cut over these weights: their sum is positive. */
    public static boolean splittable(List<BigDecimal> weights) {
        return weights.stream().reduce(BigDecimal.ZERO, BigDecimal::add).signum() > 0;
    }

    public static List<BigDecimal> split(BigDecimal total, List<BigDecimal> weights, List<String> order) {
        if (weights.size() != order.size()) {
            throw new IllegalArgumentException("A weight and an order key for each part");
        }
        int size = weights.size();
        if (size == 0) {
            return List.of();
        }
        BigDecimal sum = weights.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.signum() <= 0) {
            throw new IllegalArgumentException("The weights are not positive");
        }
        BigDecimal magnitude = total.abs();
        BigDecimal[] parts = new BigDecimal[size];
        BigDecimal[] remainders = new BigDecimal[size];
        BigDecimal given = BigDecimal.ZERO;
        for (int i = 0; i < size; i++) {
            BigDecimal exact = magnitude.multiply(weights.get(i));
            parts[i] = exact.divide(sum, 0, RoundingMode.FLOOR);
            remainders[i] = exact.subtract(parts[i].multiply(sum));
            given = given.add(parts[i]);
        }
        int left = magnitude.subtract(given).intValueExact();
        List<Integer> byRemainder = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            byRemainder.add(i);
        }
        byRemainder.sort(Comparator.<Integer, BigDecimal>comparing(i -> remainders[i]).reversed()
            .thenComparing(order::get));
        for (int i = 0; i < left; i++) {
            int index = byRemainder.get(i);
            parts[index] = parts[index].add(BigDecimal.ONE);
        }
        List<BigDecimal> signed = new ArrayList<>(size);
        for (BigDecimal part : parts) {
            signed.add(total.signum() < 0 ? part.negate() : part);
        }
        return List.copyOf(signed);
    }

    /** What one row of the credit used on a fee comes to. */
    public record Gift(BigDecimal amount, BigDecimal inBase, BigDecimal recoverGross) {}

    /**
     * The credit used on one fee, row by row. Each figure is the cumulative one rounded down less what the rows before
     * it gave, so the rows of a fee add up to the cumulative figure whatever order they come in over time:
     * <ul>
     *   <li>{@code amount}: the credit (with tax) as a part of the fee before tax, ⌊cumulative credit × amount ÷
     *       total⌋;</li>
     *   <li>{@code inBase}: of that, the part that falls on the part of the fee in the base, ⌊cumulative amount ×
     *       in-base part ÷ amount⌋;</li>
     *   <li>{@code recoverGross}: the credit (with tax) the operator bears, in the proportion of the part to take
     *       back, ⌊cumulative credit the operator bears × that part ÷ amount⌋.</li>
     * </ul>
     */
    public static final class ChargeGifts {

        private final BigDecimal amount;
        private final BigDecimal total;
        private final BigDecimal inBaseAmount;
        private final BigDecimal recoverBase;
        private BigDecimal gross;
        private BigDecimal operatorGross;
        private BigDecimal given;
        private BigDecimal givenInBase;
        private BigDecimal givenRecover;

        /**
         * @param amount       the fee before tax; {@code total} with tax
         * @param inBaseAmount the part of the fee in the base ({@link #excluded} taken off)
         * @param recoverBase  the part to take back ({@link #recoverBase})
         * @param priorGross   what the rows already written for this fee gave: credit with tax, of which the operator
         *                     bears, before tax, in the base, taken back
         */
        public ChargeGifts(BigDecimal amount, BigDecimal total, BigDecimal inBaseAmount, BigDecimal recoverBase,
            BigDecimal priorGross, BigDecimal priorOperatorGross, BigDecimal priorAmount, BigDecimal priorInBase,
            BigDecimal priorRecover) {
            this.amount = amount;
            this.total = total;
            this.inBaseAmount = inBaseAmount;
            this.recoverBase = recoverBase;
            this.gross = priorGross;
            this.operatorGross = priorOperatorGross;
            this.given = priorAmount;
            this.givenInBase = priorInBase;
            this.givenRecover = priorRecover;
        }

        /** The next row: {@code credit} with tax, signed (negative: given back). */
        public Gift next(BigDecimal credit, boolean operatorBears) {
            gross = gross.add(credit);
            if (operatorBears) {
                operatorGross = operatorGross.add(credit);
            }
            BigDecimal cumulativeAmount = ratioFloor(gross, amount, total);
            BigDecimal cumulativeInBase = ratioFloor(cumulativeAmount, inBaseAmount, amount);
            BigDecimal cumulativeRecover = ratioFloor(operatorGross, recoverBase, amount);
            Gift gift = new Gift(cumulativeAmount.subtract(given), cumulativeInBase.subtract(givenInBase),
                cumulativeRecover.subtract(givenRecover));
            given = cumulativeAmount;
            givenInBase = cumulativeInBase;
            givenRecover = cumulativeRecover;
            return gift;
        }
    }

    // ---- what the operator keeps and gives back -----------------------------------------------------------------------

    /**
     * What the operator was (or is) given of {@code base} before tax: {@code base × (1 − the platform's share)}, not
     * yet rounded; sums of these are rounded down once ({@link #floor}), so that a month's rows lose at most one yen.
     */
    public static BigDecimal exactShare(BigDecimal base, BigDecimal platformShare) {
        return base.multiply(BigDecimal.ONE.subtract(platformShare));
    }

    /** Whole yen, rounded toward negative: for a cost, less of it taken; for a refund, more given back. */
    public static BigDecimal floor(BigDecimal value) {
        return value.setScale(0, RoundingMode.FLOOR);
    }

    /** What is taken back for the credit used: Σ (credit the operator bears × the rate of the settlement that counted the fee). */
    public static BigDecimal due(BigDecimal exactSum) {
        return floor(exactSum);
    }

    /**
     * @param recovered  taken from what is payable this month; negative: given back to the operator
     * @param carriedOut what remains to take next month
     * @param costOffset of a refund (negative {@code recovered}), what pays the cost carried instead of being paid
     *                   out: the refund first reduces what the operator owes, and only the rest is payable
     */
    public record Recovery(BigDecimal recovered, BigDecimal carriedOut, BigDecimal costOffset) {}

    /**
     * Takes back what is due and what was carried in, from what is left of the share after its other costs
     * ({@code available}, never below 0); what does not fit is carried. A negative total (more given back than used)
     * is not carried but returned to the operator: {@code recovered} is negative, and the refund first pays the cost
     * carried ({@code costCarry}, F-M12 §3.5: net within the month), only what is left adds to what is payable. A
     * settlement never makes what is payable negative.
     */
    public static Recovery recover(BigDecimal due, BigDecimal carriedIn, BigDecimal available, BigDecimal costCarry) {
        BigDecimal owed = due.add(carriedIn);
        if (owed.signum() < 0) {
            return new Recovery(owed, BigDecimal.ZERO, owed.negate().min(costCarry.max(BigDecimal.ZERO)));
        }
        BigDecimal recovered = owed.min(available.max(BigDecimal.ZERO));
        return new Recovery(recovered, owed.subtract(recovered), BigDecimal.ZERO);
    }

    /**
     * What a base below 0 costs the operator: its share of the negative part, taken as a cost (positive), rounded
     * down; 0 for a base that is not negative.
     */
    public static BigDecimal negativeBaseCost(BigDecimal netBase, BigDecimal platformShare) {
        if (netBase.signum() >= 0) {
            return BigDecimal.ZERO;
        }
        return floor(exactShare(netBase.negate(), platformShare));
    }

    /**
     * The part of a credit note (before tax) that entered the base: the note's amount in the proportion the fee's
     * CHARGE line had in the base; not rounded (sums are rounded down once).
     */
    public static BigDecimal noteInBase(BigDecimal noteAmount, BigDecimal feeAmount, BigDecimal excluded) {
        if (feeAmount.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return noteAmount.multiply(feeAmount.subtract(excluded)).divide(feeAmount, 8, RoundingMode.FLOOR);
    }
}
