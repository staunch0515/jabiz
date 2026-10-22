package com.jabiz.quizbuks.it;

import com.jabiz.runtime.test.StubHttpServer;
import tools.jackson.databind.json.JsonMapper;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stripe's Connect and transfer endpoints as the payout tests need them (docs/quizbuks/plans/Q6-payments.md, Q6b): a
 * little state, so that what QuizBuks sends is what a later lookup finds. Express accounts (one per idempotency key),
 * onboarding links, and transfers kept by group (a creation under a key Stripe saw answers as before). Faults are
 * scripted by counters: refuse the next creations, make one and lose the answer, fail the next lookups.
 */
final class StripeSim {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final AtomicInteger SEQUENCE = new AtomicInteger();

    /** Transfers by group, and the answer kept for each idempotency key (errors too, as Stripe keeps them). */
    static final Map<String, Map<String, Object>> TRANSFERS = new ConcurrentHashMap<>();
    static final Map<String, StubHttpServer.Answer> ANSWERS = new ConcurrentHashMap<>();
    static final Map<String, String> ACCOUNTS_BY_KEY = new ConcurrentHashMap<>();

    /** The next creations refused with this error code ({@code card_declined}-like 400, or {@code balance_insufficient}). */
    static final AtomicInteger REFUSE = new AtomicInteger();
    static volatile String refuseCode = "transfers_not_allowed";
    /**
     * The next creations made at Stripe, their answer cut off on its way back (a body that is not JSON: as a dropped
     * connection, the attempt fails and is tried again; Stripe keeps the success under the key).
     */
    static final AtomicInteger LOSE_ANSWER = new AtomicInteger();
    /** The group the faults are for (null: every request): other takers' transfers in the same run go through. */
    static volatile String target;
    /** The next lookups of a group answered 500. */
    static final AtomicInteger LOOKUP_FAILS = new AtomicInteger();
    /** The next creations answered 500 without making a transfer. */
    static final AtomicInteger UNAVAILABLE = new AtomicInteger();
    /** The next creations answered 429 (nothing kept under the key). */
    static final AtomicInteger RATE_LIMITED = new AtomicInteger();

    static void reset() {
        TRANSFERS.clear();
        ANSWERS.clear();
        ACCOUNTS_BY_KEY.clear();
        REFUSE.set(0);
        refuseCode = "transfers_not_allowed";
        LOSE_ANSWER.set(0);
        LOOKUP_FAILS.set(0);
        UNAVAILABLE.set(0);
        RATE_LIMITED.set(0);
        target = null;
    }

    private static boolean targeted(String group) {
        String only = target;
        return only == null || only.equals(group);
    }

    private static boolean fault(AtomicInteger counter, String group) {
        return targeted(group) && counter.getAndUpdate(n -> Math.max(0, n - 1)) > 0;
    }

    /** The answer for Connect and transfer requests; null for other paths. */
    static StubHttpServer.Answer answer(StubHttpServer.Received request) {
        String path = request.path();
        if ("POST".equals(request.method()) && "/v1/accounts".equals(path)) {
            Map<String, String> form = form(request.body());
            String key = request.header("Idempotency-Key");
            String id = ACCOUNTS_BY_KEY.computeIfAbsent(key == null ? "none" + SEQUENCE.incrementAndGet() : key,
                k -> "acct_test" + SEQUENCE.incrementAndGet());
            Map<String, Object> account = new LinkedHashMap<>();
            account.put("id", id);
            account.put("object", "account");
            account.put("type", form.get("type"));
            account.put("country", form.get("country"));
            account.put("payouts_enabled", false);
            account.put("details_submitted", false);
            account.put("metadata", Map.of("userId", form.get("metadata[userId]")));
            // Like Stripe's: a v1 Account has no livemode (only the request's key and the event say the mode).
            return json(200, account);
        }
        if ("POST".equals(request.method()) && "/v1/account_links".equals(path)) {
            Map<String, String> form = form(request.body());
            Map<String, Object> link = new LinkedHashMap<>();
            link.put("object", "account_link");
            link.put("url", "https://connect.stripe.com/setup/e/" + form.get("account") + "/x"
                + SEQUENCE.incrementAndGet());
            link.put("expires_at", 4_102_444_800L);
            return json(200, link);
        }
        if ("GET".equals(request.method()) && "/v1/transfers".equals(path)) {
            Map<String, String> query = form(request.query() == null ? "" : request.query());
            if (fault(LOOKUP_FAILS, query.get("transfer_group"))) {
                return StubHttpServer.Answer.json(500, "{\"error\":{\"type\":\"api_error\"}}");
            }
            Map<String, Object> found = TRANSFERS.get(query.get("transfer_group"));
            return json(200, Map.of("object", "list", "data", found == null ? List.of() : List.of(found)));
        }
        if ("POST".equals(request.method()) && "/v1/transfers".equals(path)) {
            String key = request.header("Idempotency-Key");
            Map<String, String> form = form(request.body());
            String group = form.get("transfer_group");
            // A rate limit is answered before the request runs: nothing is kept under the key.
            if (fault(RATE_LIMITED, group)) {
                return StubHttpServer.Answer.json(429, "{\"error\":{\"type\":\"invalid_request_error\","
                    + "\"code\":\"rate_limit\"}}");
            }
            // Whatever the request ran into is kept under its key, errors included, and given again.
            StubHttpServer.Answer earlier = key == null ? null : ANSWERS.get(key);
            if (earlier != null) {
                return earlier;
            }
            if (fault(UNAVAILABLE, group)) {
                return keep(key, StubHttpServer.Answer.json(500, "{\"error\":{\"type\":\"api_error\"}}"));
            }
            if (fault(REFUSE, group)) {
                return keep(key, StubHttpServer.Answer.json(400, "{\"error\":{\"type\":\"invalid_request_error\","
                    + "\"code\":\"" + refuseCode + "\"}}"));
            }
            Map<String, Object> transfer = new LinkedHashMap<>();
            transfer.put("id", "tr_test" + SEQUENCE.incrementAndGet());
            transfer.put("object", "transfer");
            transfer.put("amount", Long.parseLong(form.get("amount")));
            transfer.put("amount_reversed", 0);
            transfer.put("currency", form.get("currency"));
            transfer.put("destination", form.get("destination"));
            transfer.put("transfer_group", form.get("transfer_group"));
            transfer.put("metadata", Map.of("transferId", form.get("metadata[transferId]")));
            transfer.put("livemode", false);
            TRANSFERS.put(form.get("transfer_group"), transfer);
            StubHttpServer.Answer made = keep(key, json(200, transfer));
            if (fault(LOSE_ANSWER, group)) {
                // Made and kept at Stripe; the answer cut off on its way back.
                return StubHttpServer.Answer.json(200, "{\"id\":\"tr_");
            }
            return made;
        }
        return null;
    }

    private static StubHttpServer.Answer keep(String key, StubHttpServer.Answer answer) {
        if (key != null) {
            ANSWERS.put(key, answer);
        }
        return answer;
    }

    /** How many transfers Stripe made in all. */
    static int transfersMade() {
        return TRANSFERS.size();
    }

    static StubHttpServer.Answer json(int status, Object body) {
        return StubHttpServer.Answer.json(status, JSON.writeValueAsString(body));
    }

    static Map<String, String> form(String body) {
        Map<String, String> form = new LinkedHashMap<>();
        if (body == null || body.isBlank()) {
            return form;
        }
        for (String pair : body.split("&")) {
            int equals = pair.indexOf('=');
            if (equals < 0) {
                continue;
            }
            form.put(URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8),
                URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
        }
        return form;
    }

    private StripeSim() {}
}
