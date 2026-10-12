package com.jabiz.runtime.web;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.LastHttpContent;
import org.springframework.boot.reactor.netty.NettyServerCustomizer;
import org.springframework.stereotype.Component;
import reactor.netty.NettyPipeline;
import reactor.netty.channel.ChannelOperations;
import reactor.netty.http.server.HttpServer;

/**
 * Keeps reading a keep-alive HTTP/1.1 connection after Reactor Netty drained a request it had buffered as pipelined.
 *
 * <p>Reactor Netty's server reads a connection only when asked (no auto-read). A request that arrives before the
 * previous response has been finished on the server's side, which a browser does as soon as it has the whole previous
 * response, is buffered and drained later. When the drained request is answered at once (a 304, a 404 of
 * {@link ServiceWorkerScriptGuard}), its response completes while the end of the request is still in the buffer: the
 * drain then hands that end on and stops, and nothing asks the channel to read again. The connection is still open, so
 * the browser sends its next request on it, which is never read and never answered (Reactor Netty 1.3.1 to 1.3.7 at
 * least). A PWA's Service Worker meets this as a matter of course: its install revalidates the shell (304s) while the
 * page loads, and the page's next navigation hangs (the reload after a new version took over, ROADMAP phase 16c-2).
 *
 * <p>This handler sits right behind Reactor Netty's {@code HttpTrafficHandler} and asks for the next read whenever the
 * end of a request goes past with no exchange left on the connection, which is exactly where the read is missing. A
 * read asked for twice is one read, so where Reactor Netty asks too nothing changes. Remove it once Reactor Netty
 * resumes reading after such a drain itself ({@code PipelinedReadResumerTest} says when).
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

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            // Read before passing it on: the message may be released downstream.
            boolean endOfRequest = msg instanceof LastHttpContent;
            ctx.fireChannelRead(msg);
            if (endOfRequest && ChannelOperations.get(ctx.channel()) == null && !ctx.channel().config().isAutoRead()) {
                ctx.read();
            }
        }
    }
}
