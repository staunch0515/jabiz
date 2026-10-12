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
 * A stand-in for Stripe for local development of QuizBuks's top-ups (docs/quizbuks/plans/Q6-payments.md, D-Q6-1),
 * matching the dev profile (application-dev.yml): the API on http://localhost:8093, the checkout pages on the same
 * address, events signed with the development secret and posted to http://localhost:8080/api/inbound/stripe. Nothing
 * of Stripe is called. Run it with the JDK alone:
 *
 * <pre>java tools/quizbuks/stripe-stub/StripeStub.java [port] [webhook URL] [webhook secret]</pre>
 *
 * <ul>
 *   <li>{@code POST /v1/checkout/sessions} creates a checkout from the form QuizBuks sends (one per idempotency key) and
 *       answers it as Stripe would; its address is this stub's page {@code /pay/<id>};</li>
 *   <li>{@code GET /v1/checkout/sessions/<id>} answers the checkout (the stale top-ups' check);</li>
 *   <li>{@code GET /v1/payment_intents/<id>} answers the payment with its charge and balance transaction (a fee of
 *       3.6 %, rounded down);</li>
 *   <li>the page {@code /pay/<id>} has three buttons: pay (the checkout completes, {@code checkout.session.completed}
 *       is signed and posted, the browser goes to the success address), expire ({@code checkout.session.expired}) and
 *       refund ({@code charge.refunded} for the whole amount, after paying).</li>
 * </ul>
 * It listens on this machine only and keeps everything in memory. Never point anything but development at it.
 */
public final class StripeStub {

    private static final Map<String, Map<String, String>> SESSIONS = new ConcurrentHashMap<>();
    private static final Map<String, String> BY_KEY = new ConcurrentHashMap<>();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private static int port;
    private static String webhook;
    private static String secret;

    public static void main(String[] args) throws IOException {
        port = args.length > 0 ? Integer.parseInt(args[0]) : 8093;
        webhook = args.length > 1 ? args[1] : "http://localhost:8080/api/inbound/stripe";
        secret = args.length > 2 ? args[2] : "whsec_development_only_quizbuks_stripe_0000";
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/v1/checkout/sessions", StripeStub::sessions);
        server.createContext("/v1/payment_intents/", StripeStub::paymentIntent);
        server.createContext("/pay/", StripeStub::page);
        server.createContext("/", exchange -> answer(exchange, 404, "application/json",
            "{\"error\":{\"type\":\"invalid_request_error\",\"code\":\"resource_missing\"}}"));
        server.start();
        System.out.println("Stripe stub on http://localhost:" + port + ", events to " + webhook + " (Ctrl+C to stop)");
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

    /** Signs an event as Stripe does ({@code t=…,v1=HMAC-SHA256(t + "." + body)}) and posts it. */
    private static void send(String type, String object) {
        long now = Instant.now().getEpochSecond();
        String body = "{\"id\":\"evt_" + random(24) + "\",\"object\":\"event\",\"api_version\":\"2025-03-31.basil\","
            + "\"created\":" + now + ",\"livemode\":false,\"type\":\"" + type + "\",\"data\":{\"object\":"
            + object + "}}";
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String signature = HexFormat.of().formatHex(mac.doFinal((now + "." + body)
                .getBytes(StandardCharsets.UTF_8)));
            HttpResponse<String> response = CLIENT.send(HttpRequest.newBuilder(URI.create(webhook))
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
