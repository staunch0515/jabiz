package com.jabiz.runtime.context;

import com.jabiz.context.RequestContext;
import com.jabiz.i18n.MessageCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Builds the {@link RequestContext} of every request (docs/design/01-core-vs-runtime.md section 5) and
 * puts it into the Reactor context of the rest of the chain, and into the exchange attributes for code
 * that only sees the exchange (exception handlers). Runs first so that every later log line of the
 * request carries its id.
 *
 * <p>The context starts out anonymous; once Spring Security has authenticated the request,
 * {@link AuthenticatedRequestContextWebFilter} replaces the actor (docs/design/10-security.md section 5).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestContextWebFilter implements WebFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    /** Exchange attribute holding the request's {@link RequestContext}. */
    public static final String ATTRIBUTE = RequestContext.class.getName();

    private static final Logger log = LoggerFactory.getLogger(RequestContextWebFilter.class);

    /** Accepted client-supplied ids; anything else could inject text into logs and is replaced. */
    private static final Pattern VALID_REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private final MessageCatalog messages;

    public RequestContextWebFilter(MessageCatalog messages) {
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
    }

    /** The context the filter stored for this exchange, if it ran. */
    public static RequestContext of(ServerWebExchange exchange) {
        return exchange.getAttribute(ATTRIBUTE);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String requestId = requestId(request);
        exchange.getResponse().getHeaders().set(REQUEST_ID_HEADER, requestId);

        Actor actor = Actor.ANONYMOUS;
        RequestContext context = new RequestContext(actor.actorId(), actor.tenantId(), locale(request), requestId,
            actor.roles(), actor.permissions());
        exchange.getAttributes().put(ATTRIBUTE, context);

        long started = System.nanoTime();
        return chain.filter(exchange)
            .doFinally(signal -> logCompletion(exchange, requestId, started))
            .contextWrite(view -> RequestContexts.put(view, context));
    }

    private static String requestId(ServerHttpRequest request) {
        String supplied = request.getHeaders().getFirst(REQUEST_ID_HEADER);
        if (supplied != null && VALID_REQUEST_ID.matcher(supplied).matches()) {
            return supplied;
        }
        return randomId();
    }

    /**
     * A random (version 4 layout) UUID from {@link ThreadLocalRandom}. {@link UUID#randomUUID()} reads
     * SecureRandom, which may block the event loop; a correlation id needs uniqueness, not secrecy.
     */
    private static String randomId() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        long high = (random.nextLong() & ~0xF000L) | 0x4000L;
        long low = (random.nextLong() & ~(0xC000L << 48)) | (0x8000L << 48);
        return new UUID(high, low).toString();
    }

    /** The best supported match for {@code Accept-Language}, otherwise the configured default. */
    private Locale locale(ServerHttpRequest request) {
        try {
            List<Locale.LanguageRange> ranges = request.getHeaders().getAcceptLanguage();
            Locale match = ranges.isEmpty() ? null : Locale.lookup(ranges, messages.supportedLocales());
            return messages.supported(match);
        } catch (IllegalArgumentException malformedHeader) {
            return messages.defaultLocale();
        }
    }

    private static void logCompletion(ServerWebExchange exchange, String requestId, long started) {
        // doFinally may run outside the propagated context, so the id is set explicitly for this line.
        try (MDC.MDCCloseable ignored = MDC.putCloseable(RequestContexts.REQUEST_ID_KEY, requestId)) {
            log.info("{} {} -> {} ({} ms)", exchange.getRequest().getMethod(), exchange.getRequest().getPath(),
                exchange.getResponse().getStatusCode(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        }
    }
}
