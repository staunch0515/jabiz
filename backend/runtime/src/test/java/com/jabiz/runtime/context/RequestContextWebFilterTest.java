package com.jabiz.runtime.context;

import com.jabiz.context.RequestContext;
import com.jabiz.i18n.MessageCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Locale;
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

    private MockServerWebExchange run(MockServerHttpRequest.BaseBuilder<?> request) {
        seen.set(null);
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        new RequestContextWebFilter(MESSAGES).filter(exchange, chain).block();
        return exchange;
    }

    @Test
    void validRequestIdIsKeptAndEchoed() {
        MockServerWebExchange exchange = run(MockServerHttpRequest.get("/api/x").header("X-Request-Id", "abc-123_x.y"));

        assertThat(seen.get().requestId()).isEqualTo("abc-123_x.y");
        assertThat(seenRequestId.get()).isEqualTo("abc-123_x.y");
        assertThat(exchange.getResponse().getHeaders().getFirst("X-Request-Id")).isEqualTo("abc-123_x.y");
        assertThat(RequestContextWebFilter.of(exchange)).isSameAs(seen.get());
    }

    @Test
    void invalidOrMissingRequestIdIsReplaced() {
        run(MockServerHttpRequest.get("/").header("X-Request-Id", "bad id forged"));
        assertThat(seen.get().requestId()).matches("[0-9a-f-]{36}");

        run(MockServerHttpRequest.get("/").header("X-Request-Id", "x".repeat(65)));
        assertThat(seen.get().requestId()).matches("[0-9a-f-]{36}");

        run(MockServerHttpRequest.get("/"));
        assertThat(seen.get().requestId()).matches("[0-9a-f-]{36}");
    }

    @Test
    void acceptLanguagePicksTheBestSupportedLanguage() {
        run(MockServerHttpRequest.get("/").header("Accept-Language", "ja-JP,en;q=0.5"));
        assertThat(seen.get().locale()).isEqualTo(Locale.JAPANESE);

        run(MockServerHttpRequest.get("/").header("Accept-Language", "de,zh-CN;q=0.8"));
        assertThat(seen.get().locale()).isEqualTo(Locale.CHINESE);

        run(MockServerHttpRequest.get("/").header("Accept-Language", "fr"));
        assertThat(seen.get().locale()).isEqualTo(Locale.ENGLISH);

        run(MockServerHttpRequest.get("/").header("Accept-Language", ";;;q=x"));
        assertThat(seen.get().locale()).isEqualTo(Locale.ENGLISH);

        run(MockServerHttpRequest.get("/"));
        assertThat(seen.get().locale()).isEqualTo(Locale.ENGLISH);
    }

    /** Authentication happens later in the chain (AuthenticatedRequestContextWebFilter); headers never name the actor. */
    @Test
    void theContextStartsAnonymousWhateverTheHeadersSay() {
        run(MockServerHttpRequest.get("/").header("X-Jabiz-Actor", "admin").header("X-Jabiz-Permissions", "*"));

        assertThat(seen.get().actorId()).isEqualTo(Actor.ANONYMOUS_ID);
        assertThat(seen.get().permissions()).isEmpty();
    }
}
