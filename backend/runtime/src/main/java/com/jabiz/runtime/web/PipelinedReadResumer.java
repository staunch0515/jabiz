package com.jabiz.runtime.web;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpContent;
import org.springframework.boot.reactor.netty.NettyServerCustomizer;
import org.springframework.stereotype.Component;
import reactor.netty.NettyPipeline;
import reactor.netty.channel.ChannelOperations;
import reactor.netty.http.server.HttpServer;

import java.util.concurrent.RejectedExecutionException;

/**
 * Keeps reading a keep-alive HTTP/1.1 connection after Reactor Netty drained a request it had buffered as pipelined.
 *
 * <p>Reactor Netty's server reads a connection only when asked (no auto-read). A request that arrives before the
 * previous response has been finished on the server's side, which a browser does as soon as it has the whole previous
 * response, is buffered and drained later. When the drained request is answered at once (a 304, a 404 of
 * {@link ServiceWorkerScriptGuard}, a 401), its response completes while the rest of the request is still in the
 * buffer (its end, or only part of its body): the drain hands that on and stops, and nothing asks the channel to read
 * again. The connection is still open, so the browser sends its next request on it, which is never read and never
 * answered (Reactor Netty 1.3.1 to 1.3.7 at least). A PWA's Service Worker meets this as a matter of course: its
 * install revalidates the shell (304s) while the page loads, and the page's next navigation hangs (the reload after a
 * new version took over, ROADMAP phase 16c-2).
 *
 * <p>This handler sits right behind Reactor Netty's {@code HttpTrafficHandler} and asks for the next read whenever
 * request content (part of a body or its end) goes past with no exchange left on the connection, which is exactly
 * where the read is missing; Reactor Netty itself reads again for content it drops when nothing is pending. The
 * drained content can also reach the exchange it belongs to after that exchange has written its whole answer but
 * before it has ended (Reactor Netty ends it only once the last write completes): the exchange drops the content
 * without reading and then ends, again with nothing asking to read. So content that goes past while an exchange is
 * still bound has that exchange watched, and once it ends, a read is asked for if no other exchange has started by
 * then. A read asked for twice is one read, so where Reactor Netty asks too nothing changes. Remove it once Reactor
 * Netty resumes reading after such a drain itself ({@code PipelinedReadResumerTest} says when).
 *
 * <p>Limits: the handler is added when the channel is initialised and only where the HTTP/1.1 traffic handler is
 * already in place, so HTTP/1.1 negotiated later (TLS with ALPN, where Reactor Netty adds that handler after the
 * handshake) would go without it; the server is plain HTTP/1.1 today. Where Reactor Netty resumes reading itself it
 * also re-arms the connection's idle timeout; this read does not, so with {@code server.netty.idle-timeout} set (it is
 * not today) such a connection would not be closed for idleness until its next exchange.
 */
@Component
class PipelinedReadResumer implements NettyServerCustomizer {

    /** The handler's name in the channel pipeline. */
    static final String NAME = "jabiz.pipelinedReadResumer";

    @Override
    public HttpServer apply(HttpServer server) {
        return server.doOnChannelInit((observer, channel, remoteAddress) -> install(channel.pipeline()));
    }

    /** Adds the handler behind the HTTP/1.1 traffic handler; an HTTP/2 or other pipeline is left as it is. */
    static void install(ChannelPipeline pipeline) {
        if (pipeline.get(NettyPipeline.HttpTrafficHandler) != null && pipeline.get(NAME) == null) {
            pipeline.addAfter(NettyPipeline.HttpTrafficHandler, NAME, new Handler());
        }
    }

    static final class Handler extends ChannelInboundHandlerAdapter {

        /**
         * The exchange content last went past while it was bound, until it has ended (then cleared, so that a finished
         * exchange and its request are not kept). Event loop only.
         */
        private ChannelOperations<?, ?> watched;

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            // Checked before passing it on: the message may be released downstream.
            boolean content = msg instanceof HttpContent;
            ctx.fireChannelRead(msg);
            if (!content || ctx.channel().config().isAutoRead()) {
                return;
            }
            ChannelOperations<?, ?> exchange = ChannelOperations.get(ctx.channel());
            if (exchange == null) {
                ctx.read();
            }
            else if (exchange != watched) {
                // Once per exchange, not per chunk of a body.
                watched = exchange;
                exchange.onTerminate()
                    .subscribe(null, error -> resumeIfIdle(ctx, exchange), () -> resumeIfIdle(ctx, exchange));
            }
        }

        /**
         * Asks for a read on the event loop, after Reactor Netty has finished ending the exchange (it may start a
         * buffered request next, which then reads for itself).
         */
        /** The exchange being watched, if any (for tests). */
        ChannelOperations<?, ?> watched() {
            return watched;
        }

        private void resumeIfIdle(ChannelHandlerContext ctx, ChannelOperations<?, ?> ended) {
            try {
                ctx.executor().execute(() -> {
                    if (watched == ended) {
                        watched = null;
                    }
                    if (ctx.channel().isActive() && ChannelOperations.get(ctx.channel()) == null
                        && !ctx.channel().config().isAutoRead()) {
                        ctx.read();
                    }
                });
            }
            catch (RejectedExecutionException e) {
                // The event loop is shutting down, and the connection with it: nothing left to read.
            }
        }
    }
}
