package com.jabiz.runtime.web;

import com.jabiz.runtime.test.PipelinedRequests;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.DisposableServer;
import reactor.netty.NettyInbound;
import reactor.netty.NettyOutbound;
import reactor.netty.NettyPipeline;
import reactor.netty.channel.ChannelOperations;
import reactor.netty.http.server.HttpServer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
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

    /**
     * The buffered request is a {@code POST} whose body is still on its way: answered at once, it leaves only part of
     * its body in the buffer, and the rest and the next request must still be read.
     */
    @Test
    void theNextRequestIsAnsweredAfterARequestWithPartOfItsBody() throws IOException {
        DisposableServer server = start(new PipelinedReadResumer()::apply);
        try {
            for (int i = 0; i < CONNECTIONS; i++) {
                assertThat(answeredAfterPartialBody(server)).as("connection %d", i).isTrue();
            }
        }
        finally {
            server.disposeNow();
        }
    }

    @Test
    void withoutItReactorNettyStopsReadingAfterARequestWithPartOfItsBody() throws IOException {
        DisposableServer server = start(UnaryOperator.identity());
        try {
            boolean stalled = false;
            for (int i = 0; i < CONNECTIONS && !stalled; i++) {
                stalled = !answeredAfterPartialBody(server);
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

    /**
     * What makes {@link #theNextRequestIsAnsweredAfterARequestWithPartOfItsBody} fail now and then (often under load):
     * the drained part of a body reaches its exchange after the answer was written but before the exchange ended, so
     * the exchange is still bound when the content goes past. The read must then follow once the exchange ends.
     */
    @Test
    void contentThatReachesAnExchangeBeforeItEndsIsFollowedByAReadWhenItEnds() {
        var reads = new AtomicInteger();
        EmbeddedChannel channel = channelCountingReads(reads);
        var exchange = new EndableExchange(channel);

        channel.pipeline().fireChannelRead(content("12345"));
        channel.pipeline().fireChannelRead(content("678"));
        channel.runPendingTasks();
        assertThat(reads).as("while the exchange is bound").hasValue(0);

        exchange.end();
        channel.runPendingTasks();
        assertThat(reads).as("once it has ended: one read, however many chunks went past").hasValue(1);
        channel.finishAndReleaseAll();
    }

    @Test
    void noReadWhenTheNextExchangeHasStartedByTheTimeTheWatchedOneEnds() {
        var reads = new AtomicInteger();
        EmbeddedChannel channel = channelCountingReads(reads);
        var exchange = new EndableExchange(channel);
        channel.pipeline().fireChannelRead(content("12345"));

        exchange.end();
        new EndableExchange(channel);
        channel.runPendingTasks();

        assertThat(reads).hasValue(0);
        channel.finishAndReleaseAll();
    }

    /** An HTTP/1.1-like pipeline with the handler, not reading by itself, counting the reads asked for. */
    private static EmbeddedChannel channelCountingReads(AtomicInteger reads) {
        var channel = new EmbeddedChannel();
        channel.config().setAutoRead(false);
        channel.pipeline().addLast(new ChannelOutboundHandlerAdapter() {
            @Override
            public void read(ChannelHandlerContext ctx) {
                reads.incrementAndGet();
                ctx.read();
            }
        });
        channel.pipeline().addLast(NettyPipeline.HttpTrafficHandler, new ChannelInboundHandlerAdapter());
        PipelinedReadResumer.install(channel.pipeline());
        return channel;
    }

    private static DefaultHttpContent content(String text) {
        return new DefaultHttpContent(Unpooled.copiedBuffer(text, StandardCharsets.US_ASCII));
    }

    /** An exchange bound to the channel on creation, ended the way Reactor Netty ends one. */
    private static final class EndableExchange extends ChannelOperations<NettyInbound, NettyOutbound> {

        EndableExchange(EmbeddedChannel channel) {
            super(Connection.from(channel), ConnectionObserver.emptyListener());
            bind();
        }

        void end() {
            terminate();
        }
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

    private static boolean answeredAfterPartialBody(DisposableServer server) throws IOException {
        String partialPost = "POST /missing HTTP/1.1\r\nHost: localhost\r\nContent-Length: 10\r\n\r\n12345";
        return PipelinedRequests.answeredAfter(server.port(), get("/slow") + partialPost, 2, "67890" + get("/missing"),
            1000);
    }

    private static boolean thirdAnswered(DisposableServer server) throws IOException {
        return PipelinedRequests.thirdAnswered(server.port(), get("/slow"), get("/missing"), get("/missing"), 1000);
    }
}
