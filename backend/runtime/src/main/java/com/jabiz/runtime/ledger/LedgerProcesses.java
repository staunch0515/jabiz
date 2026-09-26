package com.jabiz.runtime.ledger;

import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.ledger.Direction;
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
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The only writers of ledger transactions (docs/design/11-ledger-events-jobs.md section 1; decision D14):
 * <ul>
 *   <li>{@code LEDGER_POST} books a balanced transaction on enabled accounts; every violation of the posting rules
 *       ({@link LedgerPosting}) and every unknown or closed account is reported at once (422) and nothing is
 *       written;</li>
 *   <li>{@code LEDGER_REVERSE} books the reversing transaction of a posted one: the same accounts and amounts with
 *       the sides swapped. A transaction is reversed at most once and a reversal is not reversed again.</li>
 * </ul>
 * Business processes post by calling them as sub-processes ({@code CallProcess.of("LEDGER_POST", 1, ...)}).
 */
@Configuration
public class LedgerProcesses {

    public static final String POST = "LEDGER_POST";
    public static final String REVERSE = "LEDGER_REVERSE";

    /** One entry to post. */
    public record Line(@NotBlank String accountCode, @NotNull Direction direction, @NotNull BigDecimal amount) {}

    /**
     * @param bookingTime when the transaction happened in business terms; defaults to the operation time
     * @param reference   optional reference to the business document (a statement number, an invoice)
     */
    public record PostInput(Instant bookingTime, @NotBlank String description, String reference,
        @NotEmpty List<@Valid @NotNull Line> entries) {}

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

    private static EntityQuery byField(String field, Object value, int limit) {
        return EntityQuery.builder().where(new QueryPredicate.Eq(field, value)).limit(limit).build();
    }

    private static void post(ProcessContext ctx, LedgerEntities.Settings settings) {
        PostInput input = ctx.get("input", PostInput.class);
        List<PostingLine> lines = input.entries().stream()
            .map(line -> new PostingLine(line.accountCode(), line.direction(), line.amount()))
            .toList();
        LedgerPosting.validate(lines, settings.scale()).forEach(ctx::reject);
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
            }
            accountIds.add(account == null ? null : account.id());
        }
        if (ctx.hasViolations()) {
            return;
        }
        Instant booking = input.bookingTime() == null ? ctx.opTime() : input.bookingTime();
        Object transactionId = book(ctx, booking, input.description(), input.reference(), null, lines, accountIds);
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
        for (EntityInstance entry : entries) {
            // The account id stands in for the code: only the side is swapped.
            lines.add(new PostingLine(String.valueOf(entry.<Object>get("accountId")),
                Direction.valueOf(entry.get("direction")), entry.get("amount")).reversed());
            accountIds.add(entry.get("accountId"));
        }
        Instant booking = input.bookingTime() == null ? ctx.opTime() : input.bookingTime();
        String description = "Reversal: " + input.reason();
        Object reversalId = book(ctx, booking, description.length() > 500 ? description.substring(0, 500)
            : description, original.get("reference"), original.id(), lines, accountIds);
        ctx.put(OUTPUT, new ReverseOutput(String.valueOf(reversalId), String.valueOf(original.id()),
            LedgerPosting.total(lines, Direction.DEBIT)));
    }

    /** Registers the transaction and its entries; returns the transaction's id. */
    private static Object book(ProcessContext ctx, Instant booking, String description, Object reference,
        Object reverses, List<PostingLine> lines, List<Object> accountIds) {
        Map<String, Object> transaction = new LinkedHashMap<>();
        transaction.put("bookingTime", booking);
        transaction.put("description", description);
        transaction.put("reference", reference);
        transaction.put("reversesTransactionId", reverses);
        Object transactionId = Objects.requireNonNull(ctx.changes().insert(LedgerEntities.TRANSACTION, transaction));
        for (int i = 0; i < lines.size(); i++) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("transactionId", transactionId);
            entry.put("accountId", accountIds.get(i));
            entry.put("lineNo", BigDecimal.valueOf(i + 1L));
            entry.put("direction", lines.get(i).direction().name());
            entry.put("amount", lines.get(i).amount());
            ctx.changes().insert(LedgerEntities.ENTRY, entry);
        }
        return transactionId;
    }

    @SuppressWarnings("unchecked")
    private static List<EntityInstance> entities(ProcessContext ctx, String key) {
        return (List<EntityInstance>) ctx.get(key);
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
