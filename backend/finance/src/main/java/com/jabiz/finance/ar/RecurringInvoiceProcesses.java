package com.jabiz.finance.ar;

import com.jabiz.finance.FinancePermissions;
import com.jabiz.finance.calc.Money;
import com.jabiz.finance.gl.GlEntities;
import com.jabiz.entity.Violation;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.jabiz.finance.ar.InvoiceProcesses.list;

/**
 * {@code FIN_RECURRING_INVOICE_RUN} (FIN-AR-014): the invoices of the active recurring templates for the period of a
 * day, as drafts dated on the template's day of that month, which the clerk checks and posts as any other (the tax,
 * the number and the credit limit are the posting's). An invoice is made once per template and period: a second run
 * makes nothing. A job runs it on the first of each month.
 */
public final class RecurringInvoiceProcesses {

    public static final String RUN = "FIN_RECURRING_INVOICE_RUN";
    public static final String JOB = "fin.recurring-invoices";

    public static final String NO_PERIOD = "FIN_RECURRING_INVOICE_NO_PERIOD";

    /** Most templates one run handles, and most of their lines it reads. */
    static final int MAX_TEMPLATES = 200;
    static final int MAX_LINES = 5000;

    /** @param date any day of the period to make the invoices of */
    public record RunInput(@NotNull LocalDate date) {}

    /** @param skipped templates no invoice was made of this time, with the reason */
    public record RunOutput(String periodKey, List<Made> invoices, List<Skipped> skipped) {}

    public record Made(String templateCode, String invoiceId, String customerCode, LocalDate invoiceDate,
        BigDecimal subtotal) {}

    public record Skipped(String templateCode, String reason) {}

    static final String INPUT = "input";
    static final String OUTPUT = "output";
    static final String PERIODS = "periods";
    static final String TEMPLATES = "templates";
    static final String LINES = "lines";
    static final String CUSTOMERS = "customers";
    static final String EXISTING = "existing";
    static final String CURRENCIES = "currencies";

    public static final ProcessDefinition<RunInput, RunOutput, ProcessContext> RUN_PROCESS =
        ProcessDefinition.define(RUN, 1, RunInput.class, RunOutput.class, ProcessContext.class, pb -> pb
            .description("Makes the draft invoices of the recurring templates for a period, once.")
            .permissions(FinancePermissions.INVOICE_PREPARE)
            .contextFactory(InvoiceProcesses::withInput)
            .outputMapper(ctx -> ctx.get(OUTPUT, RunOutput.class))
            .step("Load the period", QueryEntities.of(GlEntities.PERIOD_DATASET,
                ctx -> com.jabiz.finance.gl.SubledgerPosting.periodsOn(ctx.get(INPUT, RunInput.class).date()), PERIODS))
            .step("Load the active templates", QueryEntities.of(ReceiptEntities.RECURRING_DATASET,
                ctx -> EntityQuery.builder().where(new QueryPredicate.Eq("active", true)).limit(MAX_TEMPLATES)
                    .build(), TEMPLATES))
            .step("Load their lines", QueryEntities.of(ReceiptEntities.RECURRING_LINE_DATASET,
                ctx -> EntityQuery.builder().where(new QueryPredicate.In("templateId",
                    new ArrayList<>(list(ctx, TEMPLATES).stream().map(EntityInstance::id).toList())))
                    .limit(MAX_LINES).build(), LINES))
            .step("Load the customers", QueryEntities.of(ArEntities.CUSTOMER_DATASET,
                ctx -> CustomerProcesses.byCodes(list(ctx, TEMPLATES).stream()
                    .map(t -> (String) t.get("customerCode")).toList()), CUSTOMERS))
            .step("Load the currencies", QueryEntities.of(GlEntities.CURRENCY_DATASET,
                ctx -> EntityQuery.builder().where(new QueryPredicate.Eq("active", true)).limit(200).build(),
                CURRENCIES))
            .step("Load the invoices made already", QueryEntities.of(InvoiceEntities.INVOICE_DATASET,
                ctx -> EntityQuery.builder().where(new QueryPredicate.In("recurringKey", new ArrayList<>(keys(ctx))))
                    .limit(MAX_TEMPLATES).build(), EXISTING))
            .compute("Make the invoices", (metadata, ctx) -> run(ctx)));

    static void run(ProcessContext ctx) {
        EntityInstance period = period(ctx);
        LocalDate date = ctx.get(INPUT, RunInput.class).date();
        if (period == null) {
            ctx.reject(new Violation("date", NO_PERIOD, "No regular fiscal period holds " + date,
                Map.of("date", date.toString())));
            return;
        }
        LocalDate start = period.get("startDate");
        LocalDate end = period.get("endDate");
        Set<String> made = new HashSet<>();
        list(ctx, EXISTING).forEach(i -> made.add(i.get("recurringKey")));
        Map<String, EntityInstance> customers = new LinkedHashMap<>();
        list(ctx, CUSTOMERS).forEach(c -> customers.put(c.get("customerCode"), c));
        Map<String, Integer> scales = new LinkedHashMap<>();
        list(ctx, CURRENCIES).forEach(c -> scales.put(c.get("currencyCode"),
            c.<BigDecimal>get("minorUnits").intValueExact()));
        List<EntityInstance> templates = new ArrayList<>(list(ctx, TEMPLATES));
        templates.sort(Comparator.comparing(t -> t.<String>get("templateCode")));
        List<Made> invoices = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        for (EntityInstance template : templates) {
            LocalDate from = template.get("startDate");
            LocalDate until = template.get("endDate");
            String key = key(template, period);
            int day = template.<BigDecimal>get("invoiceDay").intValueExact();
            LocalDate invoiceDate = start.plusDays(day - 1L).isAfter(end) ? end : start.plusDays(day - 1L);
            if (invoiceDate.isBefore(from) || until != null && until.isBefore(invoiceDate) || made.contains(key)) {
                continue;
            }
            EntityInstance customer = customers.get(template.<String>get("customerCode"));
            List<EntityInstance> lines = list(ctx, LINES).stream()
                .filter(l -> template.id().equals(l.get("templateId")))
                .sorted(Comparator.comparing(l -> l.<BigDecimal>get("lineNo")))
                .toList();
            String reason = customer == null || !"ACTIVE".equals(customer.get("status"))
                ? "there is no active customer " + template.get("customerCode")
                : lines.isEmpty() ? "it has no lines"
                : !scales.containsKey(customer.<String>get("currency"))
                    ? "there is no active currency " + customer.get("currency") : null;
            if (reason != null) {
                skipped.add(new Skipped(template.get("templateCode"), reason));
                continue;
            }
            int scale = scales.get(customer.<String>get("currency"));
            BigDecimal subtotal = BigDecimal.ZERO;
            List<Map<String, Object>> rows = new ArrayList<>();
            for (EntityInstance line : lines) {
                BigDecimal amount = Money.round(line.<BigDecimal>get("quantity").multiply(line.get("unitPrice")), scale);
                subtotal = subtotal.add(amount);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("lineNo", line.get("lineNo"));
                row.put("description", line.get("description"));
                row.put("quantity", line.get("quantity"));
                row.put("unitPrice", line.get("unitPrice"));
                row.put("amount", amount);
                row.put("revenueAccount", line.get("revenueAccount"));
                row.put("taxCode", line.get("taxCode"));
                row.put("department", line.get("department"));
                row.put("location", line.get("location"));
                rows.add(row);
            }
            Map<String, Object> header = new LinkedHashMap<>();
            header.put("kind", InvoiceEntities.INVOICE_KIND);
            header.put("customerCode", customer.get("customerCode"));
            header.put("invoiceDate", invoiceDate);
            header.put("currency", customer.get("currency"));
            header.put("termsCode", customer.get("termsCode"));
            header.put("taxCode", customer.get("taxCode"));
            header.put("description", template.get("description"));
            header.put("source", InvoiceEntities.RECURRING);
            header.put("status", InvoiceEntities.DRAFT);
            header.put("preparedBy", ctx.request().actorId());
            header.put("subtotal", subtotal);
            header.put("recurringKey", key);
            Object id = ctx.changes().insert(InvoiceEntities.INVOICE, header);
            for (Map<String, Object> row : rows) {
                row.put("invoiceId", id);
                ctx.changes().insert(InvoiceEntities.LINE, row);
            }
            made.add(key);
            invoices.add(new Made(template.get("templateCode"), String.valueOf(id), customer.get("customerCode"),
                invoiceDate, subtotal));
        }
        ctx.put(OUTPUT, new RunOutput(period.get("periodKey"), List.copyOf(invoices), List.copyOf(skipped)));
    }

    /** "SUPPORT-C200/2026-02": one invoice per template and period. */
    private static String key(EntityInstance template, EntityInstance period) {
        return template.get("templateCode") + "/" + period.get("periodKey");
    }

    private static Set<String> keys(ProcessContext ctx) {
        Set<String> keys = new HashSet<>();
        EntityInstance period = period(ctx);
        if (period != null) {
            list(ctx, TEMPLATES).forEach(t -> keys.add(key(t, period)));
        }
        return keys;
    }

    /** The regular period holding the day: not the opening or adjustment period. */
    private static EntityInstance period(ProcessContext ctx) {
        return list(ctx, PERIODS).stream()
            .filter(p -> !Boolean.TRUE.equals(p.get("opening")) && !Boolean.TRUE.equals(p.get("adjustment")))
            .findFirst().orElse(null);
    }

    private RecurringInvoiceProcesses() {}
}
