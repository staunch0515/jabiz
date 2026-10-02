package com.jabiz.finance.calc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Proposes which book items a statement line is (FIN-BK-004): pure, so the same lines and items always give the same
 * proposals, which a person then accepts or not.
 * <ul>
 *   <li>A candidate has the line's amount, alone or as the total of one payment run's payments of one day (the
 *       bank's single debit for a batch), and lies within the window of days around the line; a check whose number
 *       the line names may lie further back, since checks are cashed late.</li>
 *   <li>Its confidence starts at 50 for the amount and gains for the day (20 the same day, less the further apart),
 *       for the check number (25) and for the payer's or payee's name in the line's text (10, 15 for two words or
 *       more); each gain is a reason.</li>
 *   <li>Lines and items are given out best first, each once, in the order of their days and ids whatever order they
 *       come in. A proposal with others as good, for its line or for its items, is made at a confidence 30 lower,
 *       saying how many others are alike.</li>
 * </ul>
 */
public final class BankMatcher {

    /** A statement line, its amount as the bank sees it (deposits positive). */
    public record Line(String id, LocalDate date, BigDecimal amount, String reference, String description) {
        public Line {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(date, "date");
            Objects.requireNonNull(amount, "amount");
        }
    }

    /**
     * A book item, its amount as the cash account has it (receipts positive).
     *
     * @param kind  {@code LEDGER} or {@code OPENING}
     * @param party the customer or payee
     * @param group the payment run of a payment, which the bank debits as one
     */
    public record Item(String kind, String id, LocalDate date, BigDecimal amount, String documentNo, String party,
        String checkNo, String group) {
        public Item {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(date, "date");
            Objects.requireNonNull(amount, "amount");
        }
    }

    /** What matching proposes: one line, its items, how sure and why. */
    public record Proposal(Line line, List<Item> items, int confidence, List<String> reasons, int alike) {
        public Proposal {
            items = List.copyOf(items);
            reasons = List.copyOf(reasons);
        }
    }

    private record Candidate(Line line, List<Item> items, int score, List<String> reasons) {}

    private static final Pattern CHECK = Pattern.compile("\\b(?:CHECK|CHK|CHEQUE)\\s*(?:NO\\.?|#)?\\s*-?\\s*(\\d{1,10})\\b");
    /** Words that say nothing about who paid or was paid. */
    private static final Set<String> NOISE = Set.of("THE", "AND", "INC", "LLC", "LTD", "CORP", "CORPORATION", "CO",
        "COMPANY", "DEPOSIT", "ACH", "DEBIT", "CREDIT", "PAYMENT", "WIRE", "TRANSFER", "CHECK", "BATCH", "FROM",
        "FOR", "OF");

    private BankMatcher() {}

    public static List<Proposal> propose(List<Line> unsorted, List<Item> unsortedItems, int windowDays) {
        List<Line> lines = unsorted.stream().sorted(Comparator.comparing(Line::date).thenComparing(Line::id))
            .toList();
        List<Item> items = unsortedItems.stream().sorted(Comparator.comparing(Item::date)
            .thenComparing(Item::kind).thenComparing(Item::id)).toList();
        List<List<Item>> choices = new ArrayList<>();
        items.forEach(item -> choices.add(List.of(item)));
        choices.addAll(batches(items));
        List<Candidate> candidates = new ArrayList<>();
        Map<String, List<Candidate>> byLine = new LinkedHashMap<>();
        for (Line line : lines) {
            List<Candidate> own = new ArrayList<>();
            for (List<Item> choice : choices) {
                Candidate candidate = score(line, choice, windowDays);
                if (candidate != null) {
                    own.add(candidate);
                }
            }
            own.sort(Comparator.comparingInt(Candidate::score).reversed());
            byLine.put(line.id(), own);
            candidates.addAll(own);
        }
        candidates.sort(Comparator.comparingInt(Candidate::score).reversed()
            .thenComparing(c -> c.line().date()).thenComparing(c -> c.line().id())
            .thenComparing(c -> c.items().getFirst().date()).thenComparing(c -> c.items().getFirst().id()));
        Set<String> usedLines = new HashSet<>();
        Set<String> usedItems = new HashSet<>();
        List<Proposal> proposals = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (usedLines.contains(candidate.line().id())
                || candidate.items().stream().anyMatch(i -> usedItems.contains(key(i)))) {
                continue;
            }
            // As good for this line, or another line as good for these items.
            Set<String> mine = new HashSet<>();
            candidate.items().forEach(i -> mine.add(key(i)));
            int alike = (int) candidates.stream()
                .filter(other -> other != candidate && other.score() == candidate.score()
                    && other.items().stream().noneMatch(i -> usedItems.contains(key(i)))
                    && !usedLines.contains(other.line().id())
                    && (other.line().id().equals(candidate.line().id())
                        || other.items().stream().anyMatch(i -> mine.contains(key(i)))))
                .count();
            int confidence = candidate.score();
            List<String> reasons = new ArrayList<>(candidate.reasons());
            if (alike > 0) {
                confidence = Math.max(10, confidence - 30);
                reasons.add(alike + (alike == 1 ? " other is" : " others are") + " alike");
            }
            usedLines.add(candidate.line().id());
            candidate.items().forEach(i -> usedItems.add(key(i)));
            proposals.add(new Proposal(candidate.line(), candidate.items(), Math.min(100, confidence), reasons,
                alike));
        }
        proposals.sort(Comparator.comparing((Proposal p) -> p.line().date()).thenComparing(p -> p.line().id()));
        return List.copyOf(proposals);
    }

    /** The payments of one run and one day, two or more: the bank's one debit. */
    static List<List<Item>> batches(List<Item> items) {
        Map<String, List<Item>> groups = new LinkedHashMap<>();
        for (Item item : items) {
            if (item.group() != null && !item.group().isBlank()) {
                groups.computeIfAbsent(item.group() + "|" + item.date(), k -> new ArrayList<>()).add(item);
            }
        }
        return groups.values().stream().filter(g -> g.size() > 1).map(List::copyOf).toList();
    }

    private static Candidate score(Line line, List<Item> choice, int windowDays) {
        BigDecimal total = choice.stream().map(Item::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.compareTo(line.amount()) != 0) {
            return null;
        }
        Item first = choice.getFirst();
        long days = Math.abs(ChronoUnit.DAYS.between(first.date(), line.date()));
        String text = text(line);
        String lineCheck = checkNumber(text);
        boolean check = choice.size() == 1 && lineCheck != null && lineCheck.equals(checkOf(first));
        boolean late = !first.date().isAfter(line.date().plusDays(windowDays));
        if (days > windowDays && !(check && late)) {
            return null;
        }
        int score = 50;
        List<String> reasons = new ArrayList<>();
        reasons.add(choice.size() == 1 ? "amount equal" : "batch " + first.group() + " of " + choice.size()
            + " payments adds up");
        if (days == 0) {
            score += 20;
            reasons.add("same day");
        } else if (days <= windowDays) {
            score += Math.max(0, (int) (20 * (windowDays - days) / Math.max(1, windowDays + 1)));
            reasons.add(days + (days == 1 ? " day" : " days") + " apart");
        } else {
            reasons.add(days + " days apart");
        }
        if (check) {
            score += 25;
            reasons.add("check " + lineCheck);
        }
        if (choice.size() == 1 && first.party() != null) {
            List<String> shared = sharedWords(text, first.party());
            if (!shared.isEmpty()) {
                score += shared.size() > 1 ? 15 : 10;
                reasons.add("name " + String.join(" ", shared));
            }
        }
        return new Candidate(line, choice, Math.min(100, score), reasons);
    }

    /** The number of a check the text names ("CHECK 1045", "CHK-1045"), without leading zeros. */
    static String checkNumber(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = CHECK.matcher(text.toUpperCase(Locale.ROOT));
        return m.find() ? new BigDecimal(m.group(1)).toPlainString() : null;
    }

    private static String checkOf(Item item) {
        if (item.checkNo() != null && item.checkNo().matches("\\d{1,10}")) {
            return new BigDecimal(item.checkNo()).toPlainString();
        }
        return checkNumber(item.documentNo());
    }

    static List<String> sharedWords(String text, String party) {
        Set<String> lineWords = words(text);
        List<String> shared = new ArrayList<>();
        for (String word : words(party)) {
            if (lineWords.contains(word)) {
                shared.add(word);
            }
        }
        return shared;
    }

    private static Set<String> words(String text) {
        Set<String> words = new java.util.LinkedHashSet<>();
        if (text == null) {
            return words;
        }
        for (String word : text.toUpperCase(Locale.ROOT).split("[^A-Z0-9]+")) {
            if (word.length() >= 3 && !NOISE.contains(word) && !word.chars().allMatch(Character::isDigit)) {
                words.add(word);
            }
        }
        return words;
    }

    private static String text(Line line) {
        return ((line.description() == null ? "" : line.description()) + " "
            + (line.reference() == null ? "" : line.reference())).trim();
    }

    private static String key(Item item) {
        return item.kind() + ":" + item.id();
    }
}
