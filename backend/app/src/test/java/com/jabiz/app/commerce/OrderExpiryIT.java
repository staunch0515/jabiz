package com.jabiz.app.commerce;

import com.jabiz.app.it.fixture.ItJobItemFixtures;
import com.jabiz.app.it.fixture.ItJobItemFixtures.OnlineInput;
import com.jabiz.app.it.security.SecurityItSupport;
import com.jabiz.context.RequestContext;
import com.jabiz.inbound.InboundHeaders;
import com.jabiz.inbound.StripeSignature;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.event.OutboxDeliverer;
import com.jabiz.runtime.inbound.InboundReceiver;
import com.jabiz.runtime.process.ExecutionOptions;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.process.ProcessResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The expiry of unpaid orders against a payment arriving at the same time (ROADMAP 16k; decision D42): both take the
 * order's lock and read the order again under it, so whichever comes second sees what the first did. The lock is held
 * by a stand-in request first so that both queue for it, in a known order. Also: only the system expires orders, and
 * an order not yet past its time is left alone even when proposed. The time to pay is one second here.
 */
@SpringBootTest(properties = {"app.commerce.unpaid-order-ttl=1s", "jabiz.events.delivery.initial-backoff=0s"})
class OrderExpiryIT extends SecurityItSupport {

    static final String SECRET = "whsec_test_only_demo_pay_0123456789abcdef";

    @Autowired
    ProcessExecutor executor;

    @Autowired
    InboundReceiver inbound;

    @Autowired
    OutboxDeliverer deliverer;

    @Autowired
    ProcessDefinition<OrderExpiry.ExpireInput, OrderExpiry.ExpireOutput, ProcessContext> orderExpireProcess;

    private final List<ExecutorService> threads = new ArrayList<>();

    /** Each test later than the one before: stock and orders are temporal, and the clock is reset between tests. */
    private static final AtomicInteger HOURS = new AtomicInteger();

    @BeforeEach
    void catalog() {
        clock.set(START.plus(Duration.ofHours(HOURS.incrementAndGet())));
        if (query("SELECT 1 FROM warehouse_version WHERE warehouse_code = 'EXP'").isEmpty()) {
            insert(CommerceEntities.WAREHOUSE_DATASET, Map.of("warehouseCode", "EXP", "warehouseName", "Expiry",
                "active", true), null);
            insert(CommerceEntities.PRODUCT_DATASET, Map.of("sku", "EXP-1", "productName", "Expiring tea",
                "unitPrice", 500, "active", true), null);
            post("/api/processes/STOCK_RECEIVE/latest", admin(), Map.of("warehouseCode", "EXP", "sku", "EXP-1",
                "quantity", 1000)).expectStatus().isOk();
        }
    }

    @AfterEach
    void stopThreads() {
        threads.forEach(ExecutorService::shutdownNow);
        threads.clear();
    }

    @Test
    void aPaymentQueuedFirstIsRecordedAndTheExpiryLeavesTheOrder() throws Exception {
        String orderNo = unique("SO-EXP").toUpperCase();
        String orderId = placeOrder(orderNo);
        receivePayment(orderNo);
        clock.advance(Duration.ofSeconds(2));
        String gate = holdOrderLock(orderId);

        CompletableFuture<Integer> payment = background(() -> deliverer.deliverPending().block());
        awaitWaiting(OrderExpiry.lockName(orderId), 1);
        CompletableFuture<OrderExpiry.ExpireOutput> expiry = expire(orderId);
        awaitWaiting(OrderExpiry.lockName(orderId), 2);
        open(gate);

        assertThat(payment.get(20, TimeUnit.SECONDS)).isPositive();
        assertThat(expiry.get(20, TimeUnit.SECONDS).expired()).isFalse();
        assertThat(order(orderNo)).containsEntry("status", "PLACED").containsEntry("payment_ref", "pi_" + orderNo);
    }

    @Test
    void anExpiryQueuedFirstCancelsAndThePaymentBecomesACheck() throws Exception {
        String orderNo = unique("SO-EXP").toUpperCase();
        String orderId = placeOrder(orderNo);
        receivePayment(orderNo);
        clock.advance(Duration.ofSeconds(2));
        String gate = holdOrderLock(orderId);

        CompletableFuture<OrderExpiry.ExpireOutput> expiry = expire(orderId);
        awaitWaiting(OrderExpiry.lockName(orderId), 1);
        CompletableFuture<Integer> payment = background(() -> deliverer.deliverPending().block());
        awaitWaiting(OrderExpiry.lockName(orderId), 2);
        open(gate);

        assertThat(expiry.get(20, TimeUnit.SECONDS).expired()).isTrue();
        assertThat(payment.get(20, TimeUnit.SECONDS)).isPositive();
        // The payment found the order cancelled: not recorded, someone is asked to check it.
        assertThat(order(orderNo)).containsEntry("status", "CANCELLED").containsEntry("payment_ref", null);
    }

    @Test
    void onlyTheSystemExpiresOrdersAndOnlyThosePastTheirTime() throws Exception {
        String orderNo = unique("SO-EXP").toUpperCase();
        String orderId = placeOrder(orderNo);
        post("/api/processes/ORDER_EXPIRE/latest", admin(), Map.of("orderId", orderId)).expectStatus().isForbidden();
        // Proposed too early (placed less than the time to pay ago): read again and left alone.
        assertThat(expire(orderId).get(20, TimeUnit.SECONDS).expired()).isFalse();
        assertThat(order(orderNo)).containsEntry("status", "PLACED");
    }

    // ================= helpers =================

    private String placeOrder(String orderNo) {
        Map<String, Object> result = post("/api/processes/ORDER_PLACE/latest", admin(), Map.of("orderNo", orderNo,
            "customerCode", "EXP-CUST", "warehouseCode", "EXP", "lines", List.of(Map.of("sku", "EXP-1",
                "quantity", 1)))).expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) result.get("output");
        return (String) output.get("orderId");
    }

    /** The provider reports the full payment of the order; recorded at the next delivery. */
    private void receivePayment(String orderNo) {
        String body = "{\"id\":\"evt_" + orderNo + "\",\"object\":\"event\",\"type\":\"payment_intent.succeeded\","
            + "\"livemode\":false,\"data\":{\"object\":{\"id\":\"pi_" + orderNo + "\",\"object\":\"payment_intent\","
            + "\"amount_received\":500,\"currency\":\"jpy\",\"metadata\":{\"orderNo\":\"" + orderNo + "\"}}}}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        Map<String, List<String>> headers = Map.of(StripeSignature.HEADER, List.of(new StripeSignature()
            .sign(SECRET, clock.instant().getEpochSecond(), bytes).get(StripeSignature.HEADER)));
        assertThat(asRequest(RequestContext.system(Locale.ENGLISH, "it-inbound"), inbound.receive("demo-pay",
            InboundHeaders.of(headers), "application/json", bytes)).block()).isEqualTo(InboundReceiver.Outcome.ACCEPTED);
    }

    private CompletableFuture<OrderExpiry.ExpireOutput> expire(String orderId) {
        return asRequest(RequestContext.system(Locale.ENGLISH, "it-expire"), executor.run(orderExpireProcess,
                new OrderExpiry.ExpireInput(UUID.fromString(orderId)),
                ExecutionOptions.NONE.withLockWait(Duration.ofSeconds(15))))
            .map(ProcessResult::output).toFuture();
    }

    /** A stand-in request holds the order's lock until the returned gate opens. */
    private String holdOrderLock(String orderId) throws InterruptedException {
        String gate = "gate-" + UUID.randomUUID();
        ItJobItemFixtures.GATES.put(gate, new CountDownLatch(1));
        asTestRequest(executor.execute(ItJobItemFixtures.ONLINE,
            new OnlineInput(OrderExpiry.lockName(orderId), null, gate))).toFuture();
        for (int i = 0; i < 400 && ItJobItemFixtures.arrived(gate) < 1; i++) {
            Thread.sleep(25);
        }
        assertThat(ItJobItemFixtures.arrived(gate)).isEqualTo(1);
        return gate;
    }

    private static void open(String gate) {
        ItJobItemFixtures.GATES.get(gate).countDown();
    }

    private static void awaitWaiting(String name, int count) throws InterruptedException {
        for (int i = 0; i < 400 && waiting(name) < count; i++) {
            Thread.sleep(25);
        }
        assertThat(waiting(name)).as("sessions waiting for the order's lock").isEqualTo(count);
    }

    private static int waiting(String name) {
        return ((Number) query("SELECT count(*) AS n FROM pg_locks WHERE locktype = 'advisory' AND NOT granted"
            // Only this lock: test classes in other forks share the database server and its pg_locks.
            + " AND ((classid::bigint << 32) | objid::bigint) = hashtextextended(?, 0)", "jabiz.app-lock:" + name)
            .getFirst().get("n")).intValue();
    }

    private <T> CompletableFuture<T> background(java.util.concurrent.Callable<T> work) {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        threads.add(pool);
        return CompletableFuture.supplyAsync(() -> {
            try {
                return work.call();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, pool);
    }

    private static Map<String, Object> order(String orderNo) {
        return query("SELECT status, payment_ref FROM sales_order_version WHERE order_no = ?"
            + " ORDER BY version_no DESC LIMIT 1", orderNo).getFirst();
    }
}
