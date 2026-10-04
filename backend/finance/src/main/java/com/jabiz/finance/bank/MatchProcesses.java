package com.jabiz.finance.bank;

import com.jabiz.entity.Violation;
import com.jabiz.finance.FinancePermissions;
import com.jabiz.finance.calc.BankMatcher;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.RunTemplate;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.jabiz.finance.bank.BankAccountProcesses.code;
import static com.jabiz.finance.bank.BankAccountProcesses.list;
import static com.jabiz.finance.bank.BankAccountProcesses.trim;

/**
 * Matching a bank account's statement lines to its books (FIN-BK-004, 005, 006), the accountant's:
 * <ul>
 *   <li>{@code FIN_BANK_MATCH_PROPOSE}: what {@link BankMatcher} proposes for the open lines and items, with a
 *       confidence and reasons; nothing is written.</li>
 *   <li>{@code FIN_BANK_MATCH}: one match by hand: one line to one or more items, or more lines to one item (never
 *       many to many), of equal totals, every one still open.</li>
 *   <li>{@code FIN_BANK_MATCH_ACCEPT}: proposals accepted in bulk, all or none. Matching is run again and each
 *       accepted proposal must be one of its proposals now; their confidence and reasons are matching's, never the
 *       caller's, so the history's "proposed" means proposed.</li>
 *   <li>{@code FIN_BANK_UNMATCH}: a match undone: a record of its own that names it, so both stay in the history and
 *       the lines and items are open again.</li>
 * </ul>
 * Open lines and items are read through the templates {@code finance.bank.statement_items} and
 * {@code finance.bank.book_items}, which also show them to people; their amounts are the books', never the caller's.
 */
public final class MatchProcesses {

    public static final String PROPOSE = "FIN_BANK_MATCH_PROPOSE";
    public static final String MATCH = "FIN_BANK_MATCH";
    public static final String ACCEPT = "FIN_BANK_MATCH_ACCEPT";
    public static final String UNMATCH = "FIN_BANK_UNMATCH";

    public static final String STATEMENT_ITEMS = "finance.bank.statement_items";
    public static final String BOOK_ITEMS = "finance.bank.book_items";

    public static final String NOT_OPEN = "FIN_BANK_MATCH_NOT_OPEN";
    public static final String UNEQUAL = "FIN_BANK_MATCH_UNEQUAL";
    public static final String MANY_TO_MANY = "FIN_BANK_MATCH_MANY_TO_MANY";
    public static final String TWICE = "FIN_BANK_MATCH_TWICE";
    public static final String NOT_PROPOSED = "FIN_BANK_MATCH_NOT_PROPOSED";
    public static final String NOT_FOUND = "FIN_BANK_MATCH_NOT_FOUND";
    public static final String UNDONE = "FIN_BANK_MATCH_UNDONE";
    public static final String RECONCILED = "FIN_BANK_MATCH_RECONCILED";

    /** A book item: a cash account's ledger transaction ({@code LEDGER}) or an outstanding cutover item. */
    public record BookRef(@NotBlank @Size(max = 10) String kind, @NotBlank @Size(max = 36) String id) {}

    /** @param reason why they belong together, for the history */
    public record MatchInput(@NotBlank @Size(max = 20) String bankCode,
        @NotNull @Size(min = 1, max = 200) List<@NotNull UUID> lineIds,
        @NotNull @Size(min = 1, max = 500) List<@Valid @NotNull BookRef> items, @Size(max = 500) String reason) {}

    public record MatchOutput(String matchId, BigDecimal amount) {}

    public record AcceptInput(@NotBlank @Size(max = 20) String bankCode,
        @NotNull @Size(min = 1, max = MAX_ACCEPTED) List<@Valid @NotNull Accepted> proposals) {}

    /** A proposal as {@code FIN_BANK_MATCH_PROPOSE} gave it: its line and its items. */
    public record Accepted(@NotNull UUID lineId, @NotNull @Size(min = 1, max = 500) List<@Valid @NotNull BookRef> items) {}

    public record AcceptOutput(int matched) {}

    public record UnmatchInput(@NotNull UUID matchId, @NotBlank @Size(max = 500) String reason) {}

    public record UnmatchOutput(String undoId, String matchId) {}

    /** @param to the last day of the lines and items to match; all when absent */
    public record ProposeInput(@NotBlank @Size(max = 20) String bankCode, LocalDate to) {}

    public record ProposedItem(String kind, String id, LocalDate date, BigDecimal amount, String documentNo,
        String party) {}

    public record Proposal(UUID lineId, LocalDate valueDate, String bankReference, String description,
        BigDecimal amount, List<ProposedItem> items, int confidence, List<String> reasons) {}

    /** @param openLines the lines left without a proposal; {@code openItems} likewise the book items */
    public record ProposeOutput(List<Proposal> proposals, int openLines, int openItems) {}

    /** A reference matched this often before is no limit: its items, all of them, decide its next round. */
    static final int MAX_ITEMS = 10_000;
    /** The most proposals accepted at once: one write of their matches. */
    static final int MAX_ACCEPTED = 500;

    static final String INPUT = "input";
    static final String OUTPUT = "output";
    static final String SETTINGS = "settings";
    static final String LINES = "lines";
    static final String BOOK = "book";
    static final String ITEMS = "items";
    static final String MATCHES = "matches";
    static final String UNDOS = "undos";
    static final String MATCH_ITEMS = "matchItems";
    static final String HELD = "held";

    public static final ProcessDefinition<ProposeInput, ProposeOutput, ProcessContext> PROPOSE_PROCESS =
        ProcessDefinition.define(PROPOSE, 1, ProposeInput.class, ProposeOutput.class, ProcessContext.class, pb -> pb
            .description("Proposes matches of a bank account's open statement lines and book items.")
            .permissions(FinancePermissions.BANK_RECONCILE)
            .contextFactory(BankAccountProcesses::withInput)
            .outputMapper(ctx -> ctx.get(OUTPUT, ProposeOutput.class))
            .step("Load the settings", QueryEntities.of(BankEntities.SETTINGS_DATASET,
                ctx -> BankSettingsProcesses.current(), SETTINGS))
            .step("Load the open lines", RunTemplate.of(STATEMENT_ITEMS, ctx -> params(
                ctx.get(INPUT, ProposeInput.class).bankCode(), ctx.get(INPUT, ProposeInput.class).to()), LINES))
            .step("Load the open book items", RunTemplate.of(BOOK_ITEMS, ctx -> params(
                ctx.get(INPUT, ProposeInput.class).bankCode(), ctx.get(INPUT, ProposeInput.class).to()), BOOK))
            .compute("Propose", (metadata, ctx) -> propose(ctx)));

    public static final ProcessDefinition<MatchInput, MatchOutput, ProcessContext> MATCH_PROCESS =
        ProcessDefinition.define(MATCH, 1, MatchInput.class, MatchOutput.class, ProcessContext.class, pb -> pb
            .description("Matches statement lines of a bank account to book items of equal total.")
            .permissions(FinancePermissions.BANK_RECONCILE)
            .contextFactory(BankAccountProcesses::withInput)
            .outputMapper(ctx -> ctx.get(OUTPUT, MatchOutput.class))
            .step("Load the open lines", RunTemplate.of(STATEMENT_ITEMS, ctx -> params(
                ctx.get(INPUT, MatchInput.class).bankCode(), null), LINES))
            .step("Load the open book items", RunTemplate.of(BOOK_ITEMS, ctx -> params(
                ctx.get(INPUT, MatchInput.class).bankCode(), null), BOOK))
            .step("Load their matches before", QueryEntities.of(MatchEntities.ITEM_DATASET, ctx -> {
                MatchInput input = ctx.get(INPUT, MatchInput.class);
                List<Object> refs = new ArrayList<>();
                input.lineIds().forEach(id -> refs.add(String.valueOf(id)));
                input.items().forEach(ref -> refs.add(ref.id().trim()));
                return EntityQuery.builder().where(new QueryPredicate.In("refId", refs)).limit(MAX_ITEMS).build();
            }, ITEMS))
            .step("Load the reconciliation that holds", QueryEntities.of(
                ReconciliationEntities.RECONCILIATION_DATASET,
                ctx -> heldQuery(code(ctx.get(INPUT, MatchInput.class).bankCode())), HELD))
            .compute("Match", (metadata, ctx) -> match(ctx)));

    public static final ProcessDefinition<AcceptInput, AcceptOutput, ProcessContext> ACCEPT_PROCESS =
        ProcessDefinition.define(ACCEPT, 1, AcceptInput.class, AcceptOutput.class, ProcessContext.class, pb -> pb
            .description("Accepts proposed matches of a bank account in bulk.")
            .permissions(FinancePermissions.BANK_RECONCILE)
            .contextFactory(BankAccountProcesses::withInput)
            .outputMapper(ctx -> ctx.get(OUTPUT, AcceptOutput.class))
            .step("Load the settings", QueryEntities.of(BankEntities.SETTINGS_DATASET,
                ctx -> BankSettingsProcesses.current(), SETTINGS))
            .step("Load the open lines", RunTemplate.of(STATEMENT_ITEMS, ctx -> params(
                ctx.get(INPUT, AcceptInput.class).bankCode(), null), LINES))
            .step("Load the open book items", RunTemplate.of(BOOK_ITEMS, ctx -> params(
                ctx.get(INPUT, AcceptInput.class).bankCode(), null), BOOK))
            .step("Load their matches before", QueryEntities.of(MatchEntities.ITEM_DATASET, ctx -> {
                List<Object> refs = new ArrayList<>();
                ctx.get(INPUT, AcceptInput.class).proposals().forEach(p -> {
                    refs.add(String.valueOf(p.lineId()));
                    p.items().forEach(ref -> refs.add(ref.id().trim()));
                });
                return EntityQuery.builder().where(new QueryPredicate.In("refId", refs)).limit(MAX_ITEMS).build();
            }, ITEMS))
            .step("Load the reconciliation that holds", QueryEntities.of(
                ReconciliationEntities.RECONCILIATION_DATASET,
                ctx -> heldQuery(code(ctx.get(INPUT, AcceptInput.class).bankCode())), HELD))
            .compute("Make the matches", (metadata, ctx) -> accept(ctx)));

    public static final ProcessDefinition<UnmatchInput, UnmatchOutput, ProcessContext> UNMATCH_PROCESS =
        ProcessDefinition.define(UNMATCH, 1, UnmatchInput.class, UnmatchOutput.class, ProcessContext.class, pb -> pb
            .description("Undoes a match: its lines and items are open again, and both records stay.")
            .permissions(FinancePermissions.BANK_RECONCILE)
            .contextFactory(BankAccountProcesses::withInput)
            .outputMapper(ctx -> ctx.get(OUTPUT, UnmatchOutput.class))
            .step("Load the match", QueryEntities.of(MatchEntities.MATCH_DATASET, ctx -> EntityQuery.builder()
                .where(new QueryPredicate.In("matchId", List.of(ctx.get(INPUT, UnmatchInput.class).matchId())))
                .limit(1).build(), MATCHES))
            .step("Look for its undo", QueryEntities.of(MatchEntities.MATCH_DATASET, ctx -> EntityQuery.builder()
                .where(new QueryPredicate.Eq("reversesMatchId", ctx.get(INPUT, UnmatchInput.class).matchId()))
                .limit(1).build(), UNDOS))
            .step("Load its items", QueryEntities.of(MatchEntities.ITEM_DATASET, ctx -> EntityQuery.builder()
                .where(new QueryPredicate.Eq("matchId", ctx.get(INPUT, UnmatchInput.class).matchId()))
                .limit(MAX_ITEMS).build(), MATCH_ITEMS))
            // A match counts in every reconciliation from its last item's day on: one submitted or signed off
            // there keeps it, or what was reviewed would no longer be what the books say (FIN-BK-008).
            .step("Load the reconciliation that holds", QueryEntities.of(
                ReconciliationEntities.RECONCILIATION_DATASET, ctx -> heldQuery(list(ctx, MATCHES).stream()
                    .findFirst().map(m -> (String) m.get("bankCode")).orElse(null)), HELD))
            .compute("Undo it", (metadata, ctx) -> unmatch(ctx)));

    static Map<String, Object> params(String bankCode, LocalDate to) {
        Map<String, Object> params = new HashMap<>();
        params.put("bankCode", code(bankCode) == null ? "" : code(bankCode));
        params.put("to", to);
        return params;
    }

    static void propose(ProcessContext ctx) {
        BankSettingsProcesses.Settings settings = BankSettingsProcesses.Settings.of(list(ctx, SETTINGS));
        List<BankMatcher.Line> lines = rows(ctx, LINES).stream().map(MatchProcesses::line).toList();
        List<BankMatcher.Item> items = rows(ctx, BOOK).stream().map(MatchProcesses::item).toList();
        List<BankMatcher.Proposal> found = BankMatcher.propose(lines, items, settings.matchWindowDays());
        Map<String, Map<String, Object>> lineRows = new HashMap<>();
        rows(ctx, LINES).forEach(r -> lineRows.put(String.valueOf(r.get("lineId")), r));
        List<Proposal> proposals = new ArrayList<>();
        int usedItems = 0;
        for (BankMatcher.Proposal p : found) {
            Map<String, Object> row = lineRows.get(p.line().id());
            proposals.add(new Proposal(UUID.fromString(p.line().id()), p.line().date(), p.line().reference(),
                text(row.get("description")), p.line().amount(), p.items().stream().map(i -> new ProposedItem(
                    i.kind(), i.id(), i.date(), i.amount(), i.documentNo(), i.party())).toList(), p.confidence(),
                p.reasons()));
            usedItems += p.items().size();
        }
        ctx.put(OUTPUT, new ProposeOutput(List.copyOf(proposals), lines.size() - found.size(),
            items.size() - usedItems));
    }

    static void accept(ProcessContext ctx) {
        AcceptInput input = ctx.get(INPUT, AcceptInput.class);
        BankSettingsProcesses.Settings settings = BankSettingsProcesses.Settings.of(list(ctx, SETTINGS));
        Map<String, BankMatcher.Proposal> proposed = new HashMap<>();
        for (BankMatcher.Proposal p : BankMatcher.propose(rows(ctx, LINES).stream().map(MatchProcesses::line)
            .toList(), rows(ctx, BOOK).stream().map(MatchProcesses::item).toList(), settings.matchWindowDays())) {
            proposed.put(p.line().id(), p);
        }
        Map<String, Map<String, Object>> lineRows = new HashMap<>();
        rows(ctx, LINES).forEach(r -> lineRows.put(String.valueOf(r.get("lineId")), r));
        List<BankMatcher.Proposal> chosen = new ArrayList<>();
        for (Accepted accepted : input.proposals()) {
            BankMatcher.Proposal p = proposed.get(String.valueOf(accepted.lineId()));
            Set<String> wanted = new java.util.TreeSet<>();
            accepted.items().forEach(ref -> wanted.add(ref.kind().trim().toUpperCase(Locale.ROOT) + ":"
                + ref.id().trim()));
            Set<String> offered = new java.util.TreeSet<>();
            if (p != null) {
                p.items().forEach(i -> offered.add(i.kind() + ":" + i.id()));
            }
            if (p == null || !wanted.equals(offered)) {
                ctx.reject(new Violation("proposals", NOT_PROPOSED, "Matching does not propose that for line "
                    + accepted.lineId() + " now", Map.of("lineId", String.valueOf(accepted.lineId()))));
                continue;
            }
            chosen.add(p);
            proposed.remove(p.line().id());
        }
        if (ctx.hasViolations()) {
            return;
        }
        for (BankMatcher.Proposal p : chosen) {
            LocalDate last = p.line().date();
            for (BankMatcher.Item i : p.items()) {
                last = i.date().isAfter(last) ? i.date() : last;
            }
            held(ctx, last, "proposals");
        }
        if (ctx.hasViolations()) {
            return;
        }
        String bankCode = code(input.bankCode());
        for (BankMatcher.Proposal p : chosen) {
            Map<String, Object> row = lineRows.get(p.line().id());
            Side line = new Side(MatchEntities.LINE, p.line().id(), p.line().date(), p.line().amount().setScale(2),
                label(text(row.get("bankReference")), text(row.get("description"))));
            List<Side> books = p.items().stream().map(i -> new Side(i.kind(), i.id(), i.date(),
                i.amount().setScale(2), label(i.documentNo(), null))).toList();
            write(ctx, bankCode, List.of(line), books, MatchEntities.AUTO, p.confidence(),
                String.join("; ", p.reasons()), list(ctx, ITEMS));
        }
        ctx.put(OUTPUT, new AcceptOutput(chosen.size()));
    }

    static void match(ProcessContext ctx) {
        MatchInput input = ctx.get(INPUT, MatchInput.class);
        Map<String, Map<String, Object>> open = new HashMap<>();
        rows(ctx, LINES).forEach(r -> open.put(MatchEntities.LINE + ":" + r.get("lineId"), r));
        rows(ctx, BOOK).forEach(r -> open.put(r.get("refKind") + ":" + r.get("refId"), r));
        List<Side> lines = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        List<String> closed = new ArrayList<>();
        for (UUID lineId : input.lineIds()) {
            String key = MatchEntities.LINE + ":" + lineId;
            if (!seen.add(key)) {
                ctx.reject(new Violation("lineIds", TWICE, "Line " + lineId + " is named twice", Map.of()));
                continue;
            }
            Map<String, Object> row = open.get(key);
            if (row == null) {
                closed.add("line " + lineId);
                continue;
            }
            lines.add(new Side(MatchEntities.LINE, String.valueOf(lineId), date(row.get("valueDate")),
                amount(row.get("amount")), label(text(row.get("bankReference")), text(row.get("description")))));
        }
        List<Side> books = new ArrayList<>();
        for (BookRef ref : input.items()) {
            String kind = ref.kind().trim().toUpperCase(Locale.ROOT);
            String key = kind + ":" + ref.id().trim();
            if (!seen.add(key)) {
                ctx.reject(new Violation("items", TWICE, "Book item " + ref.id() + " is named twice", Map.of()));
                continue;
            }
            Map<String, Object> row = (MatchEntities.LEDGER.equals(kind) || MatchEntities.OPENING.equals(kind))
                ? open.get(key) : null;
            if (row == null) {
                closed.add(kind.toLowerCase(Locale.ROOT) + " " + ref.id().trim());
                continue;
            }
            books.add(new Side(kind, ref.id().trim(), date(row.get("itemDate")), amount(row.get("amount")),
                label(text(row.get("documentNo")), text(row.get("description")))));
        }
        if (!closed.isEmpty()) {
            // Matched already, of another account, or not there at all: either way not this account's to match.
            ctx.reject(new Violation("items", NOT_OPEN, "Not open in bank account " + code(input.bankCode()) + ": "
                + String.join(", ", closed), Map.of("items", String.join(", ", closed))));
        }
        if (lines.size() > 1 && books.size() > 1) {
            ctx.reject(new Violation("items", MANY_TO_MANY, "A match is one line to items, or lines to one item",
                Map.of()));
        }
        if (ctx.hasViolations()) {
            return;
        }
        BigDecimal statement = total(lines);
        BigDecimal book = total(books);
        if (statement.compareTo(book) != 0) {
            ctx.reject(new Violation("items", UNEQUAL, "The lines come to " + statement.toPlainString()
                + ", the book items to " + book.toPlainString(), Map.of("statement", statement, "book", book)));
            return;
        }
        LocalDate last = java.util.stream.Stream.concat(lines.stream(), books.stream()).map(Side::date)
            .max(LocalDate::compareTo).orElseThrow();
        if (held(ctx, last, "items")) {
            return;
        }
        Object matchId = write(ctx, code(input.bankCode()), lines, books, MatchEntities.MANUAL, null,
            trim(input.reason()), list(ctx, ITEMS));
        ctx.put(OUTPUT, new MatchOutput(String.valueOf(matchId), statement));
    }

    static void unmatch(ProcessContext ctx) {
        UnmatchInput input = ctx.get(INPUT, UnmatchInput.class);
        EntityInstance match = list(ctx, MATCHES).stream().findFirst().orElse(null);
        if (match == null || !MatchEntities.MATCHED.equals(match.get("action"))) {
            ctx.reject(new Violation("matchId", NOT_FOUND, "There is no match " + input.matchId(),
                Map.of("matchId", String.valueOf(input.matchId()))));
            return;
        }
        if (!list(ctx, UNDOS).isEmpty()) {
            ctx.reject(new Violation("matchId", UNDONE, "The match is undone already", Map.of()));
            return;
        }
        if (held(ctx, lastItemDay(ctx), "matchId")) {
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("bankCode", match.get("bankCode"));
        values.put("action", MatchEntities.UNMATCHED);
        values.put("reversesMatchId", match.id());
        values.put("amount", match.get("amount"));
        values.put("reason", input.reason().trim());
        values.put("actor", ctx.request().actorId());
        values.put("actionTime", ctx.opTime());
        Object undoId = ctx.changes().insert(MatchEntities.MATCH, values);
        ctx.put(OUTPUT, new UnmatchOutput(String.valueOf(undoId), String.valueOf(match.id())));
    }

    /** The latest reconciliation of a bank account submitted or signed off: it holds every day up to its own. */
    static EntityQuery heldQuery(String bankCode) {
        if (bankCode == null) {
            return ReconciliationProcesses.byId(null);
        }
        return EntityQuery.builder().where(new QueryPredicate.And(List.of(
            new QueryPredicate.Eq("bankCode", bankCode),
            new QueryPredicate.In("status", List.of(ReconciliationEntities.SUBMITTED,
                ReconciliationEntities.SIGNED_OFF))))).orderBy("statementDate", false).limit(1).build();
    }

    /**
     * Whether a match whose last line or item lies on {@code last} would change a reconciliation submitted or signed
     * off: it counts in every reconciliation from that day on, so matching or undoing it there would make what was
     * reviewed differ from the books (FIN-BK-008). Refuses it if so.
     */
    static boolean held(ProcessContext ctx, LocalDate last, String field) {
        EntityInstance rec = list(ctx, HELD).stream().findFirst().orElse(null);
        if (rec == null || last == null || last.isAfter(rec.get("statementDate"))) {
            return false;
        }
        ctx.reject(new Violation(field, RECONCILED, "The match counts in the reconciliation of "
            + rec.get("statementDate") + ", which is " + rec.get("status"), Map.of("statementDate",
            String.valueOf((Object) rec.get("statementDate")), "status", String.valueOf((Object) rec.get("status")))));
        return true;
    }

    private static LocalDate lastItemDay(ProcessContext ctx) {
        return list(ctx, MATCH_ITEMS).stream().map(i -> (LocalDate) i.get("itemDate"))
            .filter(java.util.Objects::nonNull).max(LocalDate::compareTo).orElse(null);
    }

    /** One side of a match: a statement line or a book item as it was when matched. */
    record Side(String kind, String id, LocalDate date, BigDecimal amount, String label) {}

    /**
     * Writes a match and its items; each item's round is one more than its reference's items before (in
     * {@code before}), which a concurrent match of the same reference cannot also take.
     */
    static Object write(ProcessContext ctx, String bankCode, List<Side> lines, List<Side> books, String method,
        Integer confidence, String reason, List<EntityInstance> before) {
        Map<String, Integer> rounds = new HashMap<>();
        for (EntityInstance item : before) {
            if (!bankCode.equals(item.get("bankCode"))) {
                continue;
            }
            String key = item.get("refKind") + ":" + item.get("refId");
            rounds.merge(key, ((BigDecimal) item.get("round")).intValueExact(), Math::max);
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("bankCode", bankCode);
        values.put("action", MatchEntities.MATCHED);
        values.put("method", method);
        values.put("amount", total(lines));
        values.put("confidence", confidence == null ? null : BigDecimal.valueOf(confidence));
        values.put("reason", reason);
        values.put("actor", ctx.request().actorId());
        values.put("actionTime", ctx.opTime());
        Object matchId = ctx.changes().insert(MatchEntities.MATCH, values);
        for (List<Side> sides : List.of(lines, books)) {
            for (Side side : sides) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("matchId", matchId);
                item.put("bankCode", bankCode);
                item.put("side", MatchEntities.LINE.equals(side.kind()) ? MatchEntities.STATEMENT : MatchEntities.BOOK);
                item.put("refKind", side.kind());
                item.put("refId", side.id());
                item.put("round", BigDecimal.valueOf(rounds.getOrDefault(side.kind() + ":" + side.id(), 0) + 1));
                item.put("itemDate", side.date());
                item.put("amount", side.amount());
                item.put("label", side.label());
                ctx.changes().insert(MatchEntities.ITEM, item);
            }
        }
        return matchId;
    }

    static BankMatcher.Line line(Map<String, Object> row) {
        return new BankMatcher.Line(String.valueOf(row.get("lineId")), date(row.get("valueDate")),
            amount(row.get("amount")), text(row.get("bankReference")), text(row.get("description")));
    }

    static BankMatcher.Item item(Map<String, Object> row) {
        return new BankMatcher.Item(text(row.get("refKind")), String.valueOf(row.get("refId")),
            date(row.get("itemDate")), amount(row.get("amount")), text(row.get("documentNo")),
            text(row.get("party")), text(row.get("checkNo")), text(row.get("runNo")));
    }

    static LocalDate date(Object value) {
        return value instanceof LocalDate day ? day : value == null ? null : LocalDate.parse(String.valueOf(value)
            .substring(0, 10));
    }

    static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    static String label(String preferred, String other) {
        String text = preferred != null && !preferred.isBlank() ? preferred : other;
        return text == null ? null : text.length() > 100 ? text.substring(0, 100) : text;
    }

    static BigDecimal amount(Object value) {
        return value instanceof BigDecimal d ? d.setScale(2) : new BigDecimal(String.valueOf(value)).setScale(2);
    }

    private static BigDecimal total(List<Side> sides) {
        return sides.stream().map(Side::amount).reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> rows(ProcessContext ctx, String key) {
        List<Map<String, Object>> rows = (List<Map<String, Object>>) ctx.get(key);
        return rows == null ? List.of() : rows;
    }

    private MatchProcesses() {}
}
