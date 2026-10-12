package com.jabiz.quizbuks.wallet;

import com.jabiz.external.ExternalHttp;
import com.jabiz.external.ExternalJobContext;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The few calls of Stripe's API the top-up jobs make, through the platform's client of the external service
 * {@value StripeService#SERVICE} (no SDK, docs/quizbuks/plans/Q6-payments.md, D-Q6-1): form-encoded requests
 * ({@link StripeForm}), the key in the {@code Authorization} header only (the platform never prints header values),
 * the pinned {@code Stripe-Version}, and JSON answers read as maps with every number a {@code BigDecimal}. An answer
 * other than 2xx fails the attempt as {@link StripeErrors} classifies it.
 */
final class StripeApi {

    private final ExternalHttp http;
    private final String apiKey;
    private final String apiVersion;
    private final JsonMapper json;

    private StripeApi(ExternalHttp http, String apiKey, String apiVersion, JsonMapper json) {
        this.http = http;
        this.apiKey = apiKey;
        this.apiVersion = apiVersion;
        this.json = json;
    }

    static StripeApi of(ExternalJobContext ctx, StripeSettings settings, JsonMapper json) {
        return new StripeApi(ctx.http(StripeService.SERVICE), ctx.secret(StripeService.SERVICE, "apiKey"),
            settings.apiVersion(), json);
    }

    /** {@code POST} of a form with an idempotency key Stripe keeps at least a day. */
    Map<String, Object> post(String path, StripeForm form, String idempotencyKey) {
        return answer(http.send(headers(ExternalHttp.Request.post(path))
            .body("application/x-www-form-urlencoded", form.encode().getBytes(StandardCharsets.UTF_8))
            .idempotencyKey(idempotencyKey)));
    }

    /** {@code GET} with an optional query. */
    Map<String, Object> get(String path, StripeForm query) {
        String target = query.isEmpty() ? path : path + "?" + query.encode();
        return answer(http.send(headers(ExternalHttp.Request.get(target))));
    }

    private ExternalHttp.Request headers(ExternalHttp.Request request) {
        return request.header("Authorization", "Bearer " + apiKey).header("Stripe-Version", apiVersion);
    }

    private Map<String, Object> answer(ExternalHttp.Response response) {
        Map<String, Object> body = parse(json, response.text());
        if (!response.isSuccess()) {
            Object error = body == null ? null : body.get("error");
            String code = error instanceof Map<?, ?> e && e.get("code") instanceof String c ? c : null;
            throw StripeErrors.failure(response.status(), code);
        }
        if (body == null) {
            throw StripeErrors.badResponse("is not a JSON object");
        }
        return body;
    }

    /** A JSON object as maps, lists and plain values, every number a BigDecimal; null when it is not one. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> parse(JsonMapper json, String text) {
        try {
            JsonNode root = json.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(text);
            return root != null && root.isObject() ? (Map<String, Object>) value(root) : null;
        } catch (JacksonException e) {
            return null;
        }
    }

    private static Object value(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            node.properties().forEach(entry -> map.put(entry.getKey(), value(entry.getValue())));
            return Collections.unmodifiableMap(map);
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            node.forEach(child -> list.add(value(child)));
            return Collections.unmodifiableList(list);
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        return node.asString();
    }
}
