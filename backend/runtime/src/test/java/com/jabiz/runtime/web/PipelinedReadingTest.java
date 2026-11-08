package com.jabiz.runtime.web;

import com.jabiz.runtime.test.PipelinedRequests;
import io.netty.handler.codec.http.HttpHeaderNames;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.io.IOException;
import java.time.Duration;

import static com.jabiz.runtime.test.PipelinedRequests.get;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reactor Netty's own server, spoken to over a plain socket: a request buffered as pipelined and answered at once (as a
 * 304 or the Service Worker guard's 404 is) must not leave the keep-alive connection unread. Up to Reactor Netty 1.3.7
 * the connection stopped being read after such a drain and the platform added a handler that asked for the next read
 * (16c-2); 1.3.8 reads on by itself and the handler went (17zz7). If this fails after an upgrade, a PWA's next
 * navigation hangs again: the Service Worker's install revalidates the shell while the page loads.
 */
class PipelinedReadingTest {

    private static final int CONNECTIONS = 20;

    @Test
    void theNextRequestOnTheConnectionIsAnswered() throws IOException {
        DisposableServer server = start();
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
     * The buffered request is a {@code POST} whose body is still on its way: answered at once, it leaves only part of
     * its body in the buffer, and the rest and the next request must still be read.
     */
    @Test
    void theNextRequestIsAnsweredAfterARequestWithPartOfItsBody() throws IOException {
        DisposableServer server = start();
        try {
            for (int i = 0; i < CONNECTIONS; i++) {
                assertThat(answeredAfterPartialBody(server)).as("connection %d", i).isTrue();
            }
        }
        finally {
            server.disposeNow();
        }
    }

    /** {@code /slow} answers a little later, so that a request behind it is buffered; anything else is 404 at once. */
    private static DisposableServer start() {
        return HttpServer.create().host("127.0.0.1").port(0)
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
