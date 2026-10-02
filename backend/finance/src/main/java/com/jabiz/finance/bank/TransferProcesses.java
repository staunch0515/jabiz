package com.jabiz.finance.bank;

import com.jabiz.entity.Violation;
import com.jabiz.finance.FinancePermissions;
import com.jabiz.finance.gl.JournalEntities;
import com.jabiz.finance.gl.JournalProcesses;
import com.jabiz.finance.gl.SubledgerPosting;
import com.jabiz.numbering.NumberSequence;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.numbering.AssignNumber;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.SaveChanges;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static com.jabiz.finance.bank.BankAccountProcesses.code;
import static com.jabiz.finance.bank.BankAccountProcesses.list;
import static com.jabiz.finance.bank.BankAccountProcesses.trim;

/**
 * Transfers between the company's bank accounts (FIN-BK-002), the treasury's:
 * <ul>
 *   <li>{@code FIN_BANK_TRANSFER_POST}: one transfer between two active accounts of one currency, numbered
 *       {@code TRF-0001}. Received the same day, one entry debits the target's cash account and credits the
 *       source's; received on a later day, one entry moves the money to the in-transit account when it leaves and
 *       another to the target when it arrives; not yet received, only the first, until it is.</li>
 *   <li>{@code FIN_BANK_TRANSFER_RECEIVE}: a transfer in transit arrives: the second entry.</li>
 *   <li>{@code FIN_BANK_TRANSFER_VOID}: a transfer entered in error is taken back: each of its entries reversed on the
 *       void date.</li>
 * </ul>
 * Each statement shows its side of a transfer, so both reconciliations can match it.
 */
public final class TransferProcesses {

    public static final String POST = "FIN_BANK_TRANSFER_POST";
    public static final String RECEIVE = "FIN_BANK_TRANSFER_RECEIVE";
    public static final String VOID = "FIN_BANK_TRANSFER_VOID";

    public static final String NUMBERS = "fin.bank.transfer";

    public static final String UNKNOWN_BANK = "FIN_BANK_TRANSFER_UNKNOWN_BANK";
    public static final String SAME_BANK = "FIN_BANK_TRANSFER_SAME_BANK";
    public static final String CURRENCY = "FIN_BANK_TRANSFER_CURRENCY";
    public static final String DATES = "FIN_BANK_TRANSFER_DATES";
    public static final String NO_IN_TRANSIT = "FIN_BANK_TRANSFER_NO_IN_TRANSIT";
    public static final String NOT_FOUND = "FIN_BANK_TRANSFER_NOT_FOUND";
    public static final String WRONG_STATUS = "FIN_BANK_TRANSFER_STATUS";

    /**
     * @param receivedDate the day it reaches the target account: the sent date when it arrives the same day, empty
     *                     while it is in transit
     */
    public record TransferInput(@NotBlank @Size(max = 20) String fromBank, @NotBlank @Size(max = 20) String toBank,
        @NotNull @DecimalMin("0.01") @Digits(integer = 13, fraction = 2) BigDecimal amount,
        @NotNull LocalDate sentDate, LocalDate receivedDate, @Size(max = 500) String description) {}

    public record ReceiveInput(@NotNull UUID transferId, @NotNull LocalDate receivedDate) {}

    public record VoidInput(@NotNull UUID transferId, @NotNull LocalDate voidDate,
        @NotBlank @Size(max = 500) String reason) {}

    public record TransferOutput(String transferId, String transferNo, String status, List<String> glNos) {}

    static final String INPUT = "input";
    static final String OUTPUT = "output";
    static final String BANKS = "banks";
    static final String SETTINGS = "settings";
    static final String TRANSFERS = "transfers";
    static final String POSTINGS = "postings";
    static final String PLAN = "plan";
    static final String NUMBER = "number";
    static final String NEW_ID = "newId";
    static final String SENT = "sent";
    static final String SENT_OUT = "sentOut";
    static final String RECEIVED = "received";
    static final String RECEIVED_OUT = "receivedOut";
    static final String REVERSALS = "reversals";
    static final String REVERSED = "reversed";

    /** The two accounts and the in-transit account a transfer posts to, once checked. */
    record Plan(EntityInstance from, EntityInstance to, String inTransit) {}

    public static final ProcessDefinition<TransferInput, TransferOutput, ProcessContext> POST_PROCESS =
        ProcessDefinition.define(POST, 1, TransferInput.class, TransferOutput.class, ProcessContext.class, pb -> pb
            .description("Transfers money between two of the company's bank accounts.")
            .permissions(FinancePermissions.BANK_TRANSFER)
            .contextFactory(BankAccountProcesses::withInput)
            .outputMapper(ctx -> ctx.get(OUTPUT, TransferOutput.class))
            .step("Load the bank accounts", QueryEntities.of(BankEntities.BANK_ACCOUNT_DATASET, ctx -> {
                TransferInput input = ctx.get(INPUT, TransferInput.class);
                return banks(code(input.fromBank()), code(input.toBank()));
            }, BANKS))
            .step("Load the settings", QueryEntities.of(BankEntities.SETTINGS_DATASET,
                ctx -> BankSettingsProcesses.current(), SETTINGS))
            .compute("Check the transfer", (metadata, ctx) -> plan(ctx))
            .step("Number the transfer", AssignNumber.when(ctx -> ctx.contains(PLAN), NUMBERS, null, NUMBER))
            .compute("Build its entries", (metadata, ctx) -> create(ctx))
            // The ledger checks that the source document exists: the transfer is saved before it is booked.
            .step("Save the transfer", SaveChanges.now())
            .step("Book it as sent", CallProcess.when(ctx -> ctx.contains(SENT), SubledgerPosting.POST, 1,
                ctx -> ctx.get(SENT), SENT_OUT))
            .step("Book it as received", CallProcess.when(ctx -> ctx.contains(RECEIVED), SubledgerPosting.POST, 1,
                ctx -> ctx.get(RECEIVED), RECEIVED_OUT))
            .compute("Report it", (metadata, ctx) -> report(ctx)));

    public static final ProcessDefinition<ReceiveInput, TransferOutput, ProcessContext> RECEIVE_PROCESS =
        ProcessDefinition.define(RECEIVE, 1, ReceiveInput.class, TransferOutput.class, ProcessContext.class, pb -> pb
            .description("Records that a transfer in transit has reached the target account.")
            .permissions(FinancePermissions.BANK_TRANSFER)
            .contextFactory(BankAccountProcesses::withInput)
            .outputMapper(ctx -> ctx.get(OUTPUT, TransferOutput.class))
            .step("Load the transfer", QueryEntities.of(TransferEntities.TRANSFER_DATASET,
                ctx -> byId(ctx.get(INPUT, ReceiveInput.class).transferId()), TRANSFERS))
            .step("Load the bank accounts", QueryEntities.of(BankEntities.BANK_ACCOUNT_DATASET, ctx -> {
                EntityInstance transfer = list(ctx, TRANSFERS).stream().findFirst().orElse(null);
                return transfer == null ? banks() : banks(transfer.get("fromBank"), transfer.get("toBank"));
            }, BANKS))
            .compute("Check it and build the entry", (metadata, ctx) -> receive(ctx))
            .step("Book it as received", CallProcess.when(ctx -> ctx.contains(RECEIVED), SubledgerPosting.POST, 1,
                ctx -> ctx.get(RECEIVED), RECEIVED_OUT))
            .compute("Report it", (metadata, ctx) -> report(ctx)));

    public static final ProcessDefinition<VoidInput, TransferOutput, ProcessContext> VOID_PROCESS =
        ProcessDefinition.define(VOID, 1, VoidInput.class, TransferOutput.class, ProcessContext.class, pb -> pb
            .description("Takes back a transfer entered in error by reversing its entries.")
            .permissions(FinancePermissions.BANK_TRANSFER)
            .contextFactory(BankAccountProcesses::withInput)
            .outputMapper(ctx -> ctx.get(OUTPUT, TransferOutput.class))
            .step("Load the transfer", QueryEntities.of(TransferEntities.TRANSFER_DATASET,
                ctx -> byId(ctx.get(INPUT, VoidInput.class).transferId()), TRANSFERS))
            .step("Load its entries", QueryEntities.of(JournalEntities.POSTING_DATASET, ctx -> {
                EntityInstance transfer = list(ctx, TRANSFERS).stream().findFirst().orElse(null);
                List<Object> numbers = transfer == null ? List.of() : List.of((Object) transfer.get("transferNo"));
                return EntityQuery.builder().where(new QueryPredicate.In("documentNo", numbers)).limit(10).build();
            }, POSTINGS))
            .compute("Check it", (metadata, ctx) -> planVoid(ctx))
            .step("Reverse its entries", CallProcess.forEach(SubledgerPosting.REVERSE, 1,
                ctx -> ctx.contains(REVERSALS) ? (List<?>) ctx.get(REVERSALS) : List.of(), REVERSED))
            .compute("Report it", (metadata, ctx) -> report(ctx)));

    static void plan(ProcessContext ctx) {
        TransferInput input = ctx.get(INPUT, TransferInput.class);
        String fromCode = code(input.fromBank());
        String toCode = code(input.toBank());
        EntityInstance from = bank(ctx, fromCode);
        EntityInstance to = bank(ctx, toCode);
        if (Objects.equals(fromCode, toCode)) {
            ctx.reject(new Violation("toBank", SAME_BANK, "A transfer is between two different bank accounts",
                Map.of("bankCode", String.valueOf(toCode))));
        }
        for (Object[] side : new Object[][] {{"fromBank", fromCode, from}, {"toBank", toCode, to}}) {
            EntityInstance bank = (EntityInstance) side[2];
            if (bank == null || !Boolean.TRUE.equals(bank.get("active"))) {
                ctx.reject(new Violation((String) side[0], UNKNOWN_BANK, "There is no active bank account " + side[1],
                    Map.of("bankCode", String.valueOf(side[1]))));
            }
        }
        if (from != null && to != null && !Objects.equals(from.get("currency"), to.get("currency"))) {
            // Between currencies it would be an exchange, with its own rate and gain or loss (F7).
            ctx.reject(new Violation("toBank", CURRENCY, "Both accounts of a transfer are in one currency",
                Map.of("from", String.valueOf((Object) from.get("currency")),
                    "to", String.valueOf((Object) to.get("currency")))));
        }
        if (input.receivedDate() != null && input.receivedDate().isBefore(input.sentDate())) {
            ctx.reject(new Violation("receivedDate", DATES, "Money is received on or after the day it is sent",
                Map.of("sentDate", input.sentDate().toString(), "receivedDate", input.receivedDate().toString())));
        }
        String inTransit = BankSettingsProcesses.Settings.of(list(ctx, SETTINGS)).inTransitAccount();
        boolean betweenDays = input.receivedDate() == null || input.receivedDate().isAfter(input.sentDate());
        if (betweenDays && inTransit == null) {
            ctx.reject(new Violation("receivedDate", NO_IN_TRANSIT, "Money received on a later day passes through "
                + "the in-transit account, and the bank settings name none", Map.of()));
        }
        if (!ctx.hasViolations()) {
            ctx.put(PLAN, new Plan(from, to, inTransit));
        }
    }

    static void create(ProcessContext ctx) {
        if (!ctx.contains(PLAN)) {
            return;
        }
        TransferInput input = ctx.get(INPUT, TransferInput.class);
        Plan plan = ctx.get(PLAN, Plan.class);
        String number = ctx.get(NUMBER, String.class);
        boolean sameDay = input.sentDate().equals(input.receivedDate());
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("transferNo", number);
        values.put("fromBank", plan.from().get("bankCode"));
        values.put("toBank", plan.to().get("bankCode"));
        values.put("amount", input.amount());
        values.put("currency", plan.from().get("currency"));
        values.put("sentDate", input.sentDate());
        values.put("receivedDate", input.receivedDate());
        values.put("description", trim(input.description()));
        values.put("inTransitAccount", sameDay ? null : plan.inTransit());
        values.put("status", input.receivedDate() == null ? TransferEntities.IN_TRANSIT : TransferEntities.COMPLETED);
        values.put("preparedBy", ctx.request().actorId());
        Object id = ctx.changes().insert(TransferEntities.TRANSFER, values);
        ctx.put(NEW_ID, id);
        String target = sameDay ? plan.to().get("glAccount") : plan.inTransit();
        ctx.put(SENT, entry(number, id, input.sentDate(), target, plan.from().get("glAccount"), input.amount(),
            description(number, plan.from().get("bankCode"), plan.to().get("bankCode"), input.description(),
                sameDay ? null : "sent")));
        if (input.receivedDate() != null && !sameDay) {
            ctx.put(RECEIVED, entry(number, id, input.receivedDate(), plan.to().get("glAccount"), plan.inTransit(),
                input.amount(), description(number, plan.from().get("bankCode"), plan.to().get("bankCode"),
                    input.description(), "received")));
        }
    }

    static void receive(ProcessContext ctx) {
        ReceiveInput input = ctx.get(INPUT, ReceiveInput.class);
        EntityInstance transfer = transfer(ctx, input.transferId());
        if (transfer == null) {
            return;
        }
        if (!TransferEntities.IN_TRANSIT.equals(transfer.get("status"))) {
            ctx.reject(new Violation("transferId", WRONG_STATUS, "Transfer " + transfer.get("transferNo") + " is not "
                + "in transit", Map.of("status", String.valueOf((Object) transfer.get("status")))));
            return;
        }
        LocalDate sent = transfer.get("sentDate");
        if (input.receivedDate().isBefore(sent)) {
            ctx.reject(new Violation("receivedDate", DATES, "Money is received on or after the day it is sent",
                Map.of("sentDate", sent.toString(), "receivedDate", input.receivedDate().toString())));
            return;
        }
        EntityInstance to = bank(ctx, transfer.get("toBank"));
        String inTransit = transfer.get("inTransitAccount");
        if (inTransit == null) {
            ctx.reject(new Violation("transferId", NO_IN_TRANSIT, "The transfer names no in-transit account",
                Map.of()));
            return;
        }
        if (to == null) {
            ctx.reject(new Violation("transferId", UNKNOWN_BANK, "There is no bank account " + transfer.get("toBank"),
                Map.of("bankCode", String.valueOf((Object) transfer.get("toBank")))));
            return;
        }
        ctx.changes().update(TransferEntities.TRANSFER, transfer.id(), transfer.version(),
            Map.of("receivedDate", input.receivedDate(), "status", TransferEntities.COMPLETED));
        ctx.put(NEW_ID, transfer.id());
        ctx.put(NUMBER, transfer.get("transferNo"));
        ctx.put(RECEIVED, entry(transfer.get("transferNo"), transfer.id(), input.receivedDate(), to.get("glAccount"),
            inTransit, transfer.get("amount"), description(transfer.get("transferNo"), transfer.get("fromBank"),
                transfer.get("toBank"), transfer.get("description"), "received")));
    }

    static void planVoid(ProcessContext ctx) {
        VoidInput input = ctx.get(INPUT, VoidInput.class);
        EntityInstance transfer = transfer(ctx, input.transferId());
        if (transfer == null) {
            return;
        }
        if (TransferEntities.VOID.equals(transfer.get("status"))) {
            ctx.reject(new Violation("transferId", WRONG_STATUS, "Transfer " + transfer.get("transferNo") + " is "
                + "void already", Map.of("status", TransferEntities.VOID)));
            return;
        }
        LocalDate latest = transfer.get("receivedDate") != null ? transfer.get("receivedDate")
            : transfer.get("sentDate");
        if (input.voidDate().isBefore(latest)) {
            ctx.reject(new Violation("voidDate", DATES, "A transfer is voided on or after its last entry, " + latest,
                Map.of("voidDate", input.voidDate().toString(), "latest", latest.toString())));
            return;
        }
        String transferId = String.valueOf(transfer.id());
        List<SubledgerPosting.ReverseInput> reversals = new ArrayList<>();
        list(ctx, POSTINGS).stream()
            .filter(p -> TransferEntities.TRANSFER.equals(p.get("sourceEntity"))
                && transferId.equals(p.get("sourceId")))
            .sorted(Comparator.comparing(p -> (String) p.get("glNo")))
            .forEach(p -> reversals.add(new SubledgerPosting.ReverseInput("BANK",
                String.valueOf((Object) p.get("transactionId")), input.voidDate(),
                "Void of " + transfer.get("transferNo") + ": " + input.reason().trim(), transfer.get("transferNo"),
                TransferEntities.TRANSFER, transferId)));
        ctx.changes().update(TransferEntities.TRANSFER, transfer.id(), transfer.version(), Map.of(
            "status", TransferEntities.VOID, "voidDate", input.voidDate(), "voidReason", input.reason().trim()));
        ctx.put(NEW_ID, transfer.id());
        ctx.put(NUMBER, transfer.get("transferNo"));
        ctx.put(REVERSALS, List.copyOf(reversals));
    }

    @SuppressWarnings("unchecked")
    static void report(ProcessContext ctx) {
        if (!ctx.contains(NEW_ID)) {
            return;
        }
        List<String> glNos = new ArrayList<>();
        for (String key : List.of(SENT_OUT, RECEIVED_OUT)) {
            if (ctx.contains(key)) {
                glNos.add(ctx.get(key, SubledgerPosting.PostOutput.class).glNo());
            }
        }
        if (ctx.contains(REVERSED)) {
            ((List<SubledgerPosting.PostOutput>) ctx.get(REVERSED)).forEach(out -> glNos.add(out.glNo()));
        }
        String status;
        if (ctx.contains(REVERSALS)) {
            status = TransferEntities.VOID;
        } else if (ctx.contains(SENT)) {
            status = ctx.get(INPUT, TransferInput.class).receivedDate() == null ? TransferEntities.IN_TRANSIT
                : TransferEntities.COMPLETED;
        } else {
            status = TransferEntities.COMPLETED;
        }
        ctx.put(OUTPUT, new TransferOutput(String.valueOf(ctx.get(NEW_ID)), ctx.get(NUMBER, String.class), status,
            List.copyOf(glNos)));
    }

    private static SubledgerPosting.PostInput entry(String number, Object id, LocalDate day, String debit,
        String credit, BigDecimal amount, String description) {
        return new SubledgerPosting.PostInput("BANK", day, description, number, TransferEntities.TRANSFER,
            String.valueOf(id), List.of(
                new JournalProcesses.LineInput(debit, amount, null, description, null, null),
                new JournalProcesses.LineInput(credit, null, amount, description, null, null)),
            List.of("BANK"));
    }

    private static String description(String number, String from, String to, String text, String leg) {
        String base = "Transfer " + number + " from " + from + " to " + to + (leg == null ? "" : " (" + leg + ")");
        return text == null || text.isBlank() ? base : base + ": " + text.trim();
    }

    private static EntityInstance transfer(ProcessContext ctx, UUID transferId) {
        EntityInstance transfer = list(ctx, TRANSFERS).stream().findFirst().orElse(null);
        if (transfer == null) {
            ctx.reject(new Violation("transferId", NOT_FOUND, "There is no transfer " + transferId,
                Map.of("transferId", String.valueOf(transferId))));
        }
        return transfer;
    }

    private static EntityInstance bank(ProcessContext ctx, String bankCode) {
        return list(ctx, BANKS).stream().filter(b -> Objects.equals(bankCode, b.get("bankCode"))).findFirst()
            .orElse(null);
    }

    static EntityQuery banks(String... codes) {
        List<Object> present = new ArrayList<>();
        for (String code : codes) {
            if (code != null) {
                present.add(code);
            }
        }
        return EntityQuery.builder().where(new QueryPredicate.In("bankCode", present)).limit(present.size() + 1)
            .build();
    }

    static EntityQuery byId(UUID id) {
        return EntityQuery.builder().where(new QueryPredicate.In("transferId", id == null ? List.of() : List.of(id)))
            .limit(1).build();
    }

    static NumberSequence numbers() {
        return NumberSequence.define(NUMBERS, s -> s.format("TRF-{n:4}").startAt(1));
    }

    private TransferProcesses() {}
}
