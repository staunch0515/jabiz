package com.jabiz.quizbuks.fee;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.param.ParamValues;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.wallet.QbWallets;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.LoadEntity;
import com.jabiz.runtime.process.steps.LoadParams;
import com.jabiz.runtime.process.steps.PublishEvent;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.task.CloseTasks;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * F-M13 (docs/quizbuks/plans/impl/F-M13.md §3.5): money arriving in the platform fee account pays the sponsor's unpaid
 * invoices by itself, the earliest first, as far as the account goes ({@value #AUTO_PAY}, {@code internal()}).
 *
 * <p>What starts it is an event, only to be quick ({@value #FUNDED_EVENT}, from a cash top-up, platform fee Kudos
 * granted and an operator's transfer); every other way the account grows (a prepayment given back, an invoice voided, a
 * credit note's cash) is left to the daily job {@value #JOB}, whose items are the sponsors with something unpaid and
 * something to pay it with. Both run the same process, which takes the sponsor's wallet lock first (as the invoice's own
 * payment does) and pays through the same sub-process {@code QB_FEE_SETTLE}, one invoice at a time in one transaction,
 * so that a hand's payment and the automatic one never pay an invoice twice. It pays no credit note and writes no use of
 * one: the owed of an invoice is its own figure ({@link QbFees#owed}).
 *
 * <p>The switch {@value QbParams#FEE_AUTO_PAY_ENABLED} is read at each run; unless it is {@code true} (a parameter
 * nobody published has no value) nothing is paid.
 */
public final class FeeAutoPay {

    public static final String AUTO_PAY = "QB_FEE_AUTO_PAY";
    public static final String AUTO_PAY_ITEM = "QB_FEE_AUTO_PAY_ITEM";
    public static final String JOB = "qb.fee-auto-pay";
    /** The sponsors with an unpaid invoice and something to pay it with: the items of the daily job. */
    public static final String DUE_TEMPLATE = "qb.system.fee-auto-pay-due";
    /** Money reached the sponsor's platform fee account (it carries the sponsor, no amount): {@value AUTO_PAY} runs. */
    public static final String FUNDED_EVENT = "qb.fee-account.funded";
    /** Invoices were paid by it: the numbers and the payments, from which the mail tells and reads the figures again. */
    public static final String PAID_EVENT = "qb.fee.auto-paid";

    static final int DEFAULT_MAX = 20;

    private static final String INPUT = "input";
    private static final String PARAMS = "params";
    private static final String ON = "on";
    private static final String UNPAID = "unpaid";
    private static final String CHOSEN = "chosen";
    private static final String MORE = "more";
    private static final String PAID = "paid";
    private static final String ITEM_PAID = "itemPaid";
    private static final String ITEM_ID = "itemId";

    /** @param sponsorId the sponsor whose unpaid invoices are paid */
    public record AutoPayInput(@NotBlank @Size(max = 128) String sponsorId) {}

    /**
     * @param outcome {@code OFF} (the switch is not true), {@code NONE} (nothing unpaid, or nothing to pay with) or
     *                {@code PAID}
     * @param count   the invoices paid in whole or in part
     * @param total   what was paid
     * @param more    whether unpaid invoices were left that this run did not reach (the run is asked again)
     */
    public record AutoPayOutput(String sponsorId, String outcome, int count, BigDecimal total, boolean more) {}

    public record ItemInput(@NotBlank @Size(max = 36) String invoiceId) {}

    /** What one invoice was paid: {@code paid} 0 when the account had nothing left for it. */
    public record ItemPaid(String invoiceId, String invoiceNo, BigDecimal paid, String transactionId, String status) {}

    private FeeAutoPay() {}

    // ---- rules (pure) ------------------------------------------------------------------------------------------------

    /** Whether the switch is {@code true}: anything else (not set, malformed) is off. */
    static boolean on(ParamValues params) {
        return params != null && Boolean.TRUE.equals(params.find(QbParams.FEE_AUTO_PAY_ENABLED, Boolean.class)
            .orElse(null));
    }

    /** How many invoices one run pays: the parameter when it is within 1 to 50, else the default. */
    static int max(ParamValues params) {
        BigDecimal value = params == null ? null
            : params.find(QbParams.FEE_AUTO_PAY_MAX_INVOICES, BigDecimal.class).orElse(null);
        return value == null || value.intValue() < 1 || value.intValue() > 50 ? DEFAULT_MAX : value.intValue();
    }

    /**
     * The invoices a run pays: those with something owed, the earliest issued first (the invoice number breaks a tie),
     * at most {@code max}; and whether more were left.
     */
    static Chosen choose(List<EntityInstance> unpaid, int max) {
        List<EntityInstance> owing = new ArrayList<>(unpaid.stream()
            .filter(invoice -> QbFees.UNPAID.contains(String.valueOf((Object) invoice.get("status")))
                && QbFees.owed(invoice).signum() > 0)
            .toList());
        owing.sort(Comparator.comparing((EntityInstance invoice) -> String.valueOf((Object) invoice.get("issuedAt")))
            .thenComparing(invoice -> String.valueOf((Object) invoice.get("invoiceNo"))));
        return new Chosen(owing.stream().limit(max).toList(), owing.size() > max);
    }

    record Chosen(List<EntityInstance> invoices, boolean more) {}

    // ---- the process -------------------------------------------------------------------------------------------------

    public static ProcessDefinition<AutoPayInput, AutoPayOutput, ProcessContext> autoPay() {
        return ProcessDefinition.define(AUTO_PAY, 1, AutoPayInput.class, AutoPayOutput.class, ProcessContext.class,
            pb -> pb
                .description("Pays a sponsor's unpaid platform fee invoices, the earliest first, from the account that "
                    + "pays the platform's fees as far as it goes, when the switch is on.")
                .permissions(QbPermissions.FEE_SYSTEM)
                .internal()
                .contextFactory(FeeProcesses::context)
                .outputMapper(FeeAutoPay::output)
                // Read now, not at the start of the application: a parameter nobody published is off.
                .step("Load the switch and the limit", LoadParams.<ProcessContext>optional(ProcessContext::opTime, PARAMS,
                    QbParams.FEE_AUTO_PAY_ENABLED, QbParams.FEE_AUTO_PAY_MAX_INVOICES))
                .compute("Is it on", (metadata, ctx) -> ctx.put(ON, on(ctx.get(PARAMS, ParamValues.class))))
                // The wallet lock first, as the invoice's own payment takes it; no lock when nothing is to be done.
                .step("Lock the sponsor's wallet", HoldLock.<ProcessContext>exclusive(
                    ctx -> Boolean.TRUE.equals(ctx.get(ON))
                        ? QbWallets.lockName(QbWallets.SPONSOR, sponsorId(ctx)) : null))
                .step("Read the unpaid invoices", QueryEntities.<ProcessContext>of(
                    QbFees.defaultDataset(QbFees.INVOICE),
                    ctx -> Boolean.TRUE.equals(ctx.get(ON)) ? CreditNotes.unpaid(sponsorId(ctx))
                        : EntityQuery.builder().where(new QueryPredicate.In("invoiceId", List.of())).limit(1).build(),
                    UNPAID))
                .step("Load the account", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbFeeAccount.defaultDataset(QbFeeAccount.GRANT),
                        ctx -> FeeSettlement.credits(sponsorId(ctx), ctx.opTime()), FeeProcesses.CREDITS),
                    QueryEntities.query(QbWallets.defaultDataset(QbWallets.WALLET),
                        ctx -> QbWallets.query(QbWallets.CASH, sponsorId(ctx)), FeeProcesses.CASH_WALLET)))
                .compute("Choose the invoices", (metadata, ctx) -> chooseInvoices(ctx))
                .step("Pay each", CallProcess.forEach(AUTO_PAY_ITEM, 1, FeeAutoPay::itemInputs, PAID))
                .compute("Count what was paid", (metadata, ctx) -> count(ctx))
                .step("Ask the mail to tell the sponsor", PublishEvent.<ProcessContext>when(
                    ctx -> ctx.get(ITEM_PAID) instanceof List<?> list && !list.isEmpty(), PAID_EVENT,
                    FeeAutoPay::paidPayload))
                // Left unpaid beyond what a run takes, and something was paid now: asked again (nothing paid: the day's job).
                .step("Go on", PublishEvent.<ProcessContext>when(
                    ctx -> Boolean.TRUE.equals(ctx.get(MORE)) && ctx.get(ITEM_PAID) instanceof List<?> list
                        && !list.isEmpty(), FUNDED_EVENT, ctx -> Map.of("sponsorId", sponsorId(ctx)))));
    }

    private static String sponsorId(ProcessContext ctx) {
        return ctx.get(INPUT, AutoPayInput.class).sponsorId();
    }

    static void chooseInvoices(ProcessContext ctx) {
        if (!Boolean.TRUE.equals(ctx.get(ON))) {
            ctx.put(CHOSEN, List.of());
            ctx.put(MORE, false);
            return;
        }
        BigDecimal available = FeeSettlement.spendable(FeeGate.list(ctx, FeeProcesses.CREDITS),
            FeeGate.first(ctx, FeeProcesses.CASH_WALLET), ctx.opTime());
        if (available.signum() <= 0) {
            ctx.put(CHOSEN, List.of());
            ctx.put(MORE, false);
            return;
        }
        Chosen chosen = choose(FeeGate.list(ctx, UNPAID), max(ctx.get(PARAMS, ParamValues.class)));
        ctx.put(CHOSEN, chosen.invoices());
        ctx.put(MORE, chosen.more());
    }

    @SuppressWarnings("unchecked")
    static List<ItemInput> itemInputs(ProcessContext ctx) {
        List<EntityInstance> chosen = (List<EntityInstance>) ctx.get(CHOSEN);
        return chosen == null ? List.of() : chosen.stream().map(invoice -> new ItemInput(String.valueOf(invoice.id())))
            .toList();
    }

    /** The items that paid something, in their order. */
    @SuppressWarnings("unchecked")
    static void count(ProcessContext ctx) {
        List<Object> outputs = (List<Object>) ctx.get(PAID);
        List<ItemPaid> paid = outputs == null ? List.of() : outputs.stream().map(ItemPaid.class::cast)
            .filter(item -> item.paid().signum() > 0).toList();
        ctx.put(ITEM_PAID, paid);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> paidPayload(ProcessContext ctx) {
        List<ItemPaid> paid = (List<ItemPaid>) ctx.get(ITEM_PAID);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sponsorId", sponsorId(ctx));
        // The numbers and the payments, not the amounts: the mail reads the figures again (what was paid by these
        // payments, what is still owed of these invoices) and does not trust a number in an event.
        payload.put("invoiceNos", paid.stream().map(ItemPaid::invoiceNo).toList());
        payload.put("transactionIds", paid.stream().map(ItemPaid::transactionId).toList());
        return payload;
    }

    @SuppressWarnings("unchecked")
    static AutoPayOutput output(ProcessContext ctx) {
        String sponsor = sponsorId(ctx);
        if (!Boolean.TRUE.equals(ctx.get(ON))) {
            return new AutoPayOutput(sponsor, "OFF", 0, BigDecimal.ZERO, false);
        }
        List<ItemPaid> paid = ctx.get(ITEM_PAID) instanceof List<?> list ? (List<ItemPaid>) list : List.of();
        BigDecimal total = paid.stream().map(ItemPaid::paid).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new AutoPayOutput(sponsor, paid.isEmpty() ? "NONE" : "PAID", paid.size(), total,
            Boolean.TRUE.equals(ctx.get(MORE)));
    }

    // ---- one invoice -------------------------------------------------------------------------------------------------

    /**
     * One invoice paid as far as the account goes, under the wallet lock the run holds: its payment through
     * {@code QB_FEE_SETTLE} (the fee credit first, then the cash, the same sub-process the hand's payment uses), the
     * invoice's state, the overdue task closed once it is paid. An invoice paid meanwhile, or when the account has
     * nothing left, is not touched.
     */
    public static ProcessDefinition<ItemInput, ItemPaid, ProcessContext> autoPayItem() {
        return ProcessDefinition.define(AUTO_PAY_ITEM, 1, ItemInput.class, ItemPaid.class, ProcessContext.class, pb -> pb
            .description("Pays one platform fee invoice from the account that pays the platform's fees as far as it goes "
                + "(a part of an automatic payment).")
            .permissions(QbPermissions.FEE_SYSTEM)
            .subProcessOnly()
            .contextFactory((start, input) -> {
                ProcessContext ctx = FeeProcesses.context(start, input);
                ctx.put(ITEM_ID, input.invoiceId());
                return ctx;
            })
            .outputMapper(FeeAutoPay::itemOutput)
            .step("Load the invoice", LoadEntity.by(QbFees.defaultDataset(QbFees.INVOICE), ITEM_ID,
                FeeProcesses.INVOICE))
            .step("Pay", CallProcess.<ProcessContext>when(FeeAutoPay::payable, FeeSettlement.SETTLE, 1,
                ctx -> new FeeSettlement.SettleInput(String.valueOf((Object) FeeProcesses.invoice(ctx).get("sponsorId")),
                    QbFees.INVOICE, String.valueOf(FeeProcesses.invoice(ctx).id()),
                    QbFees.owed(FeeProcesses.invoice(ctx)), FeeProcesses.invoice(ctx).get("invoiceNo")),
                FeeProcesses.SETTLEMENT))
            .compute("Record the payment", (metadata, ctx) -> {
                if (paidSomething(ctx)) {
                    FeeProcesses.recordPayment(ctx);
                }
            })
            .step("Close the reminder", CloseTasks.<ProcessContext>done(
                ctx -> paidSomething(ctx) && Boolean.TRUE.equals(ctx.get(FeeProcesses.PAID_NOW))
                    ? QbFees.overdueTaskSource(FeeProcesses.invoice(ctx).id()) : QbFees.overdueTaskSource("none"))));
    }

    private static boolean payable(ProcessContext ctx) {
        EntityInstance invoice = FeeProcesses.invoice(ctx);
        return invoice != null && QbFees.UNPAID.contains(String.valueOf((Object) invoice.get("status")))
            && QbFees.owed(invoice).signum() > 0;
    }

    private static boolean paidSomething(ProcessContext ctx) {
        return ctx.get(FeeProcesses.SETTLEMENT) instanceof FeeSettlement.SettleOutput settled
            && settled.paid().signum() > 0;
    }

    private static ItemPaid itemOutput(ProcessContext ctx) {
        EntityInstance invoice = FeeProcesses.invoice(ctx);
        boolean paid = paidSomething(ctx);
        FeeSettlement.SettleOutput settled = paid ? ctx.get(FeeProcesses.SETTLEMENT, FeeSettlement.SettleOutput.class)
            : null;
        return new ItemPaid(String.valueOf(invoice.id()), String.valueOf((Object) invoice.get("invoiceNo")),
            paid ? settled.paid() : BigDecimal.ZERO, paid ? settled.transactionId() : null,
            ctx.get(FeeProcesses.OUTCOME) instanceof String status ? status
                : String.valueOf((Object) invoice.get("status")));
    }

    /** The input of the consumer: the sponsor the event names (the process re-reads everything else). */
    static AutoPayInput input(com.jabiz.event.DomainEvent event) {
        return new AutoPayInput(String.valueOf(event.payload().get("sponsorId")));
    }
}
