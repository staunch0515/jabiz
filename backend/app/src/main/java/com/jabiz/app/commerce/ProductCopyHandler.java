package com.jabiz.app.commerce;

import com.jabiz.external.ExternalCallException;
import com.jabiz.external.ExternalHttp;
import com.jabiz.external.ExternalJobContext;
import com.jabiz.external.ExternalJobException;
import com.jabiz.external.ExternalJobHandler;
import com.jabiz.external.ExternalJobResult;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Writes a product's marketing copy with the demo AI service (decision D38, the long call of an external job):
 * {@code POST /v1/generate} of {@value ModerateText#SERVICE} with {@code {"task": "product-copy", "language", "sku",
 * "productName"}}, answered {@code {"text": …, "usage": {"promptTokens": n, "completionTokens": n}}} (no particular
 * provider's format). The job's idempotency key goes with every attempt, so a service that deduplicates writes once;
 * the tokens it reports are recorded as usage, a failed attempt's too where it reported them.
 *
 * <p>A refusal (a 4xx other than 429) does not pass by trying again: a permanent failure. A busy or failing service
 * (429, 5xx), an answer that cannot be read, or no answer in time are tried again after the type's backoff.
 */
@Component
public class ProductCopyHandler
    implements ExternalJobHandler<ProductCopy.ProductCopyInput, ProductCopy.ProductCopyResult> {

    private final JsonMapper json;

    public ProductCopyHandler(JsonMapper json) {
        this.json = json;
    }

    @Override
    public ExternalJobResult<ProductCopy.ProductCopyResult> run(ProductCopy.ProductCopyInput input,
        ExternalJobContext ctx) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("task", "product-copy");
        request.put("language", input.language());
        request.put("sku", input.sku());
        request.put("productName", input.productName());
        ExternalHttp.Response response = ctx.http(ModerateText.SERVICE).send(ExternalHttp.Request.post("/v1/generate")
            .header("Authorization", "Bearer " + ctx.secret(ModerateText.SERVICE, "apiKey"))
            .json(json.writeValueAsString(request))
            .idempotencyKey(ctx.idempotencyKey()));
        JsonNode answer = read(response);
        JsonNode usage = answer == null ? null : answer.path("usage");
        if (usage != null) {
            usage.properties().forEach(entry -> {
                // What the service reports is not trusted to be a metric name or a count.
                if (ExternalJobResult.USAGE_NAME.matcher(entry.getKey()).matches()
                    && entry.getValue().isIntegralNumber() && entry.getValue().canConvertToLong()
                    && entry.getValue().asLong() >= 0) {
                    ctx.recordUsage(entry.getKey(), entry.getValue().asLong());
                }
            });
        }
        if (response.status() == 429 || response.status() >= 500) {
            throw ExternalJobException.retryable("PROVIDER_UNAVAILABLE", "The AI service answered " + response.status());
        }
        if (!response.isSuccess()) {
            throw ExternalJobException.permanent("GENERATION_REFUSED", "The AI service refused the request ("
                + response.status() + ")");
        }
        if (answer == null || !answer.path("text").isString() || answer.path("text").asString().isBlank()) {
            throw new ExternalCallException(ModerateText.SERVICE, ExternalCallException.Kind.BAD_RESPONSE,
                "answer has no text");
        }
        return ExternalJobResult.of(new ProductCopy.ProductCopyResult(answer.path("text").asString()));
    }

    /** The answer as JSON; null when it is not. */
    private JsonNode read(ExternalHttp.Response response) {
        try {
            JsonNode node = json.readTree(response.text());
            return node != null && node.isObject() ? node : null;
        } catch (JacksonException e) {
            return null;
        }
    }
}
