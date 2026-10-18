package com.jabiz.app.commerce;

import com.jabiz.external.ExternalCallException;
import com.jabiz.external.ExternalHttp;
import com.jabiz.external.ExternalJobContext;
import com.jabiz.external.ExternalJobException;
import com.jabiz.external.ExternalJobFile;
import com.jabiz.external.ExternalJobHandler;
import com.jabiz.external.ExternalJobResult;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Redoes a product's photo with the demo AI service (decision D50, an external job with files): reads the photo its
 * job was given ({@link ExternalJobContext#files()}; the platform checked that the requester may read it and copied it
 * for the attempt), sends it to {@code POST /v1/images} of {@value ProductImage#SERVICE} as
 * {@code {"task": "product-image", "style", "sku", "contentType", "image": <base64>}}, and returns the image of the
 * answer {@code {"image": <base64>, "usage": {…}}} as the job's file {@value ProductImage#IMAGE}. It does not check
 * what the service sent: the platform recognises the file's type from its content and stores it only as the output
 * policy allows, re-encoded.
 *
 * <p>Failures as in {@link ProductCopyHandler}: a refusal (4xx other than 429) is permanent; a busy or failing service,
 * an answer without an image or no answer in time are tried again.
 */
@Component
public class ProductImageHandler
    implements ExternalJobHandler<ProductImage.ProductImageInput, ProductImage.ProductImageResult> {

    private final JsonMapper json;

    public ProductImageHandler(JsonMapper json) {
        this.json = json;
    }

    @Override
    public ExternalJobResult<ProductImage.ProductImageResult> run(ProductImage.ProductImageInput input,
        ExternalJobContext ctx) {
        ExternalJobFile photo = ctx.files().getFirst();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("task", "product-image");
        request.put("style", input.style());
        request.put("sku", input.sku());
        request.put("contentType", photo.contentType());
        request.put("image", Base64.getEncoder().encodeToString(photo.bytes()));
        ExternalHttp.Response response = ctx.http(ProductImage.SERVICE).send(ExternalHttp.Request.post("/v1/images")
            .header("Authorization", "Bearer " + ctx.secret(ProductImage.SERVICE, "apiKey"))
            .json(json.writeValueAsString(request))
            .idempotencyKey(ctx.idempotencyKey()));
        JsonNode answer = read(response);
        if (answer != null) {
            answer.path("usage").properties().forEach(entry -> {
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
        byte[] image = answer == null || !answer.path("image").isString() ? null : decode(answer.path("image")
            .asString());
        if (image == null || image.length == 0) {
            throw new ExternalCallException(ProductImage.SERVICE, ExternalCallException.Kind.BAD_RESPONSE,
                "answer has no image");
        }
        return ExternalJobResult.of(new ProductImage.ProductImageResult(input.style(), photo.fileId()))
            .file(ProductImage.IMAGE, "product-" + input.sku() + "-" + input.style(), image);
    }

    private static byte[] decode(String base64) {
        try {
            return Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            return null;
        }
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
