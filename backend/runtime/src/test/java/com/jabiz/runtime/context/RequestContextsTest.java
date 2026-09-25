package com.jabiz.runtime.context;

import com.jabiz.context.RequestContext;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestContextsTest {

    @Test
    void missingContextIsAnError() {
        assertThatThrownBy(() -> RequestContexts.current().block())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("No RequestContext");
    }

    @Test
    void currentReturnsTheWrittenContextAndItsRequestId() {
        RequestContext ctx = RequestContext.system(Locale.ENGLISH, "job-7");

        assertThat(RequestContexts.current().contextWrite(view -> RequestContexts.put(view, ctx)).block())
            .isSameAs(ctx);
        assertThat(Mono.deferContextual(view -> Mono.just(view.<String>get(RequestContexts.REQUEST_ID_KEY)))
            .contextWrite(RequestContexts.put(Context.empty(), ctx)).block()).isEqualTo("job-7");
    }
}
