package com.jabiz.app.commerce;

import com.jabiz.context.RequestContext;
import com.jabiz.process.ChangeSet;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessStart;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.PermissionDeniedException;
import com.jabiz.runtime.inbound.ReceivedEvent;
import com.jabiz.runtime.task.TaskSpec;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What {@code ORDER_RECORD_PAYMENT} makes of a reported payment, without a database: only a payment that fits its order
 * in every respect is recorded; everything else becomes a check task that names why (the platform side is covered by
 * {@code InboundWebhookIT} and the scenario {@code commerce/order_payment}).
 */
class CommercePaymentsTest {

    private static final Instant NOW = Instant.parse("2026-05-01T01:00:00Z");
    private static final UUID ORDER_ID = UUID.randomUUID();

    private static ProcessContext context(String actor, Map<String, Object> object, Boolean livemode,
        EntityInstance... orders) {
        ProcessContext ctx = new ProcessContext(new ProcessStart(1, NOW, new RequestContext(actor, null,
            Locale.ENGLISH, "r", Set.of(), Set.of()), (type, attributes) -> UUID.randomUUID()));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("object", object);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", "evt_1");
        body.put("livemode", livemode);
        body.put("data", data);
        ctx.put(CommercePayments.PAYMENT, new ReceivedEvent("in-1", "demo-pay", "evt_1", "payment_intent.succeeded",
            livemode, NOW, NOW, body));
        ctx.put(CommercePayments.ORDERS, List.of(orders));
        return ctx;
    }

    private static Map<String, Object> payment(String ref, Object amount, String currency, String orderNo) {
        Map<String, Object> object = new LinkedHashMap<>();
        object.put("id", ref);
        object.put("amount_received", amount);
        object.put("currency", currency);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("orderNo", orderNo);
        object.put("metadata", metadata);
        return object;
    }

    private static Map<String, Object> fitting() {
        return payment("pi_1", new BigDecimal("1600"), "jpy", "SO-PAY-1");
    }

    private static EntityInstance order(String status, String paymentRef) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("orderNo", "SO-PAY-1");
        attributes.put("status", status);
        attributes.put("totalAmount", new BigDecimal("1600"));
        attributes.put("paymentRef", paymentRef);
        return new EntityInstance(ORDER_ID, CommerceEntities.ORDER, 3, null, attributes);
    }

    private static String outcome(ProcessContext ctx, boolean acceptTestMode) {
        CommercePayments.record(ctx, acceptTestMode);
        return ctx.get(CommercePayments.OUTCOME) + " " + ctx.get(CommercePayments.REASON);
    }

    @Test
    void aFittingPaymentIsRecordedOnItsOrder() {
        ProcessContext ctx = context("system", fitting(), false, order("PLACED", null));
        assertThat(outcome(ctx, true)).isEqualTo("RECORDED null");
        List<ChangeSet.Change> changes = ctx.changes().pending();
        assertThat(changes).singleElement().satisfies(change -> {
            assertThat(change.id()).isEqualTo(ORDER_ID);
            assertThat(change.version()).isEqualTo(3);
            assertThat(change.attributes()).containsEntry("paymentRef", "pi_1").containsEntry("paidTime", NOW);
        });
        // The same payment again changes nothing.
        ProcessContext again = context("system", fitting(), false, order("PLACED", "pi_1"));
        assertThat(outcome(again, true)).isEqualTo("ALREADY_RECORDED null");
        assertThat(again.changes().pending()).isEmpty();
    }

    @Test
    void whatDoesNotFitBecomesACheckWithItsReason() {
        assertThat(outcome(context("system", fitting(), false, order("PLACED", null)), false))
            .isEqualTo("CHECK TEST_MODE");
        assertThat(outcome(context("system", fitting(), null, order("PLACED", null)), true))
            .isEqualTo("CHECK TEST_MODE");
        assertThat(outcome(context("system", fitting(), true, order("PLACED", null)), false))
            .isEqualTo("RECORDED null");
        assertThat(outcome(context("system", payment("pi_1", new BigDecimal("1600"), "usd", "SO-PAY-1"), false,
            order("PLACED", null)), true)).isEqualTo("CHECK CURRENCY");
        assertThat(outcome(context("system", payment("pi_1", new BigDecimal("1600"), null, "SO-PAY-1"), false,
            order("PLACED", null)), true)).isEqualTo("CHECK CURRENCY");
        assertThat(outcome(context("system", payment("pi_1", new BigDecimal("999"), "jpy", "SO-PAY-1"), false,
            order("PLACED", null)), true)).isEqualTo("CHECK AMOUNT_MISMATCH");
        assertThat(outcome(context("system", payment("pi_1", "1600", "jpy", "SO-PAY-1"), false,
            order("PLACED", null)), true)).isEqualTo("CHECK NO_AMOUNT");
        assertThat(outcome(context("system", fitting(), false), true)).isEqualTo("CHECK NO_ORDER");
        assertThat(outcome(context("system", fitting(), false, order("CANCELLED", null)), true))
            .isEqualTo("CHECK CANCELLED");
        assertThat(outcome(context("system", fitting(), false, order("PLACED", "pi_other")), true))
            .isEqualTo("CHECK PAID_OTHER");
    }

    @Test
    void referencesAndOrderNumbersOfTheWrongShapeAreChecksNotFailures() {
        assertThat(outcome(context("system", payment("pi 1; drop", new BigDecimal("1600"), "jpy", "SO-PAY-1"),
            false, order("PLACED", null)), true)).isEqualTo("CHECK INVALID_PAYMENT_REF");
        assertThat(outcome(context("system", payment("p".repeat(256), new BigDecimal("1600"), "jpy", "SO-PAY-1"),
            false, order("PLACED", null)), true)).isEqualTo("CHECK INVALID_PAYMENT_REF");
        assertThat(outcome(context("system", payment(null, new BigDecimal("1600"), "jpy", "SO-PAY-1"),
            false, order("PLACED", null)), true)).isEqualTo("CHECK INVALID_PAYMENT_REF");
        assertThat(outcome(context("system", payment("pi_1", new BigDecimal("1600"), "jpy", "so pay 1"),
            false), true)).isEqualTo("CHECK INVALID_ORDER_NO");
        assertThat(outcome(context("system", payment("pi_1", new BigDecimal("1600"), "jpy", "S".repeat(31)),
            false), true)).isEqualTo("CHECK INVALID_ORDER_NO");
        // An order number of the wrong shape is never looked up.
        ProcessContext ctx = context("system", payment("pi_1", new BigDecimal("1600"), "jpy", "x y"), false);
        assertThat(CommercePayments.orderQuery(ctx).toString()).doesNotContain("x y");
    }

    @Test
    void theCheckTaskNamesAmountCurrencyAndReasonAndPointsAtTheOrder() {
        ProcessContext ctx = context("system", payment("pi_1", new BigDecimal("999"), "jpy", "SO-PAY-1"), false,
            order("PLACED", null));
        CommercePayments.record(ctx, true);
        TaskSpec task = CommercePayments.checkTask(ctx);
        assertThat(task.titleParams()).containsEntry("orderNo", "SO-PAY-1").containsEntry("payment", "pi_1")
            .containsEntry("amount", "999").containsEntry("currency", "jpy").containsEntry("reason", "AMOUNT_MISMATCH");
        assertThat(task.subjectEntity()).isEqualTo(CommerceEntities.ORDER);
        assertThat(task.subjectId()).isEqualTo(ORDER_ID.toString());
        assertThat(task.sourceKey()).isEqualTo("payment:demo-pay:evt_1");
        assertThat(task.permission()).isEqualTo(CommercePayments.CHECK_PERMISSION);

        ProcessContext nothing = context("system", payment(null, null, null, null), false);
        CommercePayments.record(nothing, true);
        TaskSpec unknown = CommercePayments.checkTask(nothing);
        assertThat(unknown.titleParams().values()).doesNotContain("null").contains("-");
        assertThat(unknown.subjectEntity()).isEqualTo("InboundEvent");
        assertThat(unknown.subjectId()).isEqualTo("in-1");
        // Whatever the sender wrote, the title stays short and printable.
        ProcessContext odd = context("system", payment("p".repeat(300) + "\n", null, "x".repeat(50), null), false);
        CommercePayments.record(odd, true);
        assertThat(CommercePayments.checkTask(odd).titleParams().values())
            .allSatisfy(value -> assertThat(value).hasSizeLessThanOrEqualTo(41).doesNotContain("\n"));
    }

    @Test
    void onlyTheSystemRecordsPayments() {
        assertThatThrownBy(() -> CommercePayments.requireSystem(context("clerk", fitting(), false)))
            .isInstanceOf(PermissionDeniedException.class);
        CommercePayments.requireSystem(context(RequestContext.SYSTEM_ACTOR, fitting(), false));
    }
}
