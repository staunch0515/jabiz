package com.jabiz.runtime.ledger;

import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.ledger.Direction;
import com.jabiz.ledger.ForeignAmount;
import com.jabiz.ledger.LedgerDimension;
import com.jabiz.ledger.LedgerPosting;
import com.jabiz.ledger.PostingLine;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.LoadEntity;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The only writers of ledger transactions (docs/design/11-ledger-events-jobs.md section 1; decision D14):
 * <ul>
 *   <li>{@code LEDGER_ACCOUNT_OPEN} opens an enabled account (accounts are also maintained through their
 *       dataset);</li>
 *   <li>{@code LEDGER_POST} books a balanced transaction on enabled accounts; every violation of the posting rules
 *       ({@link LedgerPosting}) and every unknown or closed account is reported at once (422) and nothing is
 *       written;</li>
 *   <li>entries may carry a memo and values of the declared analysis dimensions, the transaction the document it was
 *       posted from; a summary account takes no postings (docs/design/11-ledger-events-jobs.md sections 1.4 to 1.6,
 *       decision D24); an entry may be in a foreign currency, balanced in that currency too (section 1.8);</li>
 *   <li>{@code LEDGER_REVERSE} books the reversing transaction of a posted one: the same accounts and amounts with
 *       the sides swapped. A transaction is reversed at most once and a reversal is not reversed again.</li>
 * </ul>
 * Business processes post by calling them as sub-processes ({@code CallProcess.of("LEDGER_POST", 1, ...)}).
 */
@Configuration
public class LedgerProcesses {

    public static final String OPEN_ACCOUNT = "LEDGER_ACCOUNT_OPEN";
    public static final String POST = "LEDGER_POST";
    public static final String REVERSE = "LEDGER_REVERSE";

    public record OpenAccountInput(@NotBlank String accountCode, @NotBlank String accountName,
        @NotBlank String accountType) {}

    public record OpenAccountOutput(String accountId, String accountCode) {}

    /**
     * One entry to post.
     *
     * @param amount            the amount in the ledger currency; for an entry in a foreign currency it may be left
     *                          out and is then converted, else it must equal the converted amount
     * @param memo              optional note of the line
     * @param dimensions        values of the declared analysis dimensions by name ({@code {"department": "SALES"}})
     * @param currency          ISO 4217 code of a foreign currency; empty (or the ledger's) for the ledger currency
     * @param transactionAmount the amount in {@code currency}
     * @param exchangeRate      units of the ledger currency per unit of {@code currency}
     */
    public record Line(@NotBlank String accountCode, @NotNull Direction direction, BigDecimal amount,
        String memo, Map<String, String> dimensions, String currency, BigDecimal transactionAmount,
        BigDecimal exchangeRate) {

        public Line(String accountCode, Direction direction, BigDecimal amount) {
            this(accountCode, direction, amount, null, null);
        }

        public Line(String accountCode, Direction direction, BigDecimal amount, String memo,
            Map<String, String> dimensions) {
            this(accountCode, direction, amount, memo, dimensions, null, null, null);
        }
    }

    /**
     * @param bookingTime  when the transaction happened in business terms; defaults to the operation time
     * @param reference    optional reference to the business document (a statement number, an invoice)
     * @param sourceEntity the entity of the document posted from (an invoice, a journal entry), if any
     * @param sourceId     its key; the document must exist
     */
    public record PostInput(Instant bookingTime, @NotBlank String description, String reference,
        @NotEmpty List<@Valid @NotNull Line> entries, String sourceEntity, String sourceId) {

        public PostInput(Instant bookingTime, String description, String reference, List<Line> entries) {
            this(bookingTime, description, reference, entries, null, null);
        }
    }

    public record PostOutput(String transactionId, Instant bookingTime, BigDecimal total, int entryCount) {}

    /** @param bookingTime of the reversal; defaults to the operation time */
    public record ReverseInput(@NotBlank String transactionId, @NotBlank String reason, Instant bookingTime) {}

    public record ReverseOutput(String transactionId, String reversedTransactionId, BigDecimal total) {}

    static final String ACCOUNTS = "accounts";
    static final String TRANSACTION_ID = "transaction_id";
    static final String ORIGINAL = "original";
    static final String ENTRIES = "entries";
    static final String REVERSALS = "reversals";
    static final String OUTPUT = "output";

    public static ProcessDefinition<OpenAccountInput, OpenAccountOutput, ProcessContext> openAccount() {
        return ProcessDefinition.define(OPEN_ACCOUNT, 1, OpenAccountInput.class, OpenAccountOutput.class,
            ProcessContext.class, pb -> pb
                .description("Opens a ledger account.")
                .permissions(LedgerPermissions.ACCOUNT_WRITE)
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put("input", input);
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get(OUTPUT, OpenAccountOutput.class))
                .compute("Register the account", (metadata, ctx) -> {
                    OpenAccountInput input = ctx.get("input", OpenAccountInput.class);
                    Map<String, Object> account = new LinkedHashMap<>();
                    account.put("accountCode", input.accountCode());
                    account.put("accountName", input.accountName());
                    account.put("accountType", input.accountType());
                    account.put("enabled", true);
                    Object id = ctx.changes().insert(LedgerEntities.ACCOUNT, account);
                    ctx.put(OUTPUT, new OpenAccountOutput(String.valueOf(id), input.accountCode()));
                }));
    }

    public static ProcessDefinition<PostInput, PostOutput, ProcessContext> post(LedgerEntities.Settings settings) {
        return ProcessDefinition.define(POST, 1, PostInput.class, PostOutput.class, ProcessContext.class, pb -> pb
            .description("Posts a balanced double-entry transaction.")
            .permissions(LedgerPermissions.POST)
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put("input", input);
                return ctx;
            })
            .outputMapper(ctx -> ctx.get(OUTPUT, PostOutput.class))
            .step("Check dimensions and source", CheckPosting.of("input"))
            .step("Load the accounts", QueryEntities.of(LedgerEntities.ACCOUNT_DATASET, ctx -> {
                List<Object> codes = ctx.get("input", PostInput.class).entries().stream()
                    .map(Line::accountCode).distinct().map(Object.class::cast).toList();
                return EntityQuery.builder().where(new QueryPredicate.In("accountCode", codes))
                    .limit(LedgerPosting.MAX_LINES).build();
            }, ACCOUNTS))
            .compute("Book the transaction", (metadata, ctx) -> post(ctx, settings)));
    }

    public static ProcessDefinition<ReverseInput, ReverseOutput, ProcessContext> reverse() {
        return ProcessDefinition.define(REVERSE, 1, ReverseInput.class, ReverseOutput.class, ProcessContext.class,
            pb -> pb
                .description("Reverses a posted transaction with a transaction of the opposite entries.")
                .permissions(LedgerPermissions.REVERSE)
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put("input", input);
                    ctx.put(TRANSACTION_ID, input.transactionId());
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get(OUTPUT, ReverseOutput.class))
                .step("Load the transaction", LoadEntity.by(LedgerEntities.TRANSACTION_DATASET, TRANSACTION_ID,
                    ORIGINAL))
                .step("Load its entries", QueryEntities.of(LedgerEntities.ENTRY_DATASET,
                    ctx -> byField("transactionId", ctx.get(TRANSACTION_ID, String.class), LedgerPosting.MAX_LINES),
                    ENTRIES))
                .step("Load earlier reversals", QueryEntities.of(LedgerEntities.TRANSACTION_DATASET,
                    ctx -> byField("reversesTransactionId", ctx.get(TRANSACTION_ID, String.class), 1), REVERSALS))
                .compute("Book the reversal", (metadata, ctx) -> reverse(ctx)));
    }

    /**
     * The entry to check and book, the ledger amount of a foreign one converted when left out; null (and the
     * problem rejected) when a needed part is missing.
     */
    private static PostingLine postingLine(Line line, int number, LedgerEntities.Settings settings,
        ProcessContext ctx) {
        boolean foreign = line.currency() != null && !line.currency().isBlank()
            && !line.currency().equals(settings.currency());
        if (!foreign) {
            BigDecimal amount = line.amount() != null ? line.amount() : line.transactionAmount();
            if (amount == null) {
                ctx.reject(missing(number, "amount"));
                return null;
            }
            return new PostingLine(line.accountCode(), line.direction(), amount, line.memo(), line.dimensions());
        }
        if (line.transactionAmount() == null || line.exchangeRate() == null) {
            ctx.reject(missing(number, line.transactionAmount() == null ? "transactionAmount" : "exchangeRate"));
            return null;
        }
        ForeignAmount amount = new ForeignAmount(line.currency(), line.transactionAmount(), line.exchangeRate());
        return new PostingLine(line.accountCode(), line.direction(),
            line.amount() != null ? line.amount() : amount.converted(settings.scale()), line.memo(),
            line.dimensions(), amount);
    }

    private static Violation missing(int line, String part) {
        return new Violation("entries", PlatformErrorCodes.REQUIRED, "Entry " + line + ": " + part + " is required",
            Map.of("line", line, "field", "entries[" + (line - 1) + "]." + part));
    }

    private static EntityQuery byField(String field, Object value, int limit) {
        return EntityQuery.builder().where(new QueryPredicate.Eq(field, value)).limit(limit).build();
    }

    private static void post(ProcessContext ctx, LedgerEntities.Settings settings) {
        PostInput input = ctx.get("input", PostInput.class);
        @SuppressWarnings("unchecked")
        List<LedgerDimension> dimensions = (List<LedgerDimension>) ctx.get(CheckPosting.DIMENSIONS);
        @SuppressWarnings("unchecked")
        Set<String> idDimensions = (Set<String>) ctx.get(CheckPosting.ID_DIMENSIONS);
        List<PostingLine> lines = new ArrayList<>();
        for (int i = 0; i < input.entries().size(); i++) {
            PostingLine line = postingLine(input.entries().get(i), i + 1, settings, ctx);
            if (line != null) {
                lines.add(line);
            }
        }
        if (lines.size() < input.entries().size()) {
            return;
        }
        LedgerPosting.validate(lines, settings.scale(), dimensions, settings.currency()).forEach(ctx::reject);
        Map<String, EntityInstance> accounts = entities(ctx, ACCOUNTS).stream()
            .collect(Collectors.toMap(account -> account.<String>get("accountCode"), Function.identity()));
        List<Object> accountIds = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String code = lines.get(i).accountCode();
            EntityInstance account = accounts.get(code);
            Map<String, Object> params = Map.of("line", i + 1, "account", code);
            if (account == null) {
                ctx.reject(new Violation("entries", PlatformErrorCodes.LEDGER_ACCOUNT_NOT_FOUND,
                    "Entry " + (i + 1) + ": no account " + code, params));
            } else if (!Boolean.TRUE.equals(account.get("enabled"))) {
                ctx.reject(new Violation("entries", PlatformErrorCodes.LEDGER_ACCOUNT_DISABLED,
                    "Entry " + (i + 1) + ": account " + code + " is closed", params));
            } else if (Boolean.TRUE.equals(account.get("summary"))) {
                ctx.reject(new Violation("entries", PlatformErrorCodes.LEDGER_ACCOUNT_NOT_POSTABLE,
                    "Entry " + (i + 1) + ": account " + code + " is a summary account", params));
            }
            accountIds.add(account == null ? null : account.id());
        }
        if (ctx.hasViolations()) {
            return;
        }
        Instant booking = input.bookingTime() == null ? ctx.opTime() : input.bookingTime();
        List<Map<String, Object>> extras = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            Map<String, Object> extra = CheckPosting.entryFields(input.entries().get(i), dimensions, idDimensions);
            ForeignAmount foreign = lines.get(i).foreign();
            if (foreign != null) {
                extra.put("currency", foreign.currency());
                extra.put("transactionAmount", foreign.amount());
                extra.put("exchangeRate", foreign.rate());
            }
            extras.add(extra);
        }
        Object transactionId = book(ctx, booking, input.description(), input.reference(),
            new Source(input.sourceEntity(), input.sourceId()), null, lines, accountIds, extras);
        ctx.put(OUTPUT, new PostOutput(String.valueOf(transactionId), booking,
            LedgerPosting.total(lines, Direction.DEBIT), lines.size()));
    }

    private static void reverse(ProcessContext ctx) {
        ReverseInput input = ctx.get("input", ReverseInput.class);
        EntityInstance original = ctx.get(ORIGINAL, EntityInstance.class);
        Map<String, Object> params = Map.of("transaction", String.valueOf(original.id()));
        if (original.get("reversesTransactionId") != null) {
            ctx.reject(new Violation("transactionId", PlatformErrorCodes.LEDGER_REVERSAL_NOT_REVERSIBLE,
                "Transaction " + original.id() + " is a reversal", params));
            return;
        }
        if (!entities(ctx, REVERSALS).isEmpty()) {
            ctx.reject(new Violation("transactionId", PlatformErrorCodes.LEDGER_ALREADY_REVERSED,
                "Transaction " + original.id() + " has been reversed already", params));
            return;
        }
        List<EntityInstance> entries = new ArrayList<>(entities(ctx, ENTRIES));
        entries.sort(Comparator.comparing(entry -> entry.<BigDecimal>get("lineNo")));
        List<PostingLine> lines = new ArrayList<>();
        List<Object> accountIds = new ArrayList<>();
        List<Map<String, Object>> extras = new ArrayList<>();
        for (EntityInstance entry : entries) {
            // The reversal keeps each line's memo and dimensions, so it cancels them in every report.
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("memo", entry.get("memo"));
            extra.put("currency", entry.get("currency"));
            extra.put("transactionAmount", entry.get("transactionAmount"));
            extra.put("exchangeRate", entry.get("exchangeRate"));
            for (int position = 1; position <= LedgerDimension.MAX_POSITION; position++) {
                extra.put(LedgerDimension.field(position), entry.get(LedgerDimension.field(position)));
            }
            extras.add(extra);
            // The account id stands in for the code: only the side is swapped.
            lines.add(new PostingLine(String.valueOf(entry.<Object>get("accountId")),
                Direction.valueOf(entry.get("direction")), entry.get("amount")).reversed());
            accountIds.add(entry.get("accountId"));
        }
        Instant booking = input.bookingTime() == null ? ctx.opTime() : input.bookingTime();
        String description = "Reversal: " + input.reason();
        Object reversalId = book(ctx, booking, description.length() > 500 ? description.substring(0, 500)
            : description, original.get("reference"), new Source(original.get("sourceEntity"),
            original.get("sourceId")), original.id(), lines, accountIds, extras);
        ctx.put(OUTPUT, new ReverseOutput(String.valueOf(reversalId), String.valueOf(original.id()),
            LedgerPosting.total(lines, Direction.DEBIT)));
    }

    private record Source(String entity, String id) {}

    /**
     * Registers the transaction and its entries; returns the transaction's id. {@code extras} are further fields of
     * each entry (memo, dimensions).
     */
    private static Object book(ProcessContext ctx, Instant booking, String description, Object reference,
        Source source, Object reverses, List<PostingLine> lines, List<Object> accountIds,
        List<Map<String, Object>> extras) {
        Map<String, Object> transaction = new LinkedHashMap<>();
        transaction.put("bookingTime", booking);
        transaction.put("description", description);
        transaction.put("reference", reference);
        transaction.put("sourceEntity", source.entity());
        transaction.put("sourceId", source.id());
        transaction.put("reversesTransactionId", reverses);
        Object transactionId = Objects.requireNonNull(ctx.changes().insert(LedgerEntities.TRANSACTION, transaction));
        for (int i = 0; i < lines.size(); i++) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("transactionId", transactionId);
            entry.put("accountId", accountIds.get(i));
            entry.put("lineNo", BigDecimal.valueOf(i + 1L));
            entry.put("direction", lines.get(i).direction().name());
            entry.put("amount", lines.get(i).amount());
            extras.get(i).forEach((field, value) -> {
                if (value != null) {
                    entry.put(field, value);
                }
            });
            ctx.changes().insert(LedgerEntities.ENTRY, entry);
        }
        return transactionId;
    }

    @SuppressWarnings("unchecked")
    private static List<EntityInstance> entities(ProcessContext ctx, String key) {
        return (List<EntityInstance>) ctx.get(key);
    }

    @Bean
    ProcessDefinition<OpenAccountInput, OpenAccountOutput, ProcessContext> ledgerOpenAccountProcess() {
        return openAccount();
    }

    @Bean
    ProcessDefinition<PostInput, PostOutput, ProcessContext> ledgerPostProcess(LedgerEntities.Settings settings) {
        return post(settings);
    }

    @Bean
    ProcessDefinition<ReverseInput, ReverseOutput, ProcessContext> ledgerReverseProcess() {
        return reverse();
    }
}
