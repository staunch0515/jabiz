package com.jabiz.app.commerce;

import com.jabiz.entity.Violation;
import com.jabiz.external.ExternalCallException;
import com.jabiz.external.ExternalHttp;
import com.jabiz.external.ExternalService;
import com.jabiz.external.ExternalServices;
import com.jabiz.process.BlockingStep;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.StepSpec;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Checks a text with the demo moderation service before it is saved (decision D38, the short call of a blocking step):
 * {@code POST /v1/moderate} of {@value #SERVICE} with {@code {"text": …}}, answered {@code {"flagged": bool,
 * "categories": […]}}. A flagged text is a violation of its field, collected with the process's other ones. The call
 * reads and changes nothing, so it is made in dry runs (an import's preview) too, and a rollback has nothing to undo.
 */
@Component
public class ModerateText implements BlockingStep<ModerateText.Metadata, ProcessContext> {

    public static final String SERVICE = "demo-ai";
    public static final String INAPPROPRIATE = "COMMERCE_TEXT_INAPPROPRIATE";
    /** The moderation holds its process's transaction while it waits: shorter than what the service allows. */
    static final Duration MODERATION_TIMEOUT = Duration.ofSeconds(10);

    /**
     * The demo AI service. Its address here is an example: each environment sets its own
     * ({@code jabiz.external-services.demo-ai.*}); development and the tests point at a local stub. Its request timeout
     * is the generation's of the external job {@link ProductCopyHandler}; the moderation asks for less.
     */
    public static final ExternalService DEMO_AI = ExternalService.define(SERVICE, s -> s
        .baseUrl("https://ai.example.com/v1").allowedHosts("ai.example.com")
        .secret("apiKey", "JABIZ_DEMO_AI_KEY")
        .connectTimeout(Duration.ofSeconds(5)).requestTimeout(Duration.ofSeconds(60))
        // Large enough for the photos of commerce.product-image (10 MB, base64 in JSON; decision D50).
        .idempotencyHeader("Idempotency-Key").maxResponseSize(ExternalService.MAX_RESPONSE_SIZE)
        .allowInDryRun());

    /**
     * @param textKey context key of the text; nothing is checked when it is absent or blank
     * @param field   the field a flagged text is reported against
     */
    public record Metadata(String textKey, String field) {
        public Metadata {
            Objects.requireNonNull(textKey, "textKey must not be null");
            Objects.requireNonNull(field, "field must not be null");
        }
    }

    public static StepSpec<Metadata, ProcessContext> of(String textKey, String field) {
        return StepSpec.of(ModerateText.class, new Metadata(textKey, field));
    }

    private final ExternalServices services;
    private final JsonMapper json;

    public ModerateText(ExternalServices services, JsonMapper json) {
        this.services = services;
        this.json = json;
    }

    @Override
    public void run(Metadata metadata, ProcessContext ctx) {
        String text = ctx.get(metadata.textKey(), String.class);
        if (text == null || text.isBlank()) {
            return;
        }
        ExternalHttp.Response response = services.http(SERVICE).send(ExternalHttp.Request.post("/v1/moderate")
            .header("Authorization", "Bearer " + services.secret(SERVICE, "apiKey"))
            .json(json.writeValueAsString(Map.of("text", text)))
            .timeout(MODERATION_TIMEOUT)
            .idempotencyKey(services.idempotencyKey("moderate")));
        if (!response.isSuccess()) {
            throw ExternalCallException.badResponse(SERVICE, response.status());
        }
        JsonNode verdict;
        try {
            verdict = json.readTree(response.text());
        } catch (JacksonException e) {
            throw new ExternalCallException(SERVICE, ExternalCallException.Kind.BAD_RESPONSE, "answer is not JSON");
        }
        if (verdict.path("flagged").asBoolean(false)) {
            List<String> categories = new ArrayList<>();
            verdict.path("categories").forEach(category -> categories.add(category.asString()));
            ctx.reject(new Violation(metadata.field(), INAPPROPRIATE, "The moderation service flagged the text",
                Map.of("categories", String.join(", ", categories))));
        }
    }
}
