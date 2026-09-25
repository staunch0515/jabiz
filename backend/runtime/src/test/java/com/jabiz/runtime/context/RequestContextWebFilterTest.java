package com.jabiz.runtime.context;

import com.jabiz.context.RequestContext;
import com.jabiz.i18n.MessageCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RequestContextWebFilterTest {

    private static final MessageCatalog MESSAGES = new MessageCatalog(List.of(MessageCatalog.PLATFORM_BUNDLE),
        List.of(Locale.CHINESE, Locale.JAPANESE, Locale.ENGLISH), Locale.ENGLISH,
        RequestContextWebFilterTest.class.getClassLoader());

    private final AtomicReference<RequestContext> seen = new AtomicReference<>();
    private final AtomicReference<String> seenRequestId = new AtomicReference<>();

    /** Records what the rest of the chain finds in the Reactor context. */
    private final WebFilterChain chain = exchange -> Mono.deferContextual(view -> {
        seen.set(view.get(RequestContext.class));
        seenRequestId.set(view.get(RequestContexts.REQUEST_ID_KEY));
        return Mono.empty();
    });

    private MockServerWebExchange run(ActorResolver actors, MockServerHttpRequest.BaseBuilder<?> request) {
        seen.set(null);
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        new RequestContextWebFilter(MESSAGES, actors).filter(exchange, chain).block();
        return exchange;
    }

    @Test
    void validRequestIdIsKeptAndEchoed() {
        MockServerWebExchange exchange = run(r -> Actor.ANONYMOUS,
            MockServerHttpRequest.get("/api/x").header("X-Request-Id", "abc-123_x.y"));

        assertThat(seen.get().requestId()).isEqualTo("abc-123_x.y");
        assertThat(seenRequestId.get()).isEqualTo("abc-123_x.y");
        assertThat(exchange.getResponse().getHeaders().getFirst("X-Request-Id")).isEqualTo("abc-123_x.y");
        assertThat(RequestContextWebFilter.of(exchange)).isSameAs(seen.get());
    }

    @Test
    void invalidOrMissingRequestIdIsReplaced() {
        run(r -> Actor.ANONYMOUS, MockServerHttpRequest.get("/").header("X-Request-Id", "bad id forged"));
        assertThat(seen.get().requestId()).matches("[0-9a-f-]{36}");

        run(r -> Actor.ANONYMOUS, MockServerHttpRequest.get("/").header("X-Request-Id", "x".repeat(65)));
        assertThat(seen.get().requestId()).matches("[0-9a-f-]{36}");

        run(r -> Actor.ANONYMOUS, MockServerHttpRequest.get("/"));
        assertThat(seen.get().requestId()).matches("[0-9a-f-]{36}");
    }

    @Test
    void acceptLanguagePicksTheBestSupportedLanguage() {
        run(r -> Actor.ANONYMOUS, MockServerHttpRequest.get("/").header("Accept-Language", "ja-JP,en;q=0.5"));
        assertThat(seen.get().locale()).isEqualTo(Locale.JAPANESE);

        run(r -> Actor.ANONYMOUS, MockServerHttpRequest.get("/").header("Accept-Language", "de,zh-CN;q=0.8"));
        assertThat(seen.get().locale()).isEqualTo(Locale.CHINESE);

        run(r -> Actor.ANONYMOUS, MockServerHttpRequest.get("/").header("Accept-Language", "fr"));
        assertThat(seen.get().locale()).isEqualTo(Locale.ENGLISH);

        run(r -> Actor.ANONYMOUS, MockServerHttpRequest.get("/").header("Accept-Language", ";;;q=x"));
        assertThat(seen.get().locale()).isEqualTo(Locale.ENGLISH);

        run(r -> Actor.ANONYMOUS, MockServerHttpRequest.get("/"));
        assertThat(seen.get().locale()).isEqualTo(Locale.ENGLISH);
    }

    @Test
    void anonymousByDefaultEvenWithDevHeaders() {
        run(request -> Actor.ANONYMOUS, MockServerHttpRequest.get("/").header("X-Jabiz-Actor", "admin")
            .header("X-Jabiz-Permissions", "*"));

        assertThat(seen.get().actorId()).isEqualTo(Actor.ANONYMOUS_ID);
        assertThat(seen.get().permissions()).isEmpty();
    }

    @Test
    void devResolverTakesTheActorFromHeaders() {
        run(new DevHeaderActorResolver(), MockServerHttpRequest.get("/")
            .header("X-Jabiz-Actor", "alice")
            .header("X-Jabiz-Tenant", "t1")
            .header("X-Jabiz-Roles", "clerk, auditor")
            .header("X-Jabiz-Permissions", "order.read,order.write"));

        assertThat(seen.get().actorId()).isEqualTo("alice");
        assertThat(seen.get().tenantId()).isEqualTo("t1");
        assertThat(seen.get().roles()).isEqualTo(Set.of("clerk", "auditor"));
        assertThat(seen.get().permissions()).isEqualTo(Set.of("order.read", "order.write"));
    }

    @Test
    void devResolverWithoutActorHeaderIsAnonymous() {
        run(new DevHeaderActorResolver(), MockServerHttpRequest.get("/").header("X-Jabiz-Permissions", "*"));
        assertThat(seen.get().actorId()).isEqualTo(Actor.ANONYMOUS_ID);
        assertThat(seen.get().permissions()).isEmpty();
    }

    @Test
    void malformedDevHeaderIsABadRequest() {
        MockServerWebExchange exchange = run(new DevHeaderActorResolver(),
            MockServerHttpRequest.get("/").header("X-Jabiz-Actor", "evil actor"));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(seen.get()).isNull();
    }
}
