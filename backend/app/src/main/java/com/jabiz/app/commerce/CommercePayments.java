package com.jabiz.app.commerce;

import com.jabiz.context.RequestContext;
import com.jabiz.event.DomainEvent;
import com.jabiz.event.EventSubscription;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.retention.RetentionPolicy;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.PermissionDeniedException;
import com.jabiz.runtime.inbound.InboundEntities;
import com.jabiz.runtime.inbound.InboundEvents;
import com.jabiz.runtime.inbound.LoadInboundEvent;
import com.jabiz.runtime.inbound.ReceivedEvent;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.task.CreateTask;
import com.jabiz.runtime.task.TaskSpec;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Period;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static com.jabiz.app.commerce.CommerceEntities.CANCELLED;
import static com.jabiz.app.commerce.CommerceEntities.ORDER;
import static com.jabiz.app.commerce.CommerceEntities.ORDER_DATASET;

/**
 * Payments of orders reported by a payment provider (ROADMAP phase 16d; decision D37), the sample of inbound webhooks
 * and the pattern applications copy. The provider {@value #PROVIDER} (configured for development and tests only,
 * scheme {@code stripe}) sends {@code payment_intent.succeeded} with the order's number in the payment's metadata. The
 * platform verifies, stores and publishes it; the consumer {@value #CONSUMER} runs {@value #RECORD_PAYMENT} once for
 * each, as the system: {@code internal()} refuses anyone else at the start (decision D41); the process's own check of
 * the actor predates that and stays as a second line.
 *
 * <p>A verified signature says who sent the event, not that it fits: the process trusts nothing in it. It loads only an
 * event of its own provider and type, and records the payment only when everything fits:
 * <ul>
 *   <li>the payment's id and the order number have the expected shape;</li>
 *   <li>the event is from live mode ({@code livemode: true}), or test mode is accepted
 *       ({@code commerce.payments.accept-test-mode}, true for the sample, which only sees test payments);</li>
 *   <li>the currency is {@value #CURRENCY}, and {@code amount_received} is the order's total. Stripe states amounts in
 *       the currency's minor unit; the yen has none, so here the amount is in yen (another currency would be scaled
 *       by its minor unit before comparing);</li>
 *   <li>the order exists, is not cancelled and is not paid by another payment.</li>
 * </ul>
 * The order then gets {@code paidTime} (when the payment was received) and {@code paymentRef}; the same payment again
 * changes nothing. Anything else becomes a task for whoever may write orders ({@value #CHECK_PERMISSION}) that names
 * the reason, the amount and the currency, rather than a failure: a failing consumer would be retried for nothing.
 * Received events are kept seven years from their receipt, like other accounting evidence.
 */
@Configuration
class CommercePayments {

    static final String PROVIDER = "demo-pay";
    static final String SUCCEEDED = "payment_intent.succeeded";
    static final String CONSUMER = "commerce.payment-received";
    static final String RECORD_PAYMENT = "ORDER_RECORD_PAYMENT";
    static final String CHECK_PERMISSION = "commerce.order.place";
    static final String CHECK_TASK = "commerce.payment-check";
    static final String CURRENCY = "jpy";

    /** Stripe's ids (letters, digits, underscores; hyphens allowed), at most the column's 255. */
    static final Pattern PAYMENT_REF = Pattern.compile("[A-Za-z0-9_-]{1,255}");
    /** As the order number's own rule (CommerceEntities). */
    static final Pattern ORDER_NO = Pattern.compile("[A-Z0-9][A-Z0-9-]{1,29}");
    private static final int MAX_SHOWN = 40;
    private static final int MAX_SOURCE = 200;

    /** @param inboundEventId the received event ({@code InboundEvent}) */
    record PaymentInput(@NotBlank String inboundEventId) {}

    /**
     * @param outcome {@code RECORDED}, {@code ALREADY_RECORDED} or {@code CHECK} (a task was opened)
     * @param reason  why a check, or null
     */
    record PaymentOutput(String orderNo, String paymentRef, String outcome, String reason) {}

    static final String INPUT = "input";
    static final String PAYMENT = "payment";
    static final String ORDERS = "orders";
    static final String OUTCOME = "outcome";
    static final String REASON = "reason";

    static ProcessDefinition<PaymentInput, PaymentOutput, ProcessContext> recordProcess(boolean acceptTestMode) {
        return ProcessDefinition.define(RECORD_PAYMENT, 1, PaymentInput.class, PaymentOutput.class,
            ProcessContext.class, pb -> pb
                .description("Records a payment the payment provider reported on its order.")
                .permissions("commerce.order.pay")
                .internal()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(ctx -> new PaymentOutput(shown(text(ctx, "data.object.metadata.orderNo")),
                    shown(text(ctx, "data.object.id")), (String) ctx.get(OUTCOME), (String) ctx.get(REASON)))
                .compute("Refuse other callers", (metadata, ctx) -> requireSystem(ctx))
                .step("Load the payment", LoadInboundEvent.of(PROVIDER, SUCCEEDED,
                    ctx -> ctx.get(INPUT, PaymentInput.class).inboundEventId(), PAYMENT))
                .step("Find the order", QueryEntities.of(ORDER_DATASET, CommercePayments::orderQuery, ORDERS))
                .compute("Record the payment", (metadata, ctx) -> record(ctx, acceptTestMode))
                .step("Ask for a check", CreateTask.when(ctx -> "CHECK".equals(ctx.get(OUTCOME)),
                    CommercePayments::checkTask)));
    }

    @Bean
    ProcessDefinition<PaymentInput, PaymentOutput, ProcessContext> orderRecordPaymentProcess(
        @Value("${commerce.payments.accept-test-mode:true}") boolean acceptTestMode) {
        return recordProcess(acceptTestMode);
    }

    @Bean
    EventSubscription<PaymentInput> paymentReceivedSubscription(
        ProcessDefinition<PaymentInput, PaymentOutput, ProcessContext> orderRecordPaymentProcess) {
        return EventSubscription.of(CONSUMER, InboundEvents.type(PROVIDER, SUCCEEDED), orderRecordPaymentProcess,
            CommercePayments::input);
    }

    /** The bodies of received events may hold personal data: kept as long as accounting evidence, then reported. */
    @Bean
    RetentionPolicy inboundEventRetention() {
        return RetentionPolicy.of(InboundEntities.EVENT).keep(Period.ofYears(7)).from("receivedTime");
    }

    private static PaymentInput input(DomainEvent event) {
        return new PaymentInput(InboundEvents.ref(event).inboundEventId());
    }

    /** Only the event delivery records payments: the process API, even for an administrator, does not. */
    static void requireSystem(ProcessContext ctx) {
        if (!RequestContext.SYSTEM_ACTOR.equals(ctx.request().actorId())) {
            throw new PermissionDeniedException("commerce.order.pay",
                "Payments are recorded from the payment provider's events only");
        }
    }

    private static ReceivedEvent payment(ProcessContext ctx) {
        return ctx.get(PAYMENT, ReceivedEvent.class);
    }

    /** The text at the path of the payment's body; null when absent or not text. */
    private static String text(ProcessContext ctx, String path) {
        return ctx.contains(PAYMENT) && payment(ctx).at(path) instanceof String text ? text : null;
    }

    private static String orderNo(ProcessContext ctx) {
        String value = text(ctx, "data.object.metadata.orderNo");
        return value != null && ORDER_NO.matcher(value).matches() ? value : null;
    }

    static EntityQuery orderQuery(ProcessContext ctx) {
        String orderNo = orderNo(ctx);
        return EntityQuery.builder()
            .where(new QueryPredicate.In("orderNo", orderNo == null ? List.of() : List.of(orderNo)))
            .limit(1).build();
    }

    @SuppressWarnings("unchecked")
    static void record(ProcessContext ctx, boolean acceptTestMode) {
        ReceivedEvent payment = payment(ctx);
        List<EntityInstance> orders = (List<EntityInstance>) ctx.get(ORDERS);
        EntityInstance order = orders == null || orders.isEmpty() ? null : orders.getFirst();
        String ref = text(ctx, "data.object.id");
        String orderNo = text(ctx, "data.object.metadata.orderNo");
        Object amount = payment.at("data.object.amount_received");
        String reason;
        if (ref == null || !PAYMENT_REF.matcher(ref).matches()) {
            reason = "INVALID_PAYMENT_REF";
        } else if (orderNo == null || !ORDER_NO.matcher(orderNo).matches()) {
            reason = "INVALID_ORDER_NO";
        } else if (payment.livemode() == null || (!payment.livemode() && !acceptTestMode)) {
            reason = "TEST_MODE";
        } else if (!CURRENCY.equals(text(ctx, "data.object.currency"))) {
            reason = "CURRENCY";
        } else if (!(amount instanceof BigDecimal)) {
            reason = "NO_AMOUNT";
        } else if (order == null) {
            reason = "NO_ORDER";
        } else if (CANCELLED.equals(order.get("status"))) {
            reason = "CANCELLED";
        } else if (order.get("paymentRef") != null) {
            if (ref.equals(order.get("paymentRef"))) {
                ctx.put(OUTCOME, "ALREADY_RECORDED");
                return;
            }
            reason = "PAID_OTHER";
        } else if (((BigDecimal) amount).compareTo(order.<BigDecimal>get("totalAmount")) != 0) {
            reason = "AMOUNT_MISMATCH";
        } else {
            Map<String, Object> paid = new LinkedHashMap<>();
            paid.put("paidTime", payment.receivedTime());
            paid.put("paymentRef", ref);
            ctx.changes().update(ORDER, order.id(), order.version(), paid);
            ctx.put(OUTCOME, "RECORDED");
            return;
        }
        ctx.put(OUTCOME, "CHECK");
        ctx.put(REASON, reason);
    }

    @SuppressWarnings("unchecked")
    static TaskSpec checkTask(ProcessContext ctx) {
        ReceivedEvent payment = payment(ctx);
        Object amount = payment.at("data.object.amount_received");
        Map<String, String> params = new LinkedHashMap<>();
        params.put("orderNo", shown(text(ctx, "data.object.metadata.orderNo")));
        params.put("payment", shown(text(ctx, "data.object.id")));
        params.put("amount", amount instanceof BigDecimal decimal ? shown(decimal.toPlainString()) : "-");
        params.put("currency", shown(text(ctx, "data.object.currency")));
        params.put("reason", String.valueOf(ctx.get(REASON)));
        List<EntityInstance> orders = (List<EntityInstance>) ctx.get(ORDERS);
        TaskSpec task = TaskSpec.forPermission(CHECK_TASK, "task.commerce.payment-check", params, CHECK_PERMISSION)
            .source(source(payment));
        return orders == null || orders.isEmpty() ? task.about(InboundEntities.EVENT, payment.inboundEventId())
            : task.about(ORDER, orders.getFirst().id());
    }

    /** {@code payment:<provider>:<external id>}, stable across replays; a long id by its SHA-256. */
    private static String source(ReceivedEvent payment) {
        String key = "payment:" + payment.provider() + ":" + payment.externalEventId();
        if (key.length() <= MAX_SOURCE) {
            return key;
        }
        try {
            return "payment:" + payment.provider() + ":sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(payment.externalEventId().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A value of the sender as a title may show it: printable, at most 40 characters; "-" for none. */
    static String shown(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        StringBuilder kept = new StringBuilder();
        value.codePoints().filter(c -> !Character.isISOControl(c)).forEach(kept::appendCodePoint);
        String text = kept.toString().strip();
        if (text.isEmpty()) {
            return "-";
        }
        return text.length() <= MAX_SHOWN ? text : text.substring(0, MAX_SHOWN) + "…";
    }
}
