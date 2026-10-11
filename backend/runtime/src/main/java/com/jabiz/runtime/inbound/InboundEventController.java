package com.jabiz.runtime.inbound;

import com.jabiz.query.BoundValue;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.security.Permissions;
import com.jabiz.runtime.storage.Rows;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * The body of one received inbound webhook event, exactly as it arrived ({@value #PATH}; decision D37 item 4):
 * {@code inbound.read}, one event at a time, never cached. The {@code InboundEvent} dataset lists and reads the events
 * without their bodies, which may be up to 1 MB of personal data each.
 */
@RestController
class InboundEventController {

    static final String PATH = "/api/inbound-events/{inboundEventId}/body";

    /**
     * @param body       the body as received (it was strict UTF-8)
     * @param bodySha256 the SHA-256 of its bytes, as stored when it arrived
     */
    record InboundEventBody(String inboundEventId, String contentType, String bodySha256, String body) {
        @Override
        public String toString() {
            return "InboundEventBody[inboundEventId=" + inboundEventId + ", body=***]";
        }
    }

    private final StorageAdapterRegistry storages;
    private final String poolRef;

    InboundEventController(StorageAdapterRegistry storages,
        @Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.storages = storages;
        this.poolRef = poolRef;
    }

    @GetMapping(PATH)
    Mono<ResponseEntity<InboundEventBody>> body(@PathVariable String inboundEventId) {
        return RequestContexts.current().flatMap(context -> {
            Permissions.require(context, InboundPermissions.READ, "Reading inbound events");
            return storages.getEngine(poolRef).select("SELECT e.inbound_event_id, e.content_type, e.body_sha256, b.body"
                    + " FROM " + InboundEntities.TABLE + " e JOIN " + InboundEntities.BODY_TABLE
                    + " b ON b.inbound_event_id = e.inbound_event_id WHERE e.inbound_event_id = :id",
                    Map.of("id", BoundValue.of(inboundEventId)))
                .next()
                .switchIfEmpty(Mono.error(() -> new EntityNotFoundException("Inbound event not found")))
                .map(row -> ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(new InboundEventBody(Rows.string(row.get("inbound_event_id")),
                        Rows.string(row.get("content_type")), Rows.string(row.get("body_sha256")),
                        Rows.string(row.get("body")))));
        });
    }
}
