package com.jabiz.runtime.web;

import com.jabiz.runtime.test.PipelinedRequests;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.HttpHeaderNames;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.NettyPipeline;
import reactor.netty.http.server.HttpServer;

import java.io.IOException;
import java.time.Duration;
import java.util.function.UnaryOperator;

import static com.jabiz.runtime.test.PipelinedRequests.get;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PipelinedReadResumer} against Reactor Netty's own server, spoken to over a plain socket: a request buffered as
 * pipelined and answered at once (as a 304 or a guard's 404 is) must not leave the connection unread.
 */
class PipelinedReadResumerTest {

    private static final int CONNECTIONS = 20;

    @Test
    void theNextRequestOnTheConnectionIsAnswered() throws IOException {
        DisposableServer server = start(new PipelinedReadResumer()::apply);
        try {
            for (int i = 0; i < CONNECTIONS; i++) {
                assertThat(thirdAnswered(server)).as("connection %d", i).isTrue();
            }
        }
        finally {
            server.disposeNow();
        }
    }

    /**
     * Reactor Netty by itself stops reading such a connection. When this fails, Reactor Netty resumes reading by itself
     * and {@link PipelinedReadResumer} can go (with this test).
     */
    @Test
    void withoutItReactorNettyStopsReading() throws IOException {
        DisposableServer server = start(UnaryOperator.identity());
        try {
            boolean stalled = false;
            for (int i = 0; i < CONNECTIONS && !stalled; i++) {
                stalled = !thirdAnswered(server);
            }
            assertThat(stalled).isTrue();
        }
        finally {
            server.disposeNow();
        }
    }

    @Test
    void addsItselfToHttp11PipelinesOnce() {
        var channel = new EmbeddedChannel();
        PipelinedReadResumer.install(channel.pipeline());
        assertThat(channel.pipeline().get(PipelinedReadResumer.NAME)).as("no HTTP/1.1 traffic handler").isNull();

        channel.pipeline().addLast(NettyPipeline.HttpTrafficHandler, new ChannelInboundHandlerAdapter());
        PipelinedReadResumer.install(channel.pipeline());
        PipelinedReadResumer.install(channel.pipeline());
        assertThat(channel.pipeline().names())
            .containsSubsequence(NettyPipeline.HttpTrafficHandler, PipelinedReadResumer.NAME);
        assertThat(channel.pipeline().names().stream().filter(PipelinedReadResumer.NAME::equals)).hasSize(1);
        channel.finishAndReleaseAll();
    }

    /** {@code /slow} answers a little later, so that a request behind it is buffered; anything else is 404 at once. */
    private static DisposableServer start(UnaryOperator<HttpServer> customizer) {
        return customizer.apply(HttpServer.create().host("127.0.0.1").port(0))
            .handle((request, response) -> request.uri().equals("/slow")
                ? response.header(HttpHeaderNames.CONTENT_LENGTH, "4")
                    .sendString(Mono.just("slow").delayElement(Duration.ofMillis(20)))
                : response.status(404).header(HttpHeaderNames.CONTENT_LENGTH, "0").send())
            .bindNow();
    }

    private static boolean thirdAnswered(DisposableServer server) throws IOException {
        return PipelinedRequests.thirdAnswered(server.port(), get("/slow"), get("/missing"), get("/missing"), 1000);
    }
}
