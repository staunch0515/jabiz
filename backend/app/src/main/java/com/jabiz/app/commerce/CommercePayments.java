package com.jabiz.app.commerce;

import com.jabiz.event.DomainEvent;
import com.jabiz.event.EventSubscription;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.retention.RetentionPolicy;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.inbound.InboundEntities;
import com.jabiz.runtime.inbound.InboundEvents;
import com.jabiz.runtime.inbound.LoadInboundEvent;
import com.jabiz.runtime.inbound.ReceivedEvent;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.task.CreateTask;
import com.jabiz.runtime.task.TaskSpec;
import jakarta.validation.constraints.NotBlank;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.time.Period;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.jabiz.app.commerce.CommerceEntities.CANCELLED;
import static com.jabiz.app.commerce.CommerceEntities.ORDER;
import static com.jabiz.app.commerce.CommerceEntities.ORDER_DATASET;

/**
 * Payments of orders reported by a payment provider (ROADMAP phase 16d; decision D37), the sample of inbound webhooks.
 * The provider {@value #PROVIDER} (configured for development and tests only, scheme {@code stripe}) sends
 * {@code payment_intent.succeeded} with the order's number in the payment's metadata. The platform verifies, stores and
 * publishes it; the consumer {@value #CONSUMER} runs {@value #RECORD_PAYMENT} once for each, as the system:
 * <ul>
 *   <li>the order of that number, not cancelled and not paid yet, whose total is the amount received, gets
 *       {@code paidTime} (when the payment was received) and {@code paymentRef} (the provider's id of the payment);</li>
 *   <li>the same payment reported again changes nothing;</li>
 *   <li>anything else (no such order, another amount, a cancelled order, an order paid by another payment) becomes a
 *       task for whoever may write orders ({@value #CHECK_PERMISSION}), rather than a failure: a failing consumer would
 *       be retried for nothing.</li>
 * </ul>
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

    /** @param inboundEventId the received event ({@code InboundEvent}) */
    record PaymentInput(@NotBlank String inboundEventId) {}

    /**
     * @param outcome {@code RECORDED}, {@code ALREADY_RECORDED} or {@code CHECK} (a task was opened)
     */
    record PaymentOutput(String orderNo, String paymentRef, String outcome) {}

    private static final String INPUT = "input";
    private static final String PAYMENT = "payment";
    private static final String ORDERS = "orders";
    private static final String OUTCOME = "outcome";

    static final ProcessDefinition<PaymentInput, PaymentOutput, ProcessContext> RECORD_PROCESS =
        ProcessDefinition.define(RECORD_PAYMENT, 1, PaymentInput.class, PaymentOutput.class, ProcessContext.class,
            pb -> pb
                .description("Records a payment the payment provider reported on its order.")
                .permissions("commerce.order.pay")
                .internal()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(ctx -> new PaymentOutput(orderNo(ctx), paymentRef(ctx), (String) ctx.get(OUTCOME)))
                .step("Load the payment", LoadInboundEvent.of(ctx -> ctx.get(INPUT, PaymentInput.class)
                    .inboundEventId(), PAYMENT))
                .step("Find the order", QueryEntities.of(ORDER_DATASET, CommercePayments::orderQuery, ORDERS))
                .compute("Record the payment", (metadata, ctx) -> record(ctx))
                .step("Ask for a check", CreateTask.when(ctx -> "CHECK".equals(ctx.get(OUTCOME)),
                    CommercePayments::checkTask)));

    @Bean
    ProcessDefinition<PaymentInput, PaymentOutput, ProcessContext> orderRecordPaymentProcess() {
        return RECORD_PROCESS;
    }

    @Bean
    EventSubscription<PaymentInput> paymentReceivedSubscription() {
        return EventSubscription.of(CONSUMER, InboundEvents.type(PROVIDER, SUCCEEDED), RECORD_PROCESS,
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

    private static ReceivedEvent payment(ProcessContext ctx) {
        return ctx.get(PAYMENT, ReceivedEvent.class);
    }

    private static String orderNo(ProcessContext ctx) {
        Object value = payment(ctx).at("data.object.metadata.orderNo");
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private static String paymentRef(ProcessContext ctx) {
        Object value = payment(ctx).at("data.object.id");
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private static BigDecimal amount(ProcessContext ctx) {
        return payment(ctx).at("data.object.amount_received") instanceof BigDecimal amount ? amount : null;
    }

    private static EntityQuery orderQuery(ProcessContext ctx) {
        String orderNo = orderNo(ctx);
        return EntityQuery.builder()
            .where(new QueryPredicate.In("orderNo", orderNo == null ? List.of() : List.of(orderNo)))
            .limit(1).build();
    }

    @SuppressWarnings("unchecked")
    private static void record(ProcessContext ctx) {
        List<EntityInstance> orders = (List<EntityInstance>) ctx.get(ORDERS);
        EntityInstance order = orders == null || orders.isEmpty() ? null : orders.getFirst();
        String ref = paymentRef(ctx);
        BigDecimal amount = amount(ctx);
        if (order == null || ref == null || amount == null || CANCELLED.equals(order.get("status"))) {
            ctx.put(OUTCOME, "CHECK");
            return;
        }
        Object recorded = order.get("paymentRef");
        if (recorded != null) {
            ctx.put(OUTCOME, ref.equals(recorded) ? "ALREADY_RECORDED" : "CHECK");
            return;
        }
        if (amount.compareTo(order.<BigDecimal>get("totalAmount")) != 0) {
            ctx.put(OUTCOME, "CHECK");
            return;
        }
        Map<String, Object> paid = new LinkedHashMap<>();
        paid.put("paidTime", payment(ctx).receivedTime());
        paid.put("paymentRef", ref);
        ctx.changes().update(ORDER, order.id(), order.version(), paid);
        ctx.put(OUTCOME, "RECORDED");
    }

    private static TaskSpec checkTask(ProcessContext ctx) {
        ReceivedEvent payment = payment(ctx);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("orderNo", String.valueOf(orderNo(ctx)));
        params.put("payment", String.valueOf(paymentRef(ctx)));
        return TaskSpec.forPermission(CHECK_TASK, "task.commerce.payment-check", params, CHECK_PERMISSION)
            .about(InboundEntities.EVENT, payment.inboundEventId())
            .source("payment:" + payment.inboundEventId());
    }
}
