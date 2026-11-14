package com.jabiz.quizbuks.fee;

import com.jabiz.event.DomainEvent;
import com.jabiz.param.ParamValues;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
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
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * F-M13 (docs/quizbuks/plans/impl/F-M13.md §3.5): money arriving in the platform fee account pays the sponsor's unpaid
 * invoices by itself, the earliest first, as far as the account goes ({@value #AUTO_PAY}, {@code internal()}).
 *
 * <p><b>One invoice, one transaction.</b> A run pays one invoice under the sponsor's wallet lock (as the invoice's own
 * payment takes it) through the same sub-process {@code QB_FEE_SETTLE}, so that a hand's payment and the automatic one
 * never pay an invoice twice and an invoice that fails never holds the others back. It pays no credit note and writes no
 * use of one: the owed of an invoice is its own figure ({@link QbFees#owed}).
 *
 * <p>Two ways start it. An event, only to be quick ({@value #FUNDED_EVENT}, from a cash top-up, platform fee Kudos
 * granted and an operator's transfer): no invoice is named, the earliest owing one is paid, and when something was paid
 * and more are unpaid the run asks for the next one ({@code n} counts them, up to the limit
 * {@value QbParams#FEE_AUTO_PAY_MAX_INVOICES}). The daily job {@value #JOB} takes every other way the account grows (a
 * prepayment given back, an invoice voided, a credit note's cash, a lost event): its items are the unpaid invoices of
 * the sponsors with something to pay them with, so each is a transaction of its own and a failing one is the only one
 * that fails.
 *
 * <p>The switch {@value QbParams#FEE_AUTO_PAY_ENABLED} is read at each run; unless it is {@code true} (a parameter
 * nobody published has no value) nothing is paid.
 */
public final class FeeAutoPay {

    public static final String AUTO_PAY = "QB_FEE_AUTO_PAY";
    public static final String AUTO_PAY_ITEM = "QB_FEE_AUTO_PAY_ITEM";
    public static final String JOB = "qb.fee-auto-pay";
    /** The unpaid invoices of the sponsors with something to pay them with, the earliest first: the daily job's items. */
    public static final String DUE_TEMPLATE = "qb.system.fee-auto-pay-due";
    /** Money reached the sponsor's platform fee account (it carries the sponsor, no amount): {@value AUTO_PAY} runs. */
    public static final String FUNDED_EVENT = "qb.fee-account.funded";
    /** An invoice was paid by it ({sponsorId, invoiceId, transactionId}): the mail gathers them and reads the figures again. */
    public static final String PAID_EVENT = "qb.fee.auto-paid";

    static final int DEFAULT_MAX = 20;

    private static final String INPUT = "input";
    private static final String PARAMS = "params";
    private static final String ON = "on";
    private static final String UNPAID = "unpaid";
    private static final String CHOSEN = "chosen";
    private static final String MORE = "more";
    private static final String ITEM_ID = "itemId";
    private static final String PAID = "paid";

    /**
     * @param sponsorId the sponsor whose unpaid invoices are paid
     * @param n         how many this chain has paid already (0 at its start: the daily job or the first event)
     */
    public record AutoPayInput(@NotBlank @Size(max = 128) String sponsorId, @Min(0) @Max(1000) Integer n) {

        /** The start of a chain: nothing paid yet. */
        public static AutoPayInput start(String sponsorId) {
            return new AutoPayInput(sponsorId, 0);
        }
    }

    /**
     * @param outcome {@code OFF} (the switch is not true), {@code NONE} (nothing unpaid, or nothing to pay with) or
     *                {@code PAID}
     * @param paid    what was paid
     */
    public record AutoPayOutput(String sponsorId, String outcome, String invoiceNo, BigDecimal paid, boolean more) {}

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

    /** How many invoices a chain of events pays: the parameter, held within 1 to 50 (the default when it has no value). */
    public static int max(ParamValues params) {
        BigDecimal value = params == null ? null
            : params.find(QbParams.FEE_AUTO_PAY_MAX_INVOICES, BigDecimal.class).orElse(null);
        return value == null ? DEFAULT_MAX : Math.max(1, Math.min(50, value.intValue()));
    }

    /** The invoices with something owed, the earliest issued first (the invoice number breaks a tie). */
    static List<EntityInstance> owing(List<EntityInstance> unpaid) {
        List<EntityInstance> owing = new ArrayList<>(unpaid.stream()
            .filter(invoice -> QbFees.UNPAID.contains(String.valueOf((Object) invoice.get("status")))
                && QbFees.owed(invoice).signum() > 0)
            .toList());
        owing.sort(Comparator.comparing((EntityInstance invoice) -> instant(invoice.get("issuedAt")))
            .thenComparing(invoice -> String.valueOf((Object) invoice.get("invoiceNo"))));
        return owing;
    }

    /**
     * The invoices a run pays: those with something owed, the earliest issued first (the invoice number breaks a tie),
     * at most {@code max}; and whether more were left.
     */
    public static Chosen choose(List<EntityInstance> unpaid, int max) {
        List<EntityInstance> owing = owing(unpaid);
        return new Chosen(owing.stream().limit(max).toList(), owing.size() > max);
    }

    public record Chosen(List<EntityInstance> invoices, boolean more) {}

    /** Compared as the moments they are, not as the text of them. */
    private static Instant instant(Object value) {
        return value instanceof Instant at ? at : Instant.parse(String.valueOf(value));
    }

    // ---- the process -------------------------------------------------------------------------------------------------

    public static ProcessDefinition<AutoPayInput, AutoPayOutput, ProcessContext> autoPay() {
        return ProcessDefinition.define(AUTO_PAY, 1, AutoPayInput.class, AutoPayOutput.class, ProcessContext.class,
            pb -> pb
                .description("Pays the earliest of a sponsor's unpaid platform fee invoices from the account that pays the "
                    + "platform's fees as far as it goes, when the switch is on, and asks for the next one; the mail tells the "
                    + "chain at its end.")
                .permissions(QbPermissions.FEE_SYSTEM)
                .internal()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = FeeProcesses.context(start, input);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(FeeAutoPay::output)
                // Read now, not at the start of the application: a parameter nobody published is off.
                .step("Load the switch and the limit", LoadParams.<ProcessContext>optional(ProcessContext::opTime, PARAMS,
                    QbParams.FEE_AUTO_PAY_ENABLED, QbParams.FEE_AUTO_PAY_MAX_INVOICES))
                .compute("Is it on", (metadata, ctx) -> ctx.put(ON, on(ctx.get(PARAMS, ParamValues.class))))
                // The wallet lock first, as the invoice's own payment takes it; no lock when nothing is to be done.
                .step("Lock the sponsor's wallet", HoldLock.<ProcessContext>exclusive(
                    ctx -> Boolean.TRUE.equals(ctx.get(ON))
                        ? QbWallets.lockName(QbWallets.SPONSOR, sponsorId(ctx)) : null))
                // One read of the sponsor's unpaid invoices and the account; none of it when the switch is off (the
                // queries then name nobody).
                .step("Read the invoices and the account", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbFees.defaultDataset(QbFees.INVOICE),
                        ctx -> Boolean.TRUE.equals(ctx.get(ON)) ? CreditNotes.unpaid(sponsorId(ctx))
                            : EntityQuery.builder().where(new QueryPredicate.In("invoiceId", List.of())).limit(1).build(),
                        UNPAID),
                    QueryEntities.query(QbFeeAccount.defaultDataset(QbFeeAccount.GRANT),
                        ctx -> FeeSettlement.credits(Boolean.TRUE.equals(ctx.get(ON)) ? sponsorId(ctx) : null,
                            ctx.opTime()), FeeProcesses.CREDITS),
                    QueryEntities.query(QbWallets.defaultDataset(QbWallets.WALLET),
                        ctx -> QbWallets.query(QbWallets.CASH, Boolean.TRUE.equals(ctx.get(ON)) ? sponsorId(ctx) : null),
                        FeeProcesses.CASH_WALLET)))
                .compute("Choose the invoice", (metadata, ctx) -> chooseInvoice(ctx))
                .step("Pay it", CallProcess.<ProcessContext>when(ctx -> ctx.get(CHOSEN) != null, AUTO_PAY_ITEM, 1,
                    ctx -> new ItemInput(String.valueOf(chosen(ctx).id())), PAID))
                .step("Go on", PublishEvent.<ProcessContext>when(FeeAutoPay::goesOn, FUNDED_EVENT,
                    ctx -> Map.of("sponsorId", sponsorId(ctx), "n", next(ctx))))
                // Every payment is told in its own transaction (so none is left untold when a later run of the chain
                // fails); the mail gathers a sponsor's payments over a window and sends one (MailProcesses.feeAutoPaid).
                .step("Ask the mail to tell the sponsor", PublishEvent.<ProcessContext>when(FeeAutoPay::paidSomething,
                    PAID_EVENT, ctx -> Map.of("sponsorId", sponsorId(ctx), "invoiceId", String.valueOf(chosen(ctx).id()),
                        "transactionId", String.valueOf(ctx.get(PAID, ItemPaid.class).transactionId())))));
    }

    /** Paid one in full, more are owing and the chain is within the limit: the next one is asked for. A part paid means
     * the account is used up (the event or the day's job looks again when it grows). */
    private static boolean goesOn(ProcessContext ctx) {
        return paidInFull(ctx) && Boolean.TRUE.equals(ctx.get(MORE))
            && next(ctx) < max(ctx.get(PARAMS, ParamValues.class));
    }

    private static String sponsorId(ProcessContext ctx) {
        return ctx.get(INPUT, AutoPayInput.class).sponsorId();
    }

    private static int next(ProcessContext ctx) {
        Integer n = ctx.get(INPUT, AutoPayInput.class).n();
        return (n == null ? 0 : n) + 1;
    }

    private static EntityInstance chosen(ProcessContext ctx) {
        return (EntityInstance) ctx.get(CHOSEN);
    }

    /** The one invoice this run pays: the earliest owing; none when off or when the account has nothing to give. */
    static void chooseInvoice(ProcessContext ctx) {
        ctx.put(MORE, false);
        if (!Boolean.TRUE.equals(ctx.get(ON))) {
            return;
        }
        BigDecimal available = FeeSettlement.spendable(FeeGate.list(ctx, FeeProcesses.CREDITS),
            FeeGate.first(ctx, FeeProcesses.CASH_WALLET), ctx.opTime());
        if (available.signum() <= 0) {
            return;
        }
        List<EntityInstance> owing = owing(FeeGate.list(ctx, UNPAID));
        if (!owing.isEmpty()) {
            ctx.put(CHOSEN, owing.getFirst());
            ctx.put(MORE, owing.size() > 1);
        }
    }

    private static boolean paidSomething(ProcessContext ctx) {
        return ctx.get(PAID) instanceof ItemPaid item && item.paid().signum() > 0;
    }

    /** The invoice paid is paid in full: the account may still have something for the next one. */
    private static boolean paidInFull(ProcessContext ctx) {
        return paidSomething(ctx) && QbFees.PAID.equals(ctx.get(PAID, ItemPaid.class).status());
    }

    static AutoPayOutput output(ProcessContext ctx) {
        String sponsor = sponsorId(ctx);
        if (!Boolean.TRUE.equals(ctx.get(ON))) {
            return new AutoPayOutput(sponsor, "OFF", null, BigDecimal.ZERO, false);
        }
        if (!paidSomething(ctx)) {
            return new AutoPayOutput(sponsor, "NONE", null, BigDecimal.ZERO, false);
        }
        return new AutoPayOutput(sponsor, "PAID", String.valueOf((Object) chosen(ctx).get("invoiceNo")),
            ctx.get(PAID, ItemPaid.class).paid(),
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
                if (settledSomething(ctx)) {
                    FeeProcesses.recordPayment(ctx);
                }
            })
            .step("Close the reminder", CloseTasks.<ProcessContext>done(
                ctx -> settledSomething(ctx) && Boolean.TRUE.equals(ctx.get(FeeProcesses.PAID_NOW))
                    ? QbFees.overdueTaskSource(FeeProcesses.invoice(ctx).id()) : QbFees.overdueTaskSource("none"))));
    }

    private static boolean payable(ProcessContext ctx) {
        EntityInstance invoice = FeeProcesses.invoice(ctx);
        return invoice != null && QbFees.UNPAID.contains(String.valueOf((Object) invoice.get("status")))
            && QbFees.owed(invoice).signum() > 0;
    }

    private static boolean settledSomething(ProcessContext ctx) {
        return ctx.get(FeeProcesses.SETTLEMENT) instanceof FeeSettlement.SettleOutput settled
            && settled.paid().signum() > 0;
    }

    private static ItemPaid itemOutput(ProcessContext ctx) {
        EntityInstance invoice = FeeProcesses.invoice(ctx);
        boolean paid = settledSomething(ctx);
        FeeSettlement.SettleOutput settled = paid ? ctx.get(FeeProcesses.SETTLEMENT, FeeSettlement.SettleOutput.class)
            : null;
        return new ItemPaid(String.valueOf(invoice.id()), String.valueOf((Object) invoice.get("invoiceNo")),
            paid ? settled.paid() : BigDecimal.ZERO, paid ? settled.transactionId() : null,
            ctx.get(FeeProcesses.OUTCOME) instanceof String status ? status
                : String.valueOf((Object) invoice.get("status")));
    }

    /** The input of the consumer: the sponsor the event names and how many the chain has paid. */
    static AutoPayInput input(DomainEvent event) {
        Object n = event.payload().get("n");
        return new AutoPayInput(String.valueOf(event.payload().get("sponsorId")),
            n == null ? 0 : Integer.valueOf(String.valueOf(n)));
    }
}
