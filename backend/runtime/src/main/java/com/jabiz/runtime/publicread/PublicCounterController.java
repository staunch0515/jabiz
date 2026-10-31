package com.jabiz.runtime.publicread;

import com.jabiz.dataset.PublicCounter;
import com.jabiz.resource.ResourceNotFoundException;
import com.jabiz.runtime.web.ClientAddresses;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * {@code POST /api/public/counters/{counter}/{key}} (docs/design/15-public-access.md section 10; decision D65): counts
 * one visit of an anonymous visitor, without a body, whatever credentials the request carries. An unknown counter is
 * 404; anything else is 204, counted or not, so the answer tells nothing about keys or visitors. The switch and the
 * rate limit are {@link PublicAccessWebFilter}'s, which lets {@code POST} through for this path only.
 */
@RestController
@RequestMapping("/api/public/counters")
class PublicCounterController {

    private final PublicCounters counters;
    private final PublicProperties properties;
    private final ClientAddresses clients;

    PublicCounterController(PublicCounters counters, PublicProperties properties, ClientAddresses clients) {
        this.counters = counters;
        this.properties = properties;
        this.clients = clients;
    }

    @PostMapping("/{counter}/{key}")
    Mono<ResponseEntity<Void>> count(@PathVariable String counter, @PathVariable String key,
        ServerWebExchange exchange) {
        if (!properties.on()) {
            // The filter answers first; this only guards against a path it did not recognise.
            throw new ResourceNotFoundException("Not found");
        }
        PublicCounter declared = counters.find(counter)
            .orElseThrow(() -> new ResourceNotFoundException("No public counter " + counter));
        String agent = exchange.getRequest().getHeaders().getFirst(HttpHeaders.USER_AGENT);
        String visitor = clients.clientOf(exchange.getRequest()) + '\n' + (agent == null ? "" : agent);
        return counters.count(declared, key, visitor)
            .thenReturn(ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").<Void>build());
    }
}
