package com.jabiz.app.load;

import org.HdrHistogram.Histogram;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * Load test of a running jabiz (ROADMAP phase 11; the report is docs/perf/phase-11-load-test.md). Drives the same
 * HTTP API the frontend and other clients use, with the orders-and-inventory sample:
 * <ol>
 *   <li>seeds products, warehouses, stock and orders through the API (itself a write benchmark);</li>
 *   <li>runs each scenario for a fixed time at a fixed concurrency after a warm-up, and reports throughput and
 *       latency percentiles (HdrHistogram) per scenario, with the HTTP statuses seen.</li>
 * </ol>
 * Configuration by environment: {@code LOAD_BASE_URL} (default http://localhost:8080), {@code LOAD_USER},
 * {@code LOAD_PASSWORD}, {@code LOAD_PRODUCTS} (2000), {@code LOAD_WAREHOUSES} (5), {@code LOAD_ORDERS} (50000),
 * {@code LOAD_CONCURRENCY} (32), {@code LOAD_DURATION} (60s), {@code LOAD_WARMUP} (10s), {@code LOAD_REPORT}
 * (a Markdown file to write), {@code LOAD_SCENARIOS} (comma-separated subset).
 */
public final class LoadTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PRODUCTS = "urn:jabiz:dataset:default:Product";
    private static final String WAREHOUSES = "urn:jabiz:dataset:default:Warehouse";

    private final String baseUrl = env("LOAD_BASE_URL", "http://localhost:8080");
    private final HttpClient http = HttpClient.newBuilder()
        .executor(Executors.newVirtualThreadPerTaskExecutor())
        .connectTimeout(Duration.ofSeconds(10))
        .build();
    private final String run = Long.toString(System.currentTimeMillis() % 1_000_000L, 36).toUpperCase(Locale.ROOT);
    private volatile String token;
    private volatile Instant tokenTime = Instant.EPOCH;

    private final List<String> productIds = new ArrayList<>();
    private final List<String> skus = new ArrayList<>();
    private final List<String> warehouseCodes = new ArrayList<>();
    private final StringBuilder report = new StringBuilder();

    /** Latencies and statuses of one scenario (or seeding step). */
    static final class Stats {
        final String name;
        final int concurrency;
        final Histogram latency = new Histogram(Duration.ofMinutes(2).toNanos(), 3);
        final Map<Integer, LongAdder> statuses = new ConcurrentHashMap<>();
        long elapsedNanos;

        Stats(String name, int concurrency) {
            this.name = name;
            this.concurrency = concurrency;
        }

        synchronized void record(long nanos, int status) {
            latency.recordValue(Math.min(nanos, latency.getHighestTrackableValue()));
            statuses.computeIfAbsent(status, s -> new LongAdder()).increment();
        }

        String row() {
            long count = latency.getTotalCount();
            double seconds = elapsedNanos / 1e9;
            Map<Integer, Long> byStatus = new TreeMap<>();
            statuses.forEach((status, n) -> byStatus.put(status, n.sum()));
            return String.format(Locale.ROOT, "| %s | %d | %d | %.1f | %.1f | %.1f | %.1f | %.1f | %s |", name,
                concurrency, count, count / seconds, ms(latency.getValueAtPercentile(50)),
                ms(latency.getValueAtPercentile(90)), ms(latency.getValueAtPercentile(99)),
                ms(latency.getMaxValue()), byStatus);
        }

        private static double ms(long nanos) {
            return nanos / 1e6;
        }
    }

    public static void main(String[] args) throws Exception {
        new LoadTest().start();
    }

    private void start() throws Exception {
        int productCount = Integer.parseInt(env("LOAD_PRODUCTS", "2000"));
        int warehouseCount = Integer.parseInt(env("LOAD_WAREHOUSES", "5"));
        int orderCount = Integer.parseInt(env("LOAD_ORDERS", "50000"));
        int concurrency = Integer.parseInt(env("LOAD_CONCURRENCY", "32"));
        Duration duration = Duration.parse("PT" + env("LOAD_DURATION", "60s").toUpperCase(Locale.ROOT));
        Duration warmup = Duration.parse("PT" + env("LOAD_WARMUP", "10s").toUpperCase(Locale.ROOT));
        List<String> only = List.of(env("LOAD_SCENARIOS", "").split(",")).stream().filter(s -> !s.isBlank())
            .toList();

        login();
        line("# jabiz load test run " + run + " (" + Instant.now() + ")");
        line("");
        line("Target " + baseUrl + "; products " + productCount + ", warehouses " + warehouseCount + ", orders "
            + orderCount + "; concurrency " + concurrency + "; " + warmup.toSeconds() + " s warm-up, "
            + duration.toSeconds() + " s measured per scenario.");
        line("");
        line("| scenario | concurrency | requests | per second | p50 ms | p90 ms | p99 ms | max ms | HTTP statuses |");
        line("|---|---|---|---|---|---|---|---|---|");

        seed(productCount, warehouseCount, orderCount, concurrency);

        Map<String, Supplier<Integer>> scenarios = new LinkedHashMap<>();
        scenarios.put("read: product by id (dataset API)", () -> get("/api/datasets/" + PRODUCTS + "/entities/"
            + pick(productIds)));
        scenarios.put("read: product list, filter + sort + page (dataset API)", () -> {
            int low = ThreadLocalRandom.current().nextInt(100, 9000);
            return post("/api/datasets/" + PRODUCTS + "/query", Map.of(
                "filters", List.of(Map.of("field", "unitPrice", "op", "between", "from", low, "to", low + 500)),
                "sorts", List.of(Map.of("field", "unitPrice", "asc", true)), "limit", 20));
        });
        scenarios.put("read: SQL template stock_availability (3-way join)", () -> post(
            "/api/queries/commerce.stock_availability", Map.of("params", Map.of("warehouseCode", pick(warehouseCodes)),
                "filters", List.of(Map.of("field", "available", "op", "gte", "value", 10)), "limit", 50)));
        scenarios.put("read: SQL template order_summary (join + group by)", () -> post(
            "/api/queries/commerce.order_summary", Map.of("params", Map.of("customerCode", customer()),
                "limit", 20)));
        AtomicLong orderNo = new AtomicLong();
        scenarios.put("write: process ORDER_PLACE (1-3 lines)", () -> post("/api/processes/ORDER_PLACE/latest",
            orderInput("W" + run + "-" + orderNo.incrementAndGet())));
        scenarios.put("write: process STOCK_RECEIVE", () -> post("/api/processes/STOCK_RECEIVE/latest", Map.of(
            "warehouseCode", pick(warehouseCodes), "sku", pick(skus), "quantity", 10)));

        for (Map.Entry<String, Supplier<Integer>> scenario : scenarios.entrySet()) {
            if (!only.isEmpty() && only.stream().noneMatch(scenario.getKey()::contains)) {
                continue;
            }
            Stats stats = measure(scenario.getKey(), concurrency, warmup, duration, scenario.getValue());
            line(stats.row());
        }
        if (only.isEmpty() || only.stream().anyMatch("contention"::equals)) {
            contention(concurrency);
        }
        String path = System.getenv("LOAD_REPORT");
        if (path != null && !path.isBlank()) {
            Files.writeString(Path.of(path), report.toString());
        }
    }

    // ---- seeding ------------------------------------------------------------------------------------------------

    private void seed(int productCount, int warehouseCount, int orderCount, int concurrency) throws Exception {
        for (int w = 0; w < warehouseCount; w++) {
            warehouseCodes.add("W" + run + w);
        }
        commitInBatches(WAREHOUSES, warehouseCodes.stream().map(code -> (Map<String, Object>) Map.<String, Object>of(
            "warehouseCode", code, "warehouseName", "Warehouse " + code, "active", true)).toList(), 100);
        List<Map<String, Object>> products = new ArrayList<>();
        for (int p = 0; p < productCount; p++) {
            String sku = "P" + run + "-" + p;
            skus.add(sku);
            products.add(Map.of("sku", sku, "productName", "Product " + p, "unitPrice",
                100 + ThreadLocalRandom.current().nextInt(9900), "active", true));
        }
        Stats productSeed = new Stats("seed: products, dataset commit of 100 per request", 1);
        long start = System.nanoTime();
        for (JsonNode saved : commitInBatches(PRODUCTS, products, 100, productSeed)) {
            productIds.add(saved.get("id").asString());
        }
        productSeed.elapsedNanos = System.nanoTime() - start;
        line(productSeed.row());

        // One stock row per product and warehouse, deep enough never to run out during the test.
        List<Supplier<Integer>> receipts = new ArrayList<>();
        for (String warehouse : warehouseCodes) {
            for (String sku : skus) {
                receipts.add(() -> post("/api/processes/STOCK_RECEIVE/latest", Map.of("warehouseCode", warehouse,
                    "sku", sku, "quantity", 1_000_000)));
            }
        }
        line(runAll("seed: process STOCK_RECEIVE (creates the stock row)", concurrency, receipts).row());

        List<Supplier<Integer>> orders = new ArrayList<>();
        for (int o = 0; o < orderCount; o++) {
            String no = "S" + run + "-" + o;
            orders.add(() -> post("/api/processes/ORDER_PLACE/latest", orderInput(no)));
        }
        line(runAll("seed: process ORDER_PLACE", concurrency, orders).row());
    }

    private List<JsonNode> commitInBatches(String dataset, List<Map<String, Object>> rows, int batch)
        throws Exception {
        return commitInBatches(dataset, rows, batch, new Stats("commit", 1));
    }

    private List<JsonNode> commitInBatches(String dataset, List<Map<String, Object>> rows, int batch, Stats stats)
        throws Exception {
        List<JsonNode> saved = new ArrayList<>();
        for (int i = 0; i < rows.size(); i += batch) {
            List<Map<String, Object>> changes = rows.subList(i, Math.min(rows.size(), i + batch)).stream()
                .map(row -> (Map<String, Object>) Map.<String, Object>of("action", "INSERT", "attributes", row))
                .toList();
            long started = System.nanoTime();
            HttpResponse<String> response = send("POST", "/api/datasets/" + dataset + "/commit",
                Map.of("changes", changes));
            stats.record(System.nanoTime() - started, response.statusCode());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("seeding " + dataset + " failed: " + response.statusCode() + " "
                    + response.body());
            }
            JSON.readTree(response.body()).forEach(saved::add);
        }
        return saved;
    }

    // ---- scenarios ----------------------------------------------------------------------------------------------

    private Map<String, Object> orderInput(String orderNo) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int lines = 1 + random.nextInt(3);
        List<Map<String, Object>> items = new ArrayList<>();
        List<String> used = new ArrayList<>();
        while (items.size() < lines) {
            String sku = pick(skus);
            if (!used.contains(sku)) {
                used.add(sku);
                items.add(Map.of("sku", sku, "quantity", 1 + random.nextInt(3)));
            }
        }
        return Map.of("orderNo", orderNo, "customerCode", customer(), "warehouseCode", pick(warehouseCodes),
            "lines", items);
    }

    private String customer() {
        return "CUST-" + run + "-" + ThreadLocalRandom.current().nextInt(1000);
    }

    /**
     * Every worker orders one unit of the same product in the same warehouse, which has a few hundred units: every
     * order updates the same stock row, so most of them conflict (409) or find nothing left (422). Afterwards the
     * reserved count must equal the number of successful orders: nothing is oversold.
     */
    private void contention(int concurrency) {
        String warehouse = warehouseCodes.getFirst();
        String sku = "HOT" + run;
        try {
            commitInBatches(PRODUCTS, List.of(Map.of("sku", sku, "productName", "Hot item", "unitPrice", 100,
                "active", true)), 1);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        int units = 300;
        post("/api/processes/STOCK_RECEIVE/latest", Map.of("warehouseCode", warehouse, "sku", sku, "quantity", units));
        AtomicLong no = new AtomicLong();
        Stats stats = measure("contention: ORDER_PLACE of one stock row", concurrency, Duration.ZERO,
            Duration.ofSeconds(20), () -> post("/api/processes/ORDER_PLACE/latest", Map.of("orderNo",
                "H" + run + "-" + no.incrementAndGet(), "customerCode", "HOT", "warehouseCode", warehouse,
                "lines", List.of(Map.of("sku", sku, "quantity", 1)))));
        line(stats.row());
        long placed = stats.statuses.getOrDefault(200, new LongAdder()).sum();
        try {
            HttpResponse<String> response = send("POST", "/api/queries/commerce.stock_availability", Map.of(
                "params", Map.of("warehouseCode", warehouse),
                "filters", List.of(Map.of("field", "sku", "op", "eq", "value", sku))));
            JsonNode row = JSON.readTree(response.body()).get("items").get(0);
            long reserved = row.get("reserved").asLong();
            line("");
            line("Contention check: " + placed + " orders succeeded, " + reserved + " units reserved of " + units
                + (placed == reserved && reserved <= units ? " - consistent, nothing oversold." : " - MISMATCH"));
        } catch (Exception e) {
            line("Contention check failed: " + e);
        }
    }

    /** Runs {@code call} on {@code concurrency} virtual threads; records only after the warm-up. */
    private Stats measure(String name, int concurrency, Duration warmup, Duration duration, Supplier<Integer> call) {
        Stats stats = new Stats(name, concurrency);
        long warmupEnd = System.nanoTime() + warmup.toNanos();
        long end = warmupEnd + duration.toNanos();
        try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < concurrency; i++) {
                workers.submit(() -> {
                    while (System.nanoTime() < end) {
                        long started = System.nanoTime();
                        int status = call.get();
                        if (started >= warmupEnd) {
                            stats.record(System.nanoTime() - started, status);
                        }
                    }
                });
            }
        }
        stats.elapsedNanos = duration.toNanos();
        return stats;
    }

    /** Runs every call once on {@code concurrency} workers and measures the whole batch. */
    private Stats runAll(String name, int concurrency, List<Supplier<Integer>> calls) {
        Stats stats = new Stats(name, concurrency);
        AtomicLong next = new AtomicLong();
        long start = System.nanoTime();
        try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < concurrency; i++) {
                workers.submit(() -> {
                    for (long n = next.getAndIncrement(); n < calls.size(); n = next.getAndIncrement()) {
                        long started = System.nanoTime();
                        int status = calls.get((int) n).get();
                        stats.record(System.nanoTime() - started, status);
                    }
                });
            }
        }
        stats.elapsedNanos = System.nanoTime() - start;
        return stats;
    }

    // ---- HTTP ---------------------------------------------------------------------------------------------------

    private int get(String path) {
        return call("GET", path, null);
    }

    private int post(String path, Object body) {
        return call("POST", path, body);
    }

    private int call(String method, String path, Object body) {
        try {
            return send(method, path, body).statusCode();
        } catch (IOException e) {
            return 599; // connection-level failure
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 599;
        }
    }

    private HttpResponse<String> send(String method, String path, Object body)
        throws IOException, InterruptedException {
        refreshTokenIfOld();
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(60))
            .header("Authorization", "Bearer " + token)
            .header("Accept-Language", "en");
        if (body == null) {
            request.GET();
        } else {
            request.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Access tokens live 15 minutes (docs/design/10-security.md section 2): sign in again well before. */
    private void refreshTokenIfOld() throws IOException, InterruptedException {
        if (Duration.between(tokenTime, Instant.now()).toMinutes() >= 10) {
            synchronized (this) {
                if (Duration.between(tokenTime, Instant.now()).toMinutes() >= 10) {
                    login();
                }
            }
        }
    }

    private void login() throws IOException, InterruptedException {
        String user = env("LOAD_USER", "admin");
        String password = System.getenv("LOAD_PASSWORD");
        if (password == null) {
            throw new IllegalStateException("Set LOAD_PASSWORD (and LOAD_USER) to an administrator's credentials");
        }
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/auth/login"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("userName", user,
                "password", password))))
            .build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Sign-in failed: " + response.statusCode());
        }
        token = JSON.readTree(response.body()).get("accessToken").asString();
        tokenTime = Instant.now();
    }

    private static <T> T pick(List<T> values) {
        return values.get(ThreadLocalRandom.current().nextInt(values.size()));
    }

    private void line(String text) {
        System.out.println(text);
        report.append(text).append('\n');
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private LoadTest() {}
}
