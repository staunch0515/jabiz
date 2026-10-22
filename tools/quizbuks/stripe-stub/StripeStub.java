import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * A stand-in for Stripe for local development of QuizBuks's top-ups and payouts (docs/quizbuks/plans/Q6-payments.md,
 * D-Q6-1), matching the dev profile (application-dev.yml): the API on http://localhost:8093, the checkout and
 * onboarding pages on the same address, events signed with the development secrets and posted to
 * http://localhost:8080/api/inbound/stripe (and /api/inbound/stripe-connect for the connected accounts). Nothing of
 * Stripe is called. Run it with the JDK alone:
 *
 * <pre>java tools/quizbuks/stripe-stub/StripeStub.java [port] [webhook URL] [webhook secret] [Connect webhook URL]
 *     [Connect webhook secret]</pre>
 *
 * An empty or missing secret argument is read from {@code STRIPE_STUB_WEBHOOK_SECRET} /
 * {@code STRIPE_STUB_CONNECT_WEBHOOK_SECRET} (so that it is not on the command line), or is the development secret.
 *
 * <ul>
 *   <li>{@code POST /v1/checkout/sessions} creates a checkout from the form QuizBuks sends (one per idempotency key) and
 *       answers it as Stripe would; its address is this stub's page {@code /pay/<id>};</li>
 *   <li>{@code GET /v1/checkout/sessions/<id>} answers the checkout (the stale top-ups' check);</li>
 *   <li>{@code GET /v1/payment_intents/<id>} answers the payment with its charge and balance transaction (a fee of
 *       3.6 %, rounded down);</li>
 *   <li>the page {@code /pay/<id>} has three buttons: pay (the checkout completes, {@code checkout.session.completed}
 *       is signed and posted, the browser goes to the success address), expire ({@code checkout.session.expired}) and
 *       refund ({@code charge.refunded} for the whole amount, after paying);</li>
 *   <li>{@code POST /v1/accounts} creates an Express account (one per idempotency key), {@code POST /v1/account_links}
 *       answers a link to this stub's page {@code /connect/<account>}, whose button enables the account
 *       ({@code account.updated}, signed with the Connect secret and posted to the Connect endpoint);</li>
 *   <li>{@code POST /v1/transfers} makes a transfer (one per idempotency key) and {@code GET /v1/transfers?transfer_group=}
 *       lists a group's.</li>
 * </ul>
 * It listens on this machine only and keeps everything in memory. Never point anything but development at it.
 */
public final class StripeStub {

    private static final Map<String, Map<String, String>> SESSIONS = new ConcurrentHashMap<>();
    private static final Map<String, String> BY_KEY = new ConcurrentHashMap<>();
    private static final Map<String, Map<String, String>> ACCOUNTS = new ConcurrentHashMap<>();
    private static final Map<String, String> TRANSFERS = new ConcurrentHashMap<>();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private static int port;
    private static String webhook;
    private static String secret;
    private static String connectWebhook;
    private static String connectSecret;

    public static void main(String[] args) throws IOException {
        port = args.length > 0 ? Integer.parseInt(args[0]) : 8093;
        webhook = args.length > 1 ? args[1] : "http://localhost:8080/api/inbound/stripe";
        secret = secret(args, 2, "STRIPE_STUB_WEBHOOK_SECRET", "whsec_development_only_quizbuks_stripe_0000");
        connectWebhook = args.length > 3 ? args[3] : "http://localhost:8080/api/inbound/stripe-connect";
        connectSecret = secret(args, 4, "STRIPE_STUB_CONNECT_WEBHOOK_SECRET",
            "whsec_development_only_quizbuks_connect_0000");
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/v1/checkout/sessions", StripeStub::sessions);
        server.createContext("/v1/payment_intents/", StripeStub::paymentIntent);
        server.createContext("/pay/", StripeStub::page);
        server.createContext("/v1/accounts", StripeStub::accounts);
        server.createContext("/v1/account_links", StripeStub::accountLinks);
        server.createContext("/connect/", StripeStub::connectPage);
        server.createContext("/v1/transfers", StripeStub::transfers);
        server.createContext("/", exchange -> answer(exchange, 404, "application/json",
            "{\"error\":{\"type\":\"invalid_request_error\",\"code\":\"resource_missing\"}}"));
        server.start();
        System.out.println("Stripe stub on http://localhost:" + port + ", events to " + webhook + " (Ctrl+C to stop)");
    }

    /**
     * A signing secret: the argument when given and not empty, otherwise the environment variable (which keeps it off
     * the command line, visible to every user of the machine), otherwise the development secret.
     */
    private static String secret(String[] args, int index, String variable, String development) {
        if (args.length > index && !args[index].isEmpty()) {
            return args[index];
        }
        String fromEnvironment = System.getenv(variable);
        return fromEnvironment == null || fromEnvironment.isEmpty() ? development : fromEnvironment;
    }

    private static void sessions(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (exchange.getRequestMethod().equals("POST") && path.equals("/v1/checkout/sessions")) {
            Map<String, String> form = form(new String(exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8));
            String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
            String id = key == null ? null : BY_KEY.get(key);
            if (id == null) {
                id = "cs_test_" + random(24);
                Map<String, String> session = new LinkedHashMap<>(form);
                session.put("id", id);
                session.put("status", "open");
                session.put("payment_status", "unpaid");
                SESSIONS.put(id, session);
                if (key != null) {
                    BY_KEY.put(key, id);
                }
                System.out.println("checkout " + id + " for " + form.get("line_items[0][price_data][unit_amount]")
                    + " " + form.get("line_items[0][price_data][currency]"));
            }
            answer(exchange, 200, "application/json", sessionJson(SESSIONS.get(id)));
            return;
        }
        Map<String, String> session = SESSIONS.get(path.substring("/v1/checkout/sessions/".length()));
        if (exchange.getRequestMethod().equals("GET") && session != null) {
            answer(exchange, 200, "application/json", sessionJson(session));
            return;
        }
        answer(exchange, 404, "application/json", "{\"error\":{\"code\":\"resource_missing\"}}");
    }

    private static void paymentIntent(HttpExchange exchange) throws IOException {
        String id = exchange.getRequestURI().getPath().substring("/v1/payment_intents/".length());
        Map<String, String> session = SESSIONS.values().stream().filter(s -> id.equals(s.get("payment_intent")))
            .findFirst().orElse(null);
        if (session == null) {
            answer(exchange, 404, "application/json", "{\"error\":{\"code\":\"resource_missing\"}}");
            return;
        }
        long amount = Long.parseLong(session.get("line_items[0][price_data][unit_amount]"));
        long fee = amount * 36 / 1000;
        answer(exchange, 200, "application/json", "{\"id\":\"" + id + "\",\"object\":\"payment_intent\","
            + "\"amount\":" + amount + ",\"currency\":\"jpy\",\"latest_charge\":{\"id\":\"" + session.get("charge")
            + "\",\"object\":\"charge\",\"balance_transaction\":{\"id\":\"txn_" + session.get("charge").substring(3)
            + "\",\"object\":\"balance_transaction\",\"amount\":" + amount + ",\"fee\":" + fee + ",\"net\":"
            + (amount - fee) + ",\"currency\":\"jpy\"}}}");
    }

    private static void page(HttpExchange exchange) throws IOException {
        String rest = exchange.getRequestURI().getPath().substring("/pay/".length());
        String[] parts = rest.split("/");
        Map<String, String> session = SESSIONS.get(parts[0]);
        if (session == null) {
            answer(exchange, 404, "text/plain; charset=utf-8", "No such checkout");
            return;
        }
        if (exchange.getRequestMethod().equals("GET")) {
            String amount = session.get("line_items[0][price_data][unit_amount]");
            answer(exchange, 200, "text/html; charset=utf-8", "<!doctype html><meta charset=utf-8>"
                + "<title>Stripe stub</title><h1>Pay " + amount + " JPY</h1><p>Checkout " + session.get("id")
                + " (" + session.get("status") + ", " + session.get("payment_status") + ")</p>"
                + "<form method=post action='/pay/" + session.get("id") + "/paid'><button>Pay</button></form>"
                + "<form method=post action='/pay/" + session.get("id") + "/expire'><button>Expire</button></form>"
                + "<form method=post action='/pay/" + session.get("id") + "/refund'><button>Refund</button></form>");
            return;
        }
        String action = parts.length > 1 ? parts[1] : "";
        switch (action) {
            case "paid" -> {
                if ("open".equals(session.get("status"))) {
                    session.put("status", "complete");
                    session.put("payment_status", "paid");
                    session.put("payment_intent", "pi_" + random(24));
                    session.put("charge", "ch_" + random(24));
                }
                send("checkout.session.completed", sessionJson(session));
                redirect(exchange, session.get("success_url"));
            }
            case "expire" -> {
                if ("open".equals(session.get("status"))) {
                    session.put("status", "expired");
                }
                send("checkout.session.expired", sessionJson(session));
                redirect(exchange, session.get("cancel_url"));
            }
            case "refund" -> {
                String amount = session.get("line_items[0][price_data][unit_amount]");
                send("charge.refunded", "{\"id\":\"" + session.get("charge") + "\",\"object\":\"charge\","
                    + "\"payment_intent\":\"" + session.get("payment_intent") + "\",\"amount\":" + amount
                    + ",\"amount_refunded\":" + amount + ",\"currency\":\"jpy\",\"livemode\":false}");
                redirect(exchange, "/pay/" + session.get("id"));
            }
            default -> answer(exchange, 404, "text/plain; charset=utf-8", "Unknown action");
        }
    }

    private static void accounts(HttpExchange exchange) throws IOException {
        Map<String, String> form = form(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
        String id = key == null ? null : BY_KEY.get(key);
        if (id == null) {
            id = "acct_" + random(16);
            ACCOUNTS.put(id, form);
            if (key != null) {
                BY_KEY.put(key, id);
            }
            System.out.println("account " + id + " for " + form.get("metadata[userId]"));
        }
        answer(exchange, 200, "application/json", accountJson(id, false));
    }

    private static void accountLinks(HttpExchange exchange) throws IOException {
        Map<String, String> form = form(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        String account = text(form.get("account"));
        ACCOUNTS.getOrDefault(account, new ConcurrentHashMap<>()).put("return_url", form.get("return_url"));
        answer(exchange, 200, "application/json", "{\"object\":\"account_link\",\"url\":\"http://localhost:" + port
            + "/connect/" + account + "\",\"expires_at\":" + (Instant.now().getEpochSecond() + 300) + "}");
    }

    private static void connectPage(HttpExchange exchange) throws IOException {
        String[] parts = exchange.getRequestURI().getPath().substring("/connect/".length()).split("/");
        Map<String, String> account = ACCOUNTS.get(parts[0]);
        if (account == null) {
            answer(exchange, 404, "text/plain; charset=utf-8", "No such account");
            return;
        }
        if (exchange.getRequestMethod().equals("GET")) {
            answer(exchange, 200, "text/html; charset=utf-8", "<!doctype html><meta charset=utf-8>"
                + "<title>Stripe stub</title><h1>Payout account " + parts[0] + "</h1>"
                + "<form method=post action='/connect/" + parts[0] + "/enable'><button>Enable</button></form>");
            return;
        }
        sendTo(connectWebhook, connectSecret, "account.updated", accountJson(parts[0], true), parts[0]);
        redirect(exchange, account.get("return_url"));
    }

    private static void transfers(HttpExchange exchange) throws IOException {
        if (exchange.getRequestMethod().equals("GET")) {
            String query = exchange.getRequestURI().getRawQuery();
            String group = query == null ? null : form(query).get("transfer_group");
            String found = group == null ? null : TRANSFERS.get(group);
            answer(exchange, 200, "application/json", "{\"object\":\"list\",\"data\":["
                + (found == null ? "" : found) + "]}");
            return;
        }
        Map<String, String> form = form(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        String group = text(form.get("transfer_group"));
        String transfer = TRANSFERS.computeIfAbsent(group, g -> "{\"id\":\"tr_" + random(24)
            + "\",\"object\":\"transfer\",\"amount\":" + Long.parseLong(form.get("amount"))
            + ",\"amount_reversed\":0,\"currency\":\"" + text(form.get("currency")) + "\",\"destination\":\""
            + text(form.get("destination")) + "\",\"transfer_group\":\"" + g + "\",\"metadata\":{\"transferId\":\""
            + text(form.get("metadata[transferId]")) + "\"},\"livemode\":false}");
        System.out.println("transfer " + group + " of " + form.get("amount") + " " + form.get("currency"));
        answer(exchange, 200, "application/json", transfer);
    }

    /** Like Stripe's v1 Account: no {@code livemode} (the request's key and the event say the mode). */
    private static String accountJson(String id, boolean enabled) {
        Map<String, String> account = ACCOUNTS.getOrDefault(id, Map.of());
        return "{\"id\":\"" + id + "\",\"object\":\"account\",\"type\":\"express\",\"country\":\""
            + text(account.get("country")) + "\",\"payouts_enabled\":" + enabled + ",\"details_submitted\":" + enabled
            + ",\"capabilities\":{\"transfers\":\"" + (enabled ? "active" : "inactive") + "\"},"
            + "\"requirements\":{\"currently_due\":[]},\"metadata\":{\"userId\":\"" + text(account.get("metadata[userId]"))
            + "\"}}";
    }

    /** Signs an event as Stripe does ({@code t=…,v1=HMAC-SHA256(t + "." + body)}) and posts it. */
    private static void send(String type, String object) {
        sendTo(webhook, secret, type, object, null);
    }

    /** As {@link #send}, to an endpoint with its secret; an event of a connected account names it. */
    private static void sendTo(String target, String key, String type, String object, String account) {
        long now = Instant.now().getEpochSecond();
        String body = "{\"id\":\"evt_" + random(24) + "\",\"object\":\"event\",\"api_version\":\"2025-03-31.basil\","
            + "\"created\":" + now + ",\"livemode\":false,\"type\":\"" + type + "\","
            + (account == null ? "" : "\"account\":\"" + account + "\",") + "\"data\":{\"object\":" + object + "}}";
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String signature = HexFormat.of().formatHex(mac.doFinal((now + "." + body)
                .getBytes(StandardCharsets.UTF_8)));
            HttpResponse<String> response = CLIENT.send(HttpRequest.newBuilder(URI.create(target))
                .header("Content-Type", "application/json").header("Stripe-Signature", "t=" + now + ",v1=" + signature)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            System.out.println(type + " -> " + response.statusCode());
        } catch (Exception e) {
            System.out.println(type + " could not be sent: " + e);
        }
    }

    private static String sessionJson(Map<String, String> s) {
        String amount = s.get("line_items[0][price_data][unit_amount]");
        String pi = s.get("payment_intent");
        return "{\"id\":\"" + s.get("id") + "\",\"object\":\"checkout.session\",\"status\":\"" + s.get("status")
            + "\",\"payment_status\":\"" + s.get("payment_status") + "\",\"mode\":\"" + text(s.get("mode"))
            + "\",\"currency\":\"" + text(s.get("line_items[0][price_data][currency]")) + "\",\"amount_total\":"
            + amount + ",\"amount_subtotal\":" + amount + ",\"client_reference_id\":\"" + text(s.get("client_reference_id"))
            + "\",\"metadata\":{\"topUpId\":\"" + text(s.get("metadata[topUpId]")) + "\",\"sponsorId\":\""
            + text(s.get("metadata[sponsorId]")) + "\"},\"payment_intent\":" + (pi == null ? "null" : "\"" + pi + "\"")
            + ",\"livemode\":false,\"url\":\"http://localhost:" + port + "/pay/" + s.get("id") + "\",\"expires_at\":"
            + s.get("expires_at") + "}";
    }

    /** A value as JSON text content: quotes, backslashes and control characters dropped (the stub's own data). */
    private static String text(String value) {
        return value == null ? "" : value.replaceAll("[\"\\\\\\p{Cntrl}]", "");
    }

    private static Map<String, String> form(String body) {
        Map<String, String> form = new LinkedHashMap<>();
        for (String pair : body.split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0) {
                form.put(URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
            }
        }
        return form;
    }

    private static String random(int length) {
        String alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder id = new StringBuilder();
        for (int i = 0; i < length; i++) {
            id.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        }
        return id.toString();
    }

    private static void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().add("Location", location == null ? "/" : location);
        exchange.sendResponseHeaders(303, -1);
        exchange.close();
    }

    private static void answer(HttpExchange exchange, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", type);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
