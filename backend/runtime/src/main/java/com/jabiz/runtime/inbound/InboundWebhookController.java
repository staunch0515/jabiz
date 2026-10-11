package com.jabiz.runtime.inbound;

import com.jabiz.inbound.InboundHeaders;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@value #PATH} (decision D37 item 2): anonymous, the signature is the credential. {@link InboundWebhookWebFilter} has
 * let only a served provider's {@code POST} within its rate limit through. The body is read as raw bytes, never
 * decoded by a codec: a declared {@code Content-Length} above the provider's limit is refused before any of it is read,
 * and reading stops as soon as a chunked body passes the limit. Rejected requests are answered 400
 * {@code INBOUND_REJECTED}; accepted, repeated and ignored ones 200 {@code {"received":true}}, after the commit.
 */
@RestController
class InboundWebhookController {

    static final String PATH = "/api/inbound/{provider}";

    /** The answer to every request taken: the provider stops sending it. */
    record InboundReceipt(boolean received) {}

    private final InboundReceiver receiver;

    InboundWebhookController(InboundReceiver receiver) {
        this.receiver = receiver;
    }

    @PostMapping(PATH)
    Mono<ResponseEntity<InboundReceipt>> receive(@PathVariable String provider, ServerHttpRequest request) {
        return Mono.defer(() -> {
            long max = receiver.maxBodySize(provider);
            HttpHeaders headers = request.getHeaders();
            Mono<byte[]> body = headers.getContentLength() > max
                ? Mono.error(new InboundRejectedException("too_large"))
                : DataBufferUtils.join(request.getBody(), (int) max)
                    .map(InboundWebhookController::bytes)
                    .onErrorMap(DataBufferLimitException.class, tooLarge -> new InboundRejectedException("too_large"));
            Map<String, List<String>> all = new LinkedHashMap<>();
            headers.forEach((name, values) -> all.merge(name, values, (a, b) -> {
                List<String> both = new ArrayList<>(a);
                both.addAll(b);
                return both;
            }));
            return receiver.receive(provider, InboundHeaders.of(all), headers.getFirst(HttpHeaders.CONTENT_TYPE), body)
                .thenReturn(ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                    .body(new InboundReceipt(true)));
        });
    }

    private static byte[] bytes(DataBuffer buffer) {
        try {
            byte[] bytes = new byte[buffer.readableByteCount()];
            buffer.read(bytes);
            return bytes;
        } finally {
            DataBufferUtils.release(buffer);
        }
    }
}
