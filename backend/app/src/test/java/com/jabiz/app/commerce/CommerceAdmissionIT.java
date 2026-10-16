package com.jabiz.app.commerce;

import com.jabiz.app.it.fixture.ItJobItemFixtures;
import com.jabiz.app.it.fixture.ItJobItemFixtures.OnlineInput;
import com.jabiz.app.it.security.SecurityItSupport;
import com.jabiz.context.RequestContext;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.process.ExecutionOptions;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.process.ProcessResult;
import com.jabiz.runtime.process.admission.AdmissionSettings;
import com.jabiz.runtime.process.admission.ProcessAdmission;
import com.jabiz.runtime.web.ProcessRateLimits;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.EntityExchangeResult;

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
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The queue before the order lock in the orders sample (decision D45): {@code ORDER_CANCEL} and {@code ORDER_SHIP}
 * queue for {@code commerce.order:<id>}; {@code ORDER_EXPIRE}, a job's item, takes the same lock without queueing and
 * cancels the order with {@code ORDER_CANCEL} as a sub-process, which never queues - even with the order's queue full
 * and its permit taken by a request waiting for the very lock the expiry holds. Queueing there would wait for that
 * request, which waits for the expiry: the sub-process does not, and nothing deadlocks. Here one request per order at a
 * time and one waiting.
 */
@SpringBootTest(properties = {"app.commerce.unpaid-order-ttl=1s",
    "jabiz.process.admission.families[commerce.order].concurrency=1",
    "jabiz.process.admission.families[commerce.order].max-queue=1",
    "jabiz.process.admission.families[commerce.order].max-wait=10s"})
class CommerceAdmissionIT extends SecurityItSupport {

    @Autowired
    ProcessExecutor executor;

    @Autowired
    ProcessAdmission admission;

    @Autowired
    ProcessRateLimits rateLimits;

    @Autowired
    AdmissionSettings settings;

    @Autowired
    ProcessDefinition<OrderExpiry.ExpireInput, OrderExpiry.ExpireOutput, ProcessContext> orderExpireProcess;

    private final List<ExecutorService> threads = new ArrayList<>();

    @BeforeEach
    void catalog() {
        if (query("SELECT 1 FROM warehouse_version WHERE warehouse_code = 'ADM'").isEmpty()) {
            insert(CommerceEntities.WAREHOUSE_DATASET, Map.of("warehouseCode", "ADM", "warehouseName", "Admission",
                "active", true), null);
            insert(CommerceEntities.PRODUCT_DATASET, Map.of("sku", "ADM-1", "productName", "Queued tea",
                "unitPrice", 500, "active", true), null);
            post("/api/processes/STOCK_RECEIVE/latest", admin(), Map.of("warehouseCode", "ADM", "sku", "ADM-1",
                "quantity", 1000)).expectStatus().isOk();
        }
    }

    @AfterEach
    void stopThreads() {
        threads.forEach(ExecutorService::shutdownNow);
        threads.clear();
    }

    @Test
    void theSampleDeclaresItsQueueAndItsRateLimit() {
        assertThat(CommerceProcesses.CANCEL_PROCESS.admission().family()).isEqualTo(OrderExpiry.LOCK_FAMILY);
        assertThat(CommerceProcesses.SHIP_PROCESS.admission().family()).isEqualTo(OrderExpiry.LOCK_FAMILY);
        String id = UUID.randomUUID().toString();
        // However the id is written, the queue and the lock are the order's.
        assertThat(CommerceProcesses.CANCEL_PROCESS.admission().keyOf(new CommerceProcesses.CancelInput(
            " " + id.toUpperCase(Locale.ROOT) + " "))).isEqualTo(OrderExpiry.lockName(id));
        // Not declared: the payment's lock is known only from the order it reads, the expiry is a job's item.
        assertThat(orderExpireProcess.admission()).isNull();
        assertThat(rateLimits.limits(PortalOrders.NAME)).as("application.yml, bound with its case").isTrue();
        assertThat(settings.limitsOf(OrderExpiry.LOCK_FAMILY).maxQueue()).isEqualTo(1);
    }

    @Test
    void aSubProcessNeverQueuesNotEvenBehindAFullQueue() throws Exception {
        String orderNo = unique("SO-ADM").toUpperCase(Locale.ROOT);
        String orderId = placeOrder(orderNo);
        clock.advance(Duration.ofSeconds(2));
        String lock = OrderExpiry.lockName(orderId);
        String gate = holdOrderLock(orderId);

        CompletableFuture<ProcessResult<OrderExpiry.ExpireOutput>> expiry = asRequest(RequestContext.system(
            Locale.ENGLISH, "it-expire"), executor.run(orderExpireProcess, new OrderExpiry.ExpireInput(
            UUID.fromString(orderId)), ExecutionOptions.NONE.withLockWait(Duration.ofSeconds(15)))).toFuture();
        awaitWaiting(lock, 1);
        // The order's permit: a cancellation waiting for the lock after the expiry; the queue: one more.
        CompletableFuture<EntityExchangeResult<Map<String, Object>>> first = background(() -> cancel(orderId));
        awaitWaiting(lock, 2);
        CompletableFuture<EntityExchangeResult<Map<String, Object>>> second = background(() -> cancel(orderId));
        await(() -> admission.waiting() == 1, "the second cancellation queued");
        EntityExchangeResult<Map<String, Object>> third = cancel(orderId);
        assertThat(third.getStatus().value()).isEqualTo(503);
        assertThat(third.getResponseBody()).containsEntry("reason", "QUEUE_FULL");
        assertThat(third.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotBlank();
        assertThat(third.getResponseBody().toString()).contains(PlatformErrorCodes.PROCESS_BUSY);

        long started = System.nanoTime();
        open(gate);
        // The expiry takes the lock first (it waited first) and cancels the order through its sub-process at once.
        assertThat(expiry.get(20, TimeUnit.SECONDS).output().expired()).isTrue();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).as("well within the queue's 10 s")
            .isLessThan(Duration.ofSeconds(5));
        // The cancellations then find it cancelled already.
        assertThat(first.get(20, TimeUnit.SECONDS).getStatus().value()).isEqualTo(422);
        assertThat(second.get(20, TimeUnit.SECONDS).getStatus().value()).isEqualTo(422);
        assertThat(query("SELECT status FROM sales_order_version WHERE order_no = ? ORDER BY version_no DESC"
            + " LIMIT 1", orderNo).getFirst()).containsEntry("status", "CANCELLED");
        await(() -> admission.active() == 0 && admission.keys() == 0, "nothing held");
    }

    // ================= helpers =================

    private EntityExchangeResult<Map<String, Object>> cancel(String orderId) {
        return post("/api/processes/ORDER_CANCEL/latest", admin(), Map.of("orderId", orderId)).expectBody(MAP)
            .returnResult();
    }

    private String placeOrder(String orderNo) {
        Map<String, Object> result = post("/api/processes/ORDER_PLACE/latest", admin(), Map.of("orderNo", orderNo,
            "customerCode", "ADM-CUST", "warehouseCode", "ADM", "lines", List.of(Map.of("sku", "ADM-1",
                "quantity", 1)))).expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) result.get("output");
        return (String) output.get("orderId");
    }

    /** A stand-in request (queueing for nothing) holds the order's lock until the returned gate opens. */
    private String holdOrderLock(String orderId) throws InterruptedException {
        String gate = "gate-" + UUID.randomUUID();
        ItJobItemFixtures.GATES.put(gate, new CountDownLatch(1));
        asTestRequest(executor.execute(ItJobItemFixtures.ONLINE,
            new OnlineInput(OrderExpiry.lockName(orderId), null, gate))).toFuture();
        await(() -> ItJobItemFixtures.arrived(gate) == 1, "the stand-in holds the lock");
        return gate;
    }

    private static void open(String gate) {
        ItJobItemFixtures.GATES.get(gate).countDown();
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        for (int i = 0; i < 400 && !condition.getAsBoolean(); i++) {
            Thread.sleep(25);
        }
        assertThat(condition.getAsBoolean()).as(what).isTrue();
    }

    private static void awaitWaiting(String name, int count) throws InterruptedException {
        await(() -> waiting(name) >= count, count + " sessions waiting for the order's lock");
        assertThat(waiting(name)).isEqualTo(count);
    }

    private static int waiting(String name) {
        return ((Number) query("SELECT count(*) AS n FROM pg_locks WHERE locktype = 'advisory' AND NOT granted"
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
}
