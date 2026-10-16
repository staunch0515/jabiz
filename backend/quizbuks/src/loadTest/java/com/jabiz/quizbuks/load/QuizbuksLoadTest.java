package com.jabiz.quizbuks.load;

import org.HdrHistogram.Histogram;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Load test of one hot publication (docs/quizbuks/plans/Q10-launch.md section 3; the report is
 * docs/quizbuks/perf/Q10a-load-test.md). Drives a running QuizBuks jar over HTTP with real tokens, through the same
 * processes and templates the front ends use:
 * <ol>
 *   <li>setup through the real processes: {@code QB_SETUP} and its proposals published by a second administrator; a
 *       sponsor registered (the mailed link opened through Mailpit), approved and funded through the Stripe stub's
 *       checkout; a quiz, its version and one publication with a large budget and no winner cap, approved and LIVE;
 *       the takers and readers registered and verified (reused by later runs: each run makes a new publication);</li>
 *   <li>a baseline of the readers alone: the home feed ({@code qb.app.feed}) and a publication's page
 *       ({@code qb.app.publication}, {@code qb.app.publication-tiers});</li>
 *   <li>the load: every taker starts and submits an attempt of the one publication ({@code LOAD_CONCURRENCY} at a
 *       time, all answers right, every reward paid), while the readers keep reading the feed and the page.</li>
 * </ol>
 * It prints and writes completions a second, the latencies (HdrHistogram) and the statuses and error codes of each
 * operation (a 422 for the quota, the budget or the sponsor's funds is expected; anything else is an error), and
 * checks afterwards that the sponsor's wallet went down by exactly the rewards paid. With {@code LOAD_DB_URL} it also
 * resets and reads {@code pg_stat_statements} around the load and samples {@code pg_stat_activity} (JDBC, outside the
 * application).
 *
 * <p>Safety: it drives only this machine ({@code LOAD_BASE_URL} must be a loopback address unless
 * {@code LOAD_ALLOW_REMOTE=yes}); it pays only checkouts on the application's own host (the Stripe stub beside it)
 * and stops at a top-up in live mode; it publishes only the controlled changes this run's {@code QB_SETUP} proposed.
 *
 * <p>Configuration by environment: {@code LOAD_BASE_URL} (http://localhost:8080), {@code LOAD_ALLOW_REMOTE},
 * {@code LOAD_MAILPIT_URL} (http://localhost:8025), {@code LOAD_ADMIN_USER} (admin), {@code LOAD_ADMIN_PASSWORD},
 * {@code LOAD_PUBLISHER_ROLE} (QB_LOAD_PUBLISHER: must be among the administration's accepted roles,
 * {@code QB_ADMIN_ROLES}), {@code LOAD_PASSWORD} (the test users' password, required), {@code LOAD_TAKERS} (1000),
 * {@code LOAD_CONCURRENCY} (64: the closed loop's takers at a time), {@code LOAD_RATE} (arrivals a second: an open
 * loop instead, every taker starting at its time whatever the others' answers took), {@code LOAD_READERS} (8),
 * {@code LOAD_BASELINE} (15s), {@code LOAD_SETUP_CONCURRENCY} (8), {@code LOAD_REWARD} (10), {@code LOAD_LABEL}
 * (a heading for the run), {@code LOAD_REPORT} (a Markdown file to write), {@code LOAD_DB_URL} / {@code LOAD_DB_USER}
 * / {@code LOAD_DB_PASSWORD} / {@code LOAD_DB_NAME} (JDBC, optional), {@code LOAD_TIMEOUT} (60s: how long a client of
 * the load waits; one not answered by then is 599 {@code TIMEOUT}), {@code LOAD_RETRY_BUSY} ({@code yes}: a 503
 * {@code PROCESS_BUSY} or a timeout is tried again as the App does, with the same {@code Idempotency-Key}, after the
 * doubling back-off and never sooner than {@code Retry-After}; {@code no} gives up at once), {@code LOAD_READ_RATE}
 * (rounds a second of each reader; back to back when absent).
 *
 * <p>As the App, every start and submission carries an {@code Idempotency-Key} of its own (kept for its retries) and
 * the submission names its publication (the key of the queue before the publication's lock, platform decision D45).
 * Takers still busy after their retries are finished after the load, unrecorded, so that no attempt is left to expire
 * and pay from the sponsor's wallet during a later run.
 */
public final class QuizbuksLoadTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String ROLES = "urn:jabiz:dataset:platform:SecRole";
    private static final String ROLE_PERMISSIONS = "urn:jabiz:dataset:platform:SecRolePermission";
    private static final String USER_ROLES = "urn:jabiz:dataset:platform:SecUserRole";
    private static final String CONTROL_CHANGES = "urn:jabiz:dataset:platform:SysControlChange";
    private static final String SPONSORS = "urn:jabiz:dataset:sponsor:QbSponsor";
    private static final String TOPUPS = "urn:jabiz:dataset:sponsor:QbTopUp";
    private static final String WALLETS = "urn:jabiz:dataset:sponsor:QbWallet";
    private static final String PUBLICATIONS = "urn:jabiz:dataset:sponsor:QbPublication";
    private static final long TOPUP_MAX = 1_000_000;
    /** The 422s of a full publication or a sponsor who cannot pay: the answers the load is expected to meet. */
    private static final List<String> EXPECTED = List.of("QB_PUBLICATION_BUDGET_FULL", "QB_PUBLICATION_QUOTA_FULL",
        "QB_SPONSOR_FUNDS_SHORT", "QB_PUBLICATION_NOT_OPEN");
    /** The queue before the publication's lock turned the call away (platform decision D45): retried as the App does. */
    static final String BUSY = "503 PROCESS_BUSY";
    /** A load call not answered within {@code LOAD_TIMEOUT}: its client gave up (as in the platform's loadTest). */
    static final int TIMED_OUT = 599;
    /** As the App (quizbuks-web/app/src/play/busy.ts): tries in all, the first delay and the longest. */
    static final int BUSY_ATTEMPTS = 6;
    static final long BUSY_FIRST_DELAY_MS = 1_000;
    static final long BUSY_LONGEST_DELAY_MS = 8_000;
    /** As the App: the waits of one call's retries together at most a minute. */
    static final long BUSY_TOTAL_WAIT_MS = 60_000;
    private static final Pattern LINK = Pattern.compile("https?://[^\\s<>\"')\\]]+");

    private final String baseUrl = env("LOAD_BASE_URL", "http://localhost:8080");
    private final String mailpit = env("LOAD_MAILPIT_URL", "http://localhost:8025");
    /** The test users' password: required, never a default in the repository. */
    private final String password = required("LOAD_PASSWORD");
    /**
     * How long a client of the load waits for an answer ({@code LOAD_TIMEOUT}, default 60s, as the platform's
     * loadTest): one not answered by then counts as {@link #TIMED_OUT}, its client gone. Setup calls wait 120 s.
     */
    private final Duration requestTimeout = Duration.parse("PT" + env("LOAD_TIMEOUT", "60s").toUpperCase(Locale.ROOT));
    /**
     * Rounds a second of each reader ({@code LOAD_READ_RATE}; a round reads the feed, the page and the tiers); 0 or
     * absent: back to back, as fast as the answers come.
     */
    private final double readRate = Double.parseDouble(env("LOAD_READ_RATE", "0"));
    /** Whether a busy answer is tried again as the App does ({@code LOAD_RETRY_BUSY}, default yes). */
    private final boolean retryBusy = !"no".equals(env("LOAD_RETRY_BUSY", "yes"));
    private final HttpClient http = HttpClient.newBuilder()
        .executor(Executors.newVirtualThreadPerTaskExecutor())
        .connectTimeout(Duration.ofSeconds(10))
        .build();
    private final StringBuilder report = new StringBuilder();
    private final String run = Long.toString(System.currentTimeMillis() % 100_000_000L, 36).toUpperCase(Locale.ROOT);

    /** An answer: its status, its body and the time it took. */
    record Answer(int status, String body, long nanos) {
        JsonNode json() {
            return body == null || body.isBlank() ? JSON.nullNode() : JSON.readTree(body);
        }

        /** The first violation's rule code, else the problem's title, else the status. */
        String code() {
            if (status / 100 == 2) {
                return "OK";
            }
            if (status == TIMED_OUT) {
                return body == null ? "CONNECTION" : body;
            }
            try {
                JsonNode problem = json();
                JsonNode violations = problem.path("violations");
                if (violations.isArray() && !violations.isEmpty() && violations.get(0).hasNonNull("ruleCode")) {
                    return violations.get(0).get("ruleCode").asString();
                }
                if (problem.hasNonNull("code")) {
                    return problem.get("code").asString();
                }
                if (problem.hasNonNull("title")) {
                    return problem.get("title").asString();
                }
            } catch (RuntimeException e) {
                // not JSON
            }
            return "HTTP " + status;
        }
    }

    /** Latencies and outcomes of one operation. */
    static final class Stats {
        final String name;
        final Histogram latency = new Histogram(Duration.ofMinutes(5).toNanos(), 3);
        final Histogram ok = new Histogram(Duration.ofMinutes(5).toNanos(), 3);
        final Map<String, LongAdder> codes = new ConcurrentHashMap<>();

        Stats(String name) {
            this.name = name;
        }

        synchronized void record(Answer answer) {
            long nanos = Math.min(answer.nanos(), latency.getHighestTrackableValue());
            latency.recordValue(nanos);
            if (answer.status() / 100 == 2) {
                ok.recordValue(nanos);
            }
            codes.computeIfAbsent(answer.status() + " " + answer.code(), c -> new LongAdder()).increment();
        }

        long count() {
            return latency.getTotalCount();
        }

        /** Neither 2xx nor an expected 422, a busy answer (retried) or a client's timeout (both counted apart). */
        long errors() {
            return codes.entrySet().stream().filter(e -> !e.getKey().startsWith("2") && !e.getKey().equals(BUSY)
                && !e.getKey().equals(TIMED_OUT + " TIMEOUT") && EXPECTED.stream()
                .noneMatch(code -> e.getKey().equals("422 " + code))).mapToLong(e -> e.getValue().sum()).sum();
        }

        long count(String code) {
            LongAdder n = codes.get(code);
            return n == null ? 0 : n.sum();
        }

        String row(double seconds) {
            Map<String, Long> byCode = new TreeMap<>();
            codes.forEach((code, n) -> byCode.put(code, n.sum()));
            return String.format(Locale.ROOT, "| %s | %d | %.1f | %.0f | %.0f | %.0f | %.0f | %.0f | %d | %s |", name,
                count(), count() / seconds, ms(latency.getValueAtPercentile(50)),
                ms(latency.getValueAtPercentile(95)), ms(latency.getValueAtPercentile(99)),
                ms(latency.getMaxValue()), ms(ok.getValueAtPercentile(95)), errors(), byCode);
        }
    }

    static final String HEADER = "| operation | requests | per second | p50 ms | p95 ms | p99 ms | max ms | p95 of 2xx ms "
        + "| errors | statuses and codes |\n|---|---|---|---|---|---|---|---|---|---|";

    public static void main(String[] args) throws Exception {
        new QuizbuksLoadTest().start();
    }

    private void start() throws Exception {
        int takers = Integer.parseInt(env("LOAD_TAKERS", "1000"));
        int concurrency = Integer.parseInt(env("LOAD_CONCURRENCY", "64"));
        int readers = Integer.parseInt(env("LOAD_READERS", "8"));
        int setupConcurrency = Integer.parseInt(env("LOAD_SETUP_CONCURRENCY", "8"));
        long reward = Long.parseLong(env("LOAD_REWARD", "10"));
        Duration baseline = Duration.parse("PT" + env("LOAD_BASELINE", "15s").toUpperCase(Locale.ROOT));
        double rate = Double.parseDouble(env("LOAD_RATE", "0"));
        refuseRemote();
        Instant runStarted = Instant.now().truncatedTo(ChronoUnit.SECONDS).minusSeconds(1);

        line("## " + env("LOAD_LABEL", "QuizBuks load test") + " (run " + run + ", " + Instant.now()
            .truncatedTo(ChronoUnit.SECONDS) + ")");
        line("");
        line("Target " + baseUrl + "; " + takers + " takers, " + (rate > 0 ? String.format(Locale.ROOT,
            "arriving at %.1f a second (open loop)", rate) : concurrency + " at a time") + ", each starting and "
            + "submitting one attempt of one publication (all answers right, reward " + reward + " each); " + readers
            + " readers of the feed and the publication's page; baseline of the readers alone " + baseline.toSeconds()
            + " s.");
        line("");

        long setupStart = System.nanoTime();
        String admin = login(env("LOAD_ADMIN_USER", "admin"), required("LOAD_ADMIN_PASSWORD"), "admin");
        setup(admin, runStarted);
        String sponsor = verifiedUser("sponsor", "load-sponsor@quizbuks.load", "Load Sponsor");
        approveSponsor(admin, sponsor);
        fund(sponsor, reward * takers + 10 * reward);
        String publicationId = publication(admin, sponsor, reward, takers);
        List<String> takerTokens = users("load-taker-", takers, setupConcurrency);
        List<String> readerTokens = users("load-reader-", readers, setupConcurrency);
        BigDecimal balanceBefore = balance(sponsor);
        log("setup done in " + (System.nanoTime() - setupStart) / 1_000_000_000L + " s; publication " + publicationId
            + ", wallet " + balanceBefore);

        Database db = database();
        // Readers alone.
        Map<String, Stats> quiet = readers(readerTokens, publicationId, () -> true, baseline);
        line("### Readers alone (" + baseline.toSeconds() + " s)");
        line("");
        line(HEADER);
        quiet.values().forEach(stats -> line(stats.row(baseline.toNanos() / 1e9)));
        line("");

        // The load, with the readers alongside.
        if (db != null) {
            db.reset();
        }
        Load load = Load.of();
        AtomicInteger next = new AtomicInteger();
        AtomicBoolean playing = new AtomicBoolean(true);
        Database.Sampler sampler = db == null ? null : db.sample();
        long began = System.nanoTime();
        Map<String, Stats> busy;
        List<Future<?>> playersDone = new ArrayList<>();
        try (ExecutorService readerPool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Map<String, Stats>> reading = readerPool.submit(() -> readers(readerTokens, publicationId,
                playing::get, null));
            try (ExecutorService players = Executors.newVirtualThreadPerTaskExecutor()) {
                if (rate > 0) {
                    // Open loop: taker n arrives at n / rate seconds, whatever the answers before it took.
                    long t0 = System.nanoTime();
                    for (int n = 0; n < takerTokens.size(); n++) {
                        long wait = t0 + (long) (n * 1e9 / rate) - System.nanoTime();
                        if (wait > 0) {
                            Thread.sleep(Duration.ofNanos(wait));
                        }
                        String token = takerTokens.get(n);
                        playersDone.add(players.submit(() -> play(token, publicationId, load)));
                    }
                }
                for (int i = 0; rate <= 0 && i < concurrency; i++) {
                    playersDone.add(players.submit(() -> {
                        for (int n = next.getAndIncrement(); n < takerTokens.size(); n = next.getAndIncrement()) {
                            play(takerTokens.get(n), publicationId, load);
                        }
                    }));
                }
            }
            playing.set(false);
            busy = reading.get();
        }
        // A taker that failed (an unexpected answer, a submission that never finished) fails the run.
        for (Future<?> player : playersDone) {
            player.get();
        }
        double seconds = (System.nanoTime() - began) / 1e9;
        if (sampler != null) {
            sampler.stop();
        }
        // The takers whose start never got through finish now (unrecorded), so that no attempt is left to expire.
        drain(publicationId, load);
        Stats starts = load.starts();
        Stats submits = load.submits();
        Map<String, LongAdder> rewards = load.rewards();
        long done = submits.count("200 OK");
        line("### Under load (" + String.format(Locale.ROOT, "%.1f", seconds) + " s)");
        line("");
        line(String.format(Locale.ROOT, "**%.1f completions/s** (%d submissions accepted of %d takers in %.1f s); "
            + "steady (between the 10th and the 90th percentile of the completions) **%.1f / s**.", done / seconds, done,
            takers, seconds, load.steadyRate()));
        line("");
        line(HEADER);
        line(starts.row(seconds));
        line(submits.row(seconds));
        line(load.startsSeen().row(seconds));
        line(load.submitsSeen().row(seconds));
        line(load.completions().row(seconds));
        busy.values().forEach(stats -> line(stats.row(seconds)));
        line("");
        Stats quietFeed = quiet.get("feed");
        Stats busyFeed = busy.get("feed");
        line(String.format(Locale.ROOT, "Home feed p95: %.0f ms alone, %.0f ms under load (%.1f x).",
            ms(quietFeed.latency.getValueAtPercentile(95)), ms(busyFeed.latency.getValueAtPercentile(95)),
            (double) busyFeed.latency.getValueAtPercentile(95) / Math.max(1, quietFeed.latency.getValueAtPercentile(95))));
        line(String.format(Locale.ROOT, "Busy answers (503 PROCESS_BUSY): %d of the starts, %d of the submissions; "
            + "client timeouts (%d s): %d and %d; %d retries (as the App: same key, Retry-After); "
            + "takers still busy after their retries: %d starts, %d submissions (finished afterwards, unrecorded).",
            starts.count(BUSY), submits.count(BUSY), requestTimeout.toSeconds(), starts.count(TIMED_OUT + " TIMEOUT"),
            submits.count(TIMED_OUT + " TIMEOUT"), load.retries().sum(), load.unfinished().size(),
            load.submitsSeen().count() - load.submitsSeen().count("200 OK") - load.submitsSeen().codes.entrySet()
                .stream().filter(e -> e.getKey().startsWith("422")).mapToLong(e -> e.getValue().sum()).sum()));

        // The money: the sponsor's wallet went down by exactly the rewards paid.
        BigDecimal paid = BigDecimal.valueOf(rewards.getOrDefault("PAID", new LongAdder()).sum());
        BigDecimal balanceAfter = balance(sponsor);
        boolean consistent = balanceBefore.subtract(balanceAfter).compareTo(paid) == 0;
        line(String.format(Locale.ROOT, "Wallet check: %s rewards paid, %s pending; the wallet went from %s to %s - %s",
            paid, rewards.getOrDefault("PENDING", new LongAdder()).sum(), balanceBefore.toPlainString(),
            balanceAfter.toPlainString(), consistent ? "consistent." : "MISMATCH."));
        line("");
        if (db != null) {
            sampler.print();
            db.statements(done);
            db.close();
        }
        String path = System.getenv("LOAD_REPORT");
        if (path != null && !path.isBlank()) {
            Files.writeString(Path.of(path), report.toString());
        }
        if (!consistent || starts.errors() + submits.errors() > 0) {
            System.exit(1);
        }
    }

    // ---- the load -----------------------------------------------------------------------------------------------

    /** The calls of the load: each HTTP answer, and each operation as the taker sees it (with its retries). */
    record Load(Stats starts, Stats submits, Stats startsSeen, Stats submitsSeen, Stats completions,
        Map<String, LongAdder> rewards, LongAdder retries, List<String> unfinished, List<Long> finishedAt) {

        static Load of() {
            return new Load(new Stats("start (QB_ATTEMPT_START), each call"),
                new Stats("submit (QB_ATTEMPT_SUBMIT), each call"), new Stats("start as the taker sees it"),
                new Stats("submit as the taker sees it"), new Stats("start + submit, one taker"),
                new ConcurrentHashMap<>(), new LongAdder(), java.util.Collections.synchronizedList(new ArrayList<>()),
                java.util.Collections.synchronizedList(new ArrayList<>()));
        }

        /**
         * Completions a second between the 10th and the 90th percentile of the accepted submissions' times: the
         * steady rate, without the ramp and the tail (the last takers waiting out their back-off).
         */
        double steadyRate() {
            List<Long> times;
            synchronized (finishedAt) {
                times = new ArrayList<>(finishedAt);
            }
            java.util.Collections.sort(times);
            if (times.size() < 20) {
                return Double.NaN;
            }
            int from = times.size() / 10;
            int to = times.size() * 9 / 10;
            return (to - from) / ((times.get(to) - times.get(from)) / 1e9);
        }
    }

    /**
     * One call of the load as the App makes it (play/busy.ts): a busy answer (503 {@code PROCESS_BUSY}) or a timeout
     * is tried again with the same {@code Idempotency-Key} after the doubling back-off and never sooner than
     * {@code Retry-After}, at most {@link #BUSY_ATTEMPTS} times in all. Every answer goes to {@code each}, the last one
     * (with the whole time) to {@code seen}.
     */
    private Answer retried(Stats each, Stats seen, LongAdder retries, java.util.function.Supplier<Answer> call) {
        long t0 = System.nanoTime();
        long waited = 0;
        for (int attempt = 1; ; attempt++) {
            Answer answer = call.get();
            each.record(answer);
            boolean busy = BUSY.equals(answer.status() + " " + answer.code());
            if (!retryBusy || !(busy || answer.status() == TIMED_OUT) || attempt >= BUSY_ATTEMPTS) {
                Answer whole = new Answer(answer.status(), answer.body(), System.nanoTime() - t0);
                if (seen != null) {
                    seen.record(whole);
                }
                return whole;
            }
            long after = busy ? answer.json().path("retryAfter").asLong(0) * 1000 : 0;
            long wait = Math.max(after, Math.min(BUSY_LONGEST_DELAY_MS, BUSY_FIRST_DELAY_MS << (attempt - 1)));
            long delay = (long) (wait * (1 + 0.25 * ThreadLocalRandom.current().nextDouble()));
            if (waited + delay > BUSY_TOTAL_WAIT_MS) {
                Answer whole = new Answer(answer.status(), answer.body(), System.nanoTime() - t0);
                if (seen != null) {
                    seen.record(whole);
                }
                return whole;
            }
            waited += delay;
            retries.increment();
            try {
                Thread.sleep(Duration.ofMillis(delay));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return answer;
            }
        }
    }

    /**
     * A taker's start and submission. One that did not finish (busy or timed out after the retries) is noted, and
     * finished after the load ({@link #drain}): otherwise its attempt would expire minutes later and pay its reward
     * from the sponsor's wallet during a later run.
     */
    private void play(String token, String publicationId, Load load) {
        if (!play(token, publicationId, load, false)) {
            load.unfinished().add(token);
        }
    }

    /** Returns whether the taker's attempt finished (or was refused); {@code drain} records nothing but the reward. */
    private boolean play(String token, String publicationId, Load load, boolean drain) {
        long t0 = System.nanoTime();
        Stats ignored = new Stats("ignored");
        LongAdder retries = drain ? new LongAdder() : load.retries();
        // As the App starts (useStartAttempt): one key for the start and its retries.
        String startKey = UUID.randomUUID().toString();
        Answer started = retried(drain ? ignored : load.starts(), drain ? null : load.startsSeen(), retries,
            () -> call("POST", "/api/processes/QB_ATTEMPT_START/latest", Map.of("publicationId", publicationId),
                token, "Idempotency-Key", startKey));
        if (started.status() != 200) {
            return started.status() == 422;
        }
        JsonNode output = started.json().get("output");
        List<Map<String, Object>> answers = new ArrayList<>();
        for (JsonNode question : output.get("questions")) {
            for (JsonNode option : question.get("options")) {
                if (option.get("text").asString().startsWith("Right")) {
                    answers.add(Map.of("questionNo", question.get("no").asInt(), "selected",
                        List.of(option.get("no").asInt())));
                }
            }
        }
        // As the App submits (PlayPage): the publication (the queue's key), the last question, every answer, and a key
        // of its own for the submission and its retries.
        Map<String, Object> input = Map.of("attemptId", output.get("attemptId").asString(), "publicationId",
            publicationId, "currentIndex", answers.size() - 1, "answers", answers);
        String submitKey = UUID.randomUUID().toString();
        Stats each = drain ? ignored : load.submits();
        Answer submitted = submit(token, input, submitKey, each, drain ? null : load.submitsSeen(), retries,
            load.rewards());
        // Still busy after the retries: the taker presses again, with the same key (a submission that went through is
        // answered again, not repeated). Counted as calls and in the completion's time; "as the taker sees it" keeps
        // the first round.
        for (int i = 0; i < 20 && !finishedAgain(submitted); i++) {
            pauseBeforeAgain();
            submitted = submit(token, input, submitKey, each, null, retries, load.rewards());
        }
        if (!finishedAgain(submitted)) {
            throw new IllegalStateException("a submission did not finish: " + submitted.status());
        }
        if (!drain) {
            load.completions().record(new Answer(submitted.status(), submitted.body(), System.nanoTime() - t0));
            if (submitted.status() == 200) {
                load.finishedAt().add(System.nanoTime());
            }
        }
        return true;
    }

    /** Between rounds of a taker pressing again: without the App's own waits ({@code LOAD_RETRY_BUSY=no}) a second. */
    private void pauseBeforeAgain() {
        if (retryBusy) {
            return;
        }
        try {
            Thread.sleep(Duration.ofSeconds(1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean finishedAgain(Answer answer) {
        return answer.status() == 200 || answer.status() == 422;
    }

    private Answer submit(String token, Map<String, Object> input, String key, Stats each, Stats seen,
        LongAdder retries, Map<String, LongAdder> rewards) {
        Answer submitted = retried(each, seen, retries, () -> call("POST", "/api/processes/QB_ATTEMPT_SUBMIT/latest",
            input, token, "Idempotency-Key", key));
        if (submitted.status() == 200) {
            JsonNode result = submitted.json().get("output");
            rewards.computeIfAbsent(result.get("rewardStatus").asString(), s -> new LongAdder())
                .add(result.get("reward").decimalValue().longValueExact());
        }
        return submitted;
    }

    /** After the load: the takers whose start never got through start (or continue) and submit now, unrecorded. */
    private void drain(String publicationId, Load load) {
        for (String token : List.copyOf(load.unfinished())) {
            for (int i = 0; i < 20 && !play(token, publicationId, load, true); i++) {
                pauseBeforeAgain();
            }
        }
    }

    /** Each reader in turn reads the feed, then the page of the publication (two templates), until {@code go} fails. */
    private Map<String, Stats> readers(List<String> tokens, String publicationId, BooleanSupplier go,
        Duration duration) throws Exception {
        Map<String, Stats> stats = new LinkedHashMap<>();
        stats.put("feed", new Stats("feed"));
        stats.put("page", new Stats("page"));
        stats.put("tiers", new Stats("tiers"));
        long end = duration == null ? Long.MAX_VALUE : System.nanoTime() + duration.toNanos();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (String token : tokens) {
                pool.submit(() -> {
                    long round = 0;
                    long first = System.nanoTime();
                    while (go.getAsBoolean() && System.nanoTime() < end) {
                        if (readRate > 0) {
                            // Paced: round n of this reader at n / rate seconds (late rounds are not made up).
                            long due = first + (long) (round++ * 1e9 / readRate) - System.nanoTime();
                            if (due > 0) {
                                Thread.sleep(Duration.ofNanos(due));
                            } else if (due < -1_000_000_000L) {
                                first = System.nanoTime();
                                round = 1;
                            }
                        }
                        String at = Instant.now().toString();
                        Map<String, Object> feedParams = new LinkedHashMap<>();
                        feedParams.put("at", at);
                        feedParams.put("country", null);
                        feedParams.put("q", null);
                        stats.get("feed").record(call("POST", "/api/queries/qb.app.feed", Map.of("params", feedParams,
                            "offset", 0, "limit", 20), token));
                        Map<String, Object> pageParams = new LinkedHashMap<>();
                        pageParams.put("publicationId", publicationId);
                        pageParams.put("at", at);
                        pageParams.put("country", null);
                        stats.get("page").record(call("POST", "/api/queries/qb.app.publication", Map.of("params",
                            pageParams), token));
                        stats.get("tiers").record(call("POST", "/api/queries/qb.app.publication-tiers",
                            Map.of("params", pageParams), token));
                    }
                    return null;
                });
            }
        }
        return stats;
    }

    // ---- setup --------------------------------------------------------------------------------------------------

    /** {@code QB_SETUP}, and its proposals published by the second administrator (four eyes, decision D40). */
    private void setup(String admin, Instant runStarted) throws Exception {
        JsonNode made = run(admin, "QB_SETUP", Map.of());
        // Only what this run's QB_SETUP proposed: never anybody else's pending changes.
        List<String> ours = new ArrayList<>();
        made.path("proposals").forEach(id -> ours.add(id.asString()));
        if (ours.isEmpty()) {
            return;
        }
        String adminId = subject(admin);
        List<String> publishable = new ArrayList<>();
        for (JsonNode change : rows(admin, CONTROL_CHANGES, List.of(Map.of("field", "status", "op", "eq", "value",
            "PROPOSED"), Map.of("field", "proposedBy", "op", "eq", "value", adminId)), 1000)) {
            String id = change.get("id").asString();
            Instant created = createdAt(change);
            if (ours.contains(id) && created != null && !created.isBefore(runStarted)) {
                publishable.add(id);
            }
        }
        if (publishable.size() != ours.size()) {
            throw new IllegalStateException("QB_SETUP proposed " + ours + " but only " + publishable
                + " are pending, by " + adminId + ", since " + runStarted + ": refusing to publish anything");
        }
        String publisher = publisher(admin);
        for (String id : publishable) {
            run(publisher, "CONTROL_CHANGE_PUBLISH", Map.of("changeId", id));
        }
        log("published the " + publishable.size() + " proposals of this run's QB_SETUP");
    }

    /** When a row of a temporal dataset was written: its version's start, as the dataset API gives it. */
    static Instant createdAt(JsonNode row) {
        for (String name : List.of("effectStartTime", "validFrom", "createdAt")) {
            JsonNode value = row.path(name);
            if (value.isMissingNode() || value.isNull()) {
                value = row.path("attributes").path(name);
            }
            if (!value.isMissingNode() && !value.isNull()) {
                return Instant.parse(value.asString());
            }
        }
        return null;
    }

    /** The subject (user id) of an access token. */
    static String subject(String token) {
        String[] parts = token.split("[.]");
        JsonNode claims = JSON.readTree(java.util.Base64.getUrlDecoder().decode(parts[1]));
        return claims.get("sub").asString();
    }

    /**
     * Refuses to drive anything but this machine (the tool creates users, top-ups and payments through the stub)
     * unless {@code LOAD_ALLOW_REMOTE=yes}.
     */
    private void refuseRemote() {
        String host = URI.create(baseUrl).getHost();
        boolean loopback;
        try {
            loopback = host != null && java.net.InetAddress.getByName(host).isLoopbackAddress();
        } catch (java.net.UnknownHostException e) {
            loopback = false;
        }
        if (!loopback && !"yes".equals(System.getenv("LOAD_ALLOW_REMOTE"))) {
            throw new IllegalStateException("LOAD_BASE_URL " + baseUrl + " is not this machine; set "
                + "LOAD_ALLOW_REMOTE=yes to load a remote installation (never a production one)");
        }
    }

    private String publisher(String admin) throws Exception {
        String user = "load-publisher";
        String role = env("LOAD_PUBLISHER_ROLE", "QB_LOAD_PUBLISHER");
        String token = tryLogin(user, password, "admin");
        if (token != null) {
            return token;
        }
        List<JsonNode> found = rows(admin, ROLES, List.of(Map.of("field", "roleCode", "op", "eq", "value", role)), 1);
        String roleId;
        if (found.isEmpty()) {
            roleId = insert(admin, ROLES, Map.of("roleCode", role, "labels", Map.of("en", "Load test publisher"),
                "enabled", true)).get("id").asString();
            insert(admin, ROLE_PERMISSIONS, Map.of("roleId", roleId, "permission", "control.publish"));
        } else {
            roleId = found.getFirst().get("id").asString();
        }
        String userId = run(admin, "SEC_USER_CREATE", Map.of("userName", user, "displayName", "Load publisher",
            "password", password)).get("userId").asString();
        insert(admin, USER_ROLES, Map.of("userId", userId, "roleId", roleId));
        return login(user, password, "admin");
    }

    private void approveSponsor(String admin, String sponsor) throws Exception {
        List<JsonNode> mine = rows(sponsor, SPONSORS, List.of(), 1);
        if (!mine.isEmpty() && "APPROVED".equals(text(mine.getFirst().path("attributes").path("reviewStatus")))) {
            return;
        }
        JsonNode applied = run(sponsor, "QB_SPONSOR_APPLY", Map.of("displayName", "Load Sponsor", "company",
            "QuizBuks Load Co", "acceptTerms", true));
        if ("PENDING".equals(text(applied.path("approval")))) {
            run(admin, "APPROVAL_DECIDE", Map.of("requestId", applied.get("approvalRequestId").asString(), "decision",
                "APPROVE"));
        }
        waitFor("the sponsor's approval", () -> "APPROVED".equals(text(rows(sponsor, SPONSORS, List.of(), 1)
            .getFirst().path("attributes").path("reviewStatus"))));
    }

    /** Tops the sponsor's wallet up through the stub's checkout until it holds {@code needed}. */
    private void fund(String sponsor, long needed) throws Exception {
        if (balance(sponsor).compareTo(BigDecimal.valueOf(needed)) >= 0) {
            return;
        }
        // Before a new top-up asks the application for a Checkout: an earlier one that went anywhere but this
        // application's host (the Stripe stub runs beside it), or in live mode, means real Stripe is configured.
        for (JsonNode row : rows(sponsor, TOPUPS, List.of(), 100)) {
            requireStub(row.path("attributes"));
        }
        while (balance(sponsor).compareTo(BigDecimal.valueOf(needed)) < 0) {
            BigDecimal before = balance(sponsor);
            long amount = Math.min(TOPUP_MAX, Math.max(1000, needed - before.longValue()));
            String topUp = run(sponsor, "QB_TOPUP_START", Map.of("amount", amount)).get("topUpId").asString();
            String[] checkout = new String[1];
            waitFor("the checkout", () -> {
                for (JsonNode row : rows(sponsor, TOPUPS, List.of(), 100)) {
                    if (topUp.equals(row.get("id").asString()) && row.path("attributes").hasNonNull("checkoutUrl")) {
                        requireStub(row.path("attributes"));
                        checkout[0] = row.path("attributes").get("checkoutUrl").asString();
                        return true;
                    }
                }
                return false;
            });
            // The stub's page, paid as its Pay button does: the stub posts the signed event back.
            Answer paid = send("POST", checkout[0] + "/paid", "", null, "text/plain");
            if (paid.status() / 100 > 3) {
                throw new IllegalStateException("the stub refused the payment: " + paid.status());
            }
            waitFor("the top-up to be credited", () -> balance(sponsor).compareTo(before.add(BigDecimal.valueOf(
                amount))) >= 0);
            log("topped up " + amount);
        }
    }

    /**
     * Refuses a top-up whose checkout is not the Stripe stub beside the application (the same host as
     * {@code LOAD_BASE_URL}) or that Stripe reported in live mode: the tool pays only the stub's checkouts.
     */
    private void requireStub(JsonNode topUp) {
        if (topUp.path("livemode").asBoolean(false)) {
            throw new IllegalStateException("a top-up is in Stripe's live mode: refusing to go on");
        }
        JsonNode url = topUp.get("checkoutUrl");
        if (url != null && !url.isNull()) {
            String host = URI.create(url.asString()).getHost();
            if (host == null || !host.equalsIgnoreCase(URI.create(baseUrl).getHost())) {
                throw new IllegalStateException("the checkout " + url.asString() + " is not on " + baseUrl
                    + "'s host (the Stripe stub): refusing to pay it");
            }
        }
    }

    /** A new quiz, its version and a publication of it, approved and LIVE: a large budget, no winner cap. */
    private String publication(String admin, String sponsor, long reward, int takers) throws Exception {
        String quiz = run(sponsor, "QB_QUIZ_SAVE", Map.of("title", "Load " + run, "timeLimitSec", 600, "intro",
            "Two questions.")).get("quizId").asString();
        for (int q = 1; q <= 2; q++) {
            run(sponsor, "QB_QUESTION_SAVE", Map.of("quizId", quiz, "stem", "Question " + q, "points", 1, "options",
                List.of(Map.of("text", "Right " + q, "correct", true), Map.of("text", "Wrong " + q, "correct", false),
                    Map.of("text", "Other " + q, "correct", false))));
        }
        run(sponsor, "QB_QUIZ_PUBLISH_VERSION", Map.of("quizId", quiz));
        String version = query(sponsor, "qb.sponsor.quiz-versions", Map.of("quizId", quiz)).getFirst()
            .get("versionId").asString();
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Map<String, Object> save = new LinkedHashMap<>();
        save.put("versionId", version);
        save.put("name", "Load " + run);
        save.put("regions", List.of("GLOBAL"));
        save.put("startAt", now.minusSeconds(60).toString());
        save.put("endAt", now.plus(Duration.ofDays(1)).toString());
        save.put("passScore", 2);
        save.put("budgetCap", reward * (takers + 10));
        save.put("maxWinners", null);
        save.put("tiers", List.of(Map.of("fromScore", 0, "toScore", 1, "reward", 0), Map.of("fromScore", 2,
            "toScore", 2, "reward", reward)));
        String publication = run(sponsor, "QB_PUBLICATION_SAVE", save).get("publicationId").asString();
        run(sponsor, "QB_PUBLICATION_SUBMIT", Map.of("publicationId", publication));
        String path = "/api/datasets/" + encode(PUBLICATIONS) + "/entities/" + publication;
        JsonNode attributes = ok(send("GET", path, null, sponsor, null)).path("attributes");
        if (!"LIVE".equals(text(attributes.path("status")))) {
            run(admin, "APPROVAL_DECIDE", Map.of("requestId", attributes.get("approvalRequestId").asString(),
                "decision", "APPROVE"));
        }
        waitFor("the publication to go live", () -> "LIVE".equals(text(ok(send("GET", path, null, sponsor, null))
            .path("attributes").path("status"))));
        return publication;
    }

    /** {@code count} verified users of the app ({@code prefix}0 ...), registered once, each signed in now. */
    private List<String> users(String prefix, int count, int concurrency) throws Exception {
        List<Future<String>> tokens = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(concurrency)) {
            for (int i = 0; i < count; i++) {
                String email = prefix + i + "@quizbuks.load";
                tokens.add(pool.submit(() -> verifiedUser("app", email, "Load " + email.substring(0,
                    email.indexOf('@')))));
            }
        }
        List<String> result = new ArrayList<>();
        for (Future<String> token : tokens) {
            result.add(token.get());
        }
        return result;
    }

    /** Registers at the entry and opens the mailed link, as the person would; signs in when the account exists. */
    private String verifiedUser(String entry, String email, String name) throws Exception {
        String token = tryLogin(email, password, entry);
        if (token != null) {
            return token;
        }
        ok(send("POST", "/api/auth/register", JSON.writeValueAsString(Map.of("email", email, "password", password,
            "displayName", name, "locale", "en", "entry", entry)), null, "application/json"));
        String[] id = new String[1];
        waitFor("the mail to " + email, () -> {
            JsonNode found = ok(send("GET", mailpit + "/api/v1/search?query=" + encode("to:" + email), null, null,
                null));
            for (JsonNode message : found.path("messages")) {
                if (message.get("Subject").asString().matches("(?is).*(verify|confirm|address).*")) {
                    id[0] = message.get("ID").asString();
                    return true;
                }
            }
            return false;
        });
        String text = ok(send("GET", mailpit + "/api/v1/message/" + id[0], null, null, null)).get("Text").asString();
        String link = null;
        Matcher matcher = LINK.matcher(text);
        while (matcher.find()) {
            if (matcher.group().contains("/auth/verify-email")) {
                link = matcher.group().replace("&amp;", "&");
                break;
            }
        }
        if (link == null) {
            throw new IllegalStateException("no verification link in the mail to " + email);
        }
        String verification = null;
        for (String pair : URI.create(link).getRawQuery().split("&")) {
            if (pair.startsWith("token=")) {
                verification = java.net.URLDecoder.decode(pair.substring(6), StandardCharsets.UTF_8);
            }
        }
        JsonNode check = ok(send("POST", "/api/auth/verify-email/check", JSON.writeValueAsString(Map.of("token",
            verification)), null, "application/json"));
        Map<String, Object> body = check.path("passwordRequired").asBoolean(false)
            ? Map.of("token", verification, "password", password) : Map.of("token", verification);
        ok(send("POST", "/api/auth/verify-email", JSON.writeValueAsString(body), null, "application/json"));
        return login(email, password, entry);
    }

    private BigDecimal balance(String sponsor) throws Exception {
        List<JsonNode> wallets = rows(sponsor, WALLETS, List.of(), 1);
        JsonNode balance = wallets.isEmpty() ? null : wallets.getFirst().path("attributes").get("balance");
        return balance == null || balance.isNull() ? BigDecimal.ZERO : new BigDecimal(balance.asString());
    }

    // ---- HTTP ---------------------------------------------------------------------------------------------------

    private JsonNode run(String token, String process, Map<String, ?> input) throws Exception {
        Answer answer = send("POST", "/api/processes/" + process + "/latest", JSON.writeValueAsString(input), token,
            "application/json", "Idempotency-Key", UUID.randomUUID().toString());
        return ok(answer).get("output");
    }

    private List<JsonNode> rows(String token, String dataset, List<Map<String, Object>> filters, int limit)
        throws Exception {
        JsonNode answer = ok(send("POST", "/api/datasets/" + encode(dataset) + "/query",
            JSON.writeValueAsString(Map.of("filters", filters, "limit", limit)), token, "application/json"));
        List<JsonNode> items = new ArrayList<>();
        answer.path("items").forEach(items::add);
        return items;
    }

    private JsonNode insert(String token, String dataset, Map<String, Object> attributes) throws Exception {
        return ok(send("POST", "/api/datasets/" + encode(dataset) + "/commit", JSON.writeValueAsString(Map.of(
            "changes", List.of(Map.of("action", "INSERT", "attributes", attributes)))), token, "application/json"))
            .get(0);
    }

    private List<JsonNode> query(String token, String template, Map<String, Object> params) throws Exception {
        JsonNode answer = ok(send("POST", "/api/queries/" + encode(template), JSON.writeValueAsString(Map.of("params",
            params, "limit", 100)), token, "application/json"));
        List<JsonNode> items = new ArrayList<>();
        answer.path("items").forEach(items::add);
        return items;
    }

    private String tryLogin(String user, String secret, String entry) throws Exception {
        Answer answer = send("POST", "/api/auth/login", JSON.writeValueAsString(Map.of("userName", user, "password",
            secret, "entry", entry)), null, "application/json");
        return answer.status() == 200 ? answer.json().get("accessToken").asString() : null;
    }

    private String login(String user, String secret, String entry) throws Exception {
        String token = tryLogin(user, secret, entry);
        if (token == null) {
            throw new IllegalStateException(user + " cannot sign in at " + entry);
        }
        return token;
    }

    /**
     * A call of the load, timed, waiting at most {@code LOAD_TIMEOUT}: not answered by then is 599 {@code TIMEOUT}
     * (the client went away; the server may still finish it), another connection failure 599 {@code CONNECTION}.
     */
    private Answer call(String method, String path, Object body, String token, String... headers) {
        long started = System.nanoTime();
        try {
            return send(requestTimeout, method, path, JSON.writeValueAsString(body), token, "application/json",
                headers);
        } catch (java.net.http.HttpTimeoutException e) {
            return new Answer(TIMED_OUT, "TIMEOUT", System.nanoTime() - started);
        } catch (IOException e) {
            return new Answer(TIMED_OUT, null, System.nanoTime() - started);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Answer(TIMED_OUT, null, System.nanoTime() - started);
        }
    }

    private Answer send(String method, String path, String body, String token, String contentType,
        String... headers) throws IOException, InterruptedException {
        return send(Duration.ofSeconds(120), method, path, body, token, contentType, headers);
    }

    private Answer send(Duration timeout, String method, String path, String body, String token, String contentType,
        String... headers) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(path.startsWith("http") ? path
                : baseUrl + path))
            .timeout(timeout)
            .header("Accept-Language", "en");
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        for (int i = 0; i + 1 < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        if (body == null) {
            request.GET();
        } else {
            request.header("Content-Type", contentType).method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        long started = System.nanoTime();
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Answer(response.statusCode(), response.body(), System.nanoTime() - started);
    }

    private static JsonNode ok(Answer answer) {
        if (answer.status() / 100 != 2) {
            throw new IllegalStateException("HTTP " + answer.status() + ": " + answer.body());
        }
        return answer.json();
    }

    // ---- the database (optional) --------------------------------------------------------------------------------

    /**
     * The statistics of the application's database ({@code LOAD_DB_NAME}) when {@code LOAD_DB_URL} is set: a
     * connection to a database of the same server where {@code pg_stat_statements} is installed (not the
     * application's own, whose schema belongs to its migrations); null otherwise.
     */
    private Database database() throws SQLException {
        String url = System.getenv("LOAD_DB_URL");
        if (url == null || url.isBlank()) {
            return null;
        }
        String name = env("LOAD_DB_NAME", "quizbuks");
        if (!name.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("LOAD_DB_NAME must be a plain database name");
        }
        return new Database(DriverManager.getConnection(url, env("LOAD_DB_USER", "postgres"),
            env("LOAD_DB_PASSWORD", "")), name);
    }

    /** pg_stat_statements and pg_stat_activity of the application's database. */
    final class Database implements AutoCloseable {
        private final Connection connection;
        private final String name;

        private Database(Connection connection, String name) {
            this.connection = connection;
            this.name = name;
        }

        void reset() throws SQLException {
            try (Statement statement = connection.createStatement()) {
                // This database's statements only (PostgreSQL 16: user, database, query id; 0 = any).
                statement.execute("SELECT pg_stat_statements_reset(0, (SELECT oid FROM pg_database WHERE datname = '"
                    + name + "'), 0)");
            }
        }

        /** Samples every 100 ms how many of the database's connections are busy, and how many wait for a lock. */
        Sampler sample() throws SQLException {
            return new Sampler(DriverManager.getConnection(System.getenv("LOAD_DB_URL"), env("LOAD_DB_USER",
                "postgres"), env("LOAD_DB_PASSWORD", "")));
        }

        /** The statements that took most of the database's time during the load, per completed attempt. */
        void statements(long completions) throws SQLException {
            line("Statements by total time during the load (pg_stat_statements; per completion = calls / "
                + completions + "):");
            line("");
            line("| total ms | calls | per completion | mean ms | statement |");
            line("|---|---|---|---|---|");
            double total = 0;
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT s.total_exec_time, s.calls, s.mean_exec_time, "
                     + "left(regexp_replace(s.query, '\\s+', ' ', 'g'), 140) AS query FROM pg_stat_statements s "
                     + "JOIN pg_database d ON d.oid = s.dbid WHERE d.datname = '" + name + "' "
                     + "ORDER BY s.total_exec_time DESC")) {
                int shown = 0;
                while (rows.next()) {
                    total += rows.getDouble(1);
                    if (shown++ < 25) {
                        line(String.format(Locale.ROOT, "| %.0f | %d | %.2f | %.3f | `%s` |", rows.getDouble(1),
                            rows.getLong(2), rows.getLong(2) / (double) Math.max(1, completions), rows.getDouble(3),
                            rows.getString(4).replace("|", "\\|").replace("`", "'")));
                    }
                }
            }
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT sum(s.calls) FROM pg_stat_statements s "
                     + "JOIN pg_database d ON d.oid = s.dbid WHERE d.datname = '" + name + "'")) {
                rows.next();
                line("");
                line(String.format(Locale.ROOT, "All statements: %.0f ms in %d calls (%.1f calls per completion).",
                    total, rows.getLong(1), rows.getLong(1) / (double) Math.max(1, completions)));
                line("");
            }
        }

        @Override
        public void close() throws SQLException {
            connection.close();
        }

        /** A background sampler of pg_stat_activity. */
        final class Sampler {
            private final Connection connection;
            private final AtomicBoolean running = new AtomicBoolean(true);
            private final List<int[]> samples = new ArrayList<>();
            private final Thread thread;

            Sampler(Connection connection) {
                this.connection = connection;
                this.thread = Thread.ofVirtual().start(this::loop);
            }

            private void loop() {
                try (Statement statement = connection.createStatement()) {
                    while (running.get()) {
                        try (ResultSet rows = statement.executeQuery("SELECT count(*) FILTER (WHERE state <> 'idle'), "
                            + "count(*) FILTER (WHERE wait_event_type = 'Lock' AND wait_event = 'advisory'), count(*) "
                            + "FROM pg_stat_activity WHERE datname = '" + name + "' AND pid <> pg_backend_pid()")) {
                            rows.next();
                            synchronized (samples) {
                                samples.add(new int[] {rows.getInt(1), rows.getInt(2), rows.getInt(3)});
                            }
                        }
                        Thread.sleep(100);
                    }
                } catch (SQLException | InterruptedException e) {
                    log("sampler stopped: " + e);
                }
            }

            void stop() throws InterruptedException, SQLException {
                running.set(false);
                thread.join();
                connection.close();
            }

            void print() {
                synchronized (samples) {
                    if (samples.isEmpty()) {
                        return;
                    }
                    double busy = samples.stream().mapToInt(s -> s[0]).average().orElse(0);
                    double locked = samples.stream().mapToInt(s -> s[1]).average().orElse(0);
                    double open = samples.stream().mapToInt(s -> s[2]).average().orElse(0);
                    int maxBusy = samples.stream().mapToInt(s -> s[0]).max().orElse(0);
                    int maxLocked = samples.stream().mapToInt(s -> s[1]).max().orElse(0);
                    line(String.format(Locale.ROOT, "Connections during the load (pg_stat_activity every 100 ms, %d "
                        + "samples): %.1f open, %.1f busy (at most %d), %.1f of them waiting for an advisory lock "
                        + "(at most %d).", samples.size(), open, busy, maxBusy, locked, maxLocked));
                    line("");
                }
            }
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    interface Check {
        boolean done() throws Exception;
    }

    private static void waitFor(String what, Check check) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (!check.done()) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("gave up waiting for " + what);
            }
            Thread.sleep(250);
        }
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode() ? null : node.asString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static double ms(long nanos) {
        return nanos / 1e6;
    }

    private void line(String text) {
        System.out.println(text);
        report.append(text).append('\n');
    }

    private static void log(String text) {
        System.err.println("loadTest: " + text);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Set " + name);
        }
        return value;
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private QuizbuksLoadTest() {}
}
