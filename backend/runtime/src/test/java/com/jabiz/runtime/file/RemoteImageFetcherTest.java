package com.jabiz.runtime.file;

import com.jabiz.file.RemoteImageLink;
import com.jabiz.net.PublicAddresses;
import com.jabiz.runtime.test.HttpsStubServer;
import com.jabiz.runtime.test.HttpsStubServer.Answer;
import io.netty.handler.ssl.SslContextBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.test.StepVerifier;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fetcher alone against the HTTPS stub (decision D75): the parts the integration test cannot see from outside,
 * such as the client's own resolver refusing what was not checked.
 */
class RemoteImageFetcherTest {

    static final InetAddress LOOPBACK = address("127.0.0.1");
    static final HttpsStubServer STUB = HttpsStubServer.start(LOOPBACK);

    static {
        // Loaded here rather than first on an event loop, where reading the class file is a blocking call; the
        // application has loaded it long before any download.
        org.springframework.core.io.buffer.DataBufferUtils.releaseConsumer();
    }

    @TempDir
    Path dir;

    @AfterAll
    static void stop() {
        STUB.close();
    }

    private static RemoteImageFetcher fetcher() {
        try {
            RemoteFetchNetwork network = new RemoteFetchNetwork(host -> List.of(LOOPBACK),
                address -> address.equals(LOOPBACK) || PublicAddresses.isPublic(address),
                SslContextBuilder.forClient().trustManager(HttpsStubServer.certificate()).build());
            return new RemoteImageFetcher(network, new RemoteFetchProperties(3, Duration.ofSeconds(2),
                Duration.ofSeconds(1), Duration.ofSeconds(3), List.of(443, STUB.port())));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private RemoteImageLink link(String path) {
        return RemoteImageLink.parse("https://a.fetch.test:" + STUB.port() + path, Set.of(STUB.port()));
    }

    @Test
    void bodiesPastTheLimitAreAbandonedWhateverTheirLength() throws Exception {
        RemoteImageFetcher fetcher = fetcher();
        STUB.script("/declared", Answer.of(200, new byte[200_000]));
        STUB.script("/chunked", Answer.of(200, new byte[200_000]).chunked());
        STUB.script("/unbounded", Answer.of(200, new byte[200_000]).length(-1));
        for (String path : List.of("/declared", "/chunked", "/unbounded")) {
            Path target = Files.createFile(dir.resolve(path.substring(1)));
            StepVerifier.create(fetcher.fetch(link(path), 64 * 1024, target))
                .expectErrorSatisfies(error -> assertThat(((RemoteImageFetcher.Refusal) error).code()).as(path)
                    .isEqualTo("FILE_FETCH_TOO_LARGE"))
                .verify(Duration.ofSeconds(10));
        }
    }

    @Test
    void aBodyWithoutLengthWithinTheLimitIsKept() throws Exception {
        STUB.script("/small", Answer.of(200, new byte[1000]).length(-1));
        Path target = Files.createFile(dir.resolve("small"));
        StepVerifier.create(fetcher().fetch(link("/small"), 64 * 1024, target))
            .expectNext(1000L)
            .verifyComplete();
        assertThat(Files.size(target)).isEqualTo(1000);
    }

    @Test
    void theClientsResolverAcceptsOnlyResolvedAllowedAddresses() {
        RemoteImageFetcher.ValidatedAddresses resolver = new RemoteImageFetcher.ValidatedAddresses(LOOPBACK::equals);
        assertThat(resolver.accepted(new InetSocketAddress(LOOPBACK, 443))).isTrue();
        assertThat(resolver.accepted(new InetSocketAddress(address("127.0.0.2"), 443))).isFalse();
        assertThat(resolver.accepted(InetSocketAddress.createUnresolved("a.fetch.test", 443))).isFalse();
        assertThat(resolver.accepted(null)).isFalse();
    }

    @Test
    void networkFailuresBecomeRefusalsAndOtherErrorsStayAsTheyAre() {
        assertThat(((RemoteImageFetcher.Refusal) RemoteImageFetcher.refusalOf(new java.net.ConnectException("x")))
            .params()).containsEntry("reason", "UNREACHABLE");
        assertThat(((RemoteImageFetcher.Refusal) RemoteImageFetcher.refusalOf(
            new RuntimeException(new javax.net.ssl.SSLHandshakeException("x")))).params())
            .containsEntry("reason", "TLS");
        assertThat(((RemoteImageFetcher.Refusal) RemoteImageFetcher.refusalOf(
            io.netty.handler.timeout.ReadTimeoutException.INSTANCE)).code()).isEqualTo("FILE_FETCH_TIMEOUT");
        IllegalStateException bug = new IllegalStateException("bug");
        assertThat(RemoteImageFetcher.refusalOf(bug)).isSameAs(bug);
    }

    private static InetAddress address(String literal) {
        try {
            return InetAddress.getByName(literal);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
