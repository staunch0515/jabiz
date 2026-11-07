package com.jabiz.runtime.file;

import com.jabiz.file.RemoteImageLink;
import com.jabiz.i18n.PlatformErrorCodes;
import io.netty.channel.ChannelOption;
import io.netty.channel.ConnectTimeoutException;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.timeout.ReadTimeoutException;
import io.netty.resolver.AbstractAddressResolver;
import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Promise;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.netty.ByteBufFlux;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.HttpClientResponse;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * Downloads the bytes a link gives (decision D75), once, into a file of the upload area, on Reactor Netty without
 * blocking an event loop. For every hop - the link and each redirect, at most {@code jabiz.files.fetch.max-redirects}:
 * <ol>
 *   <li>the link is checked again ({@link RemoteImageLink}: https, no credentials, an allowed port, a usual host);</li>
 *   <li>its host is resolved once, on {@code boundedElastic}, and refused unless every address is public
 *       ({@link RemoteFetchNetwork#addresses()}); a name resolving to one public and one private address is refused,
 *       since another resolver might pick the private one;</li>
 *   <li>the connection goes to the first of those very addresses: the address is handed to Netty resolved, with the
 *       host name kept for SNI and certificate verification, so nothing resolves the name again between the check and
 *       the connection (DNS rebinding). The client's resolver itself accepts only addresses the policy allows, a
 *       second guard should an unresolved address ever reach it;</li>
 *   <li>one {@code GET} over HTTP/1.1 on a fresh connection (no pool, no retry), with fixed headers only: the host, a
 *       neutral user agent naming the platform and the image types wanted. No cookies, credentials, referrer or
 *       anything of the requester; no proxy; no compression asked for (and none undone);</li>
 *   <li>3xx with a location is a redirect, 200 the image; anything else fails ({@code FILE_FETCH_FAILED}). A
 *       declared length above the limit fails at once; the body is counted as it streams and the download abandoned
 *       as soon as it passes the limit, whatever the length said.</li>
 * </ol>
 * Response headers are limited to 16 KiB, the status line to 4 KiB. The connect timeout applies per connection, the
 * read timeout between reads, the deadline to the whole download. Refusals are {@link Refusal}s with the error codes
 * of decision D75; their messages never hold the link.
 */
@Component
public class RemoteImageFetcher {

    /** Sent as {@code User-Agent}: names the platform, nothing of the requester or the application. */
    public static final String USER_AGENT = "jabiz-image-fetch/1.0";

    static final String ACCEPT = "image/png, image/jpeg";

    private static final Set<Integer> REDIRECTS = Set.of(301, 302, 303, 307, 308);
    private static final int MAX_HEADER_BYTES = 16 * 1024;
    private static final int MAX_LINE_BYTES = 4 * 1024;

    /** A download refused or failed: the error code and its parameters (decision D75). */
    public static final class Refusal extends RuntimeException {

        private final String code;
        private final transient Map<String, Object> params;

        Refusal(String code, String message, Map<String, Object> params) {
            super(message, null, false, false);
            this.code = code;
            this.params = Map.copyOf(params);
        }

        public String code() {
            return code;
        }

        public Map<String, Object> params() {
            return params;
        }
    }

    /** The result of one hop: where it redirects, or how many bytes it gave. */
    private record Hop(String location, long size) {}

    private final RemoteFetchNetwork network;
    private final RemoteFetchProperties properties;
    private final HttpClient client;

    public RemoteImageFetcher(RemoteFetchNetwork network, RemoteFetchProperties properties) {
        this.network = network;
        this.properties = properties;
        this.client = HttpClient.newConnection()
            // Never through a proxy (system properties included): the proxy would resolve the name itself.
            .noProxy()
            .resolver(new ValidatedAddresses(network.addresses()))
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) Math.max(1, properties.connectTimeout().toMillis()))
            .responseTimeout(properties.readTimeout())
            .protocol(HttpProtocol.HTTP11)
            .followRedirect(false)
            .disableRetry(true)
            .compress(false)
            .keepAlive(false)
            .httpResponseDecoder(spec -> spec.maxHeaderSize(MAX_HEADER_BYTES).maxInitialLineLength(MAX_LINE_BYTES))
            .headers(headers -> headers.set(HttpHeaderNames.USER_AGENT, USER_AGENT)
                .set(HttpHeaderNames.ACCEPT, ACCEPT));
    }

    /**
     * Downloads what {@code link} gives into {@code target} (replacing it), at most {@code maxBytes}; the number of
     * bytes written. Errors are {@link Refusal}s or {@link RemoteImageLink.Refused}.
     */
    Mono<Long> fetch(RemoteImageLink link, long maxBytes, Path target) {
        return hop(link, 0, maxBytes, target)
            .timeout(properties.deadline(), Mono.error(() -> timeout("The download took longer than "
                + properties.deadline())))
            .onErrorMap(error -> !(error instanceof Refusal) && !(error instanceof RemoteImageLink.Refused),
                RemoteImageFetcher::refusalOf);
    }

    private Mono<Long> hop(RemoteImageLink link, int redirects, long maxBytes, Path target) {
        return resolve(link)
            .flatMap(address -> exchange(link, address, maxBytes, target))
            .flatMap(hop -> {
                if (hop.location() == null) {
                    return Mono.just(hop.size());
                }
                if (redirects >= properties.maxRedirects()) {
                    return Mono.error(new Refusal(PlatformErrorCodes.FILE_FETCH_TOO_MANY_REDIRECTS,
                        "The link redirects more than " + properties.maxRedirects() + " times",
                        Map.of("max", properties.maxRedirects())));
                }
                RemoteImageLink next = link.redirect(hop.location(), properties.ports());
                return hop(next, redirects + 1, maxBytes, target);
            });
    }

    /** The address to connect to: the first of the host's, all of which must be allowed. */
    private Mono<InetAddress> resolve(RemoteImageLink link) {
        return Mono.fromCallable(() -> link.literal()
                // A literal (checked to be one by RemoteImageLink) is parsed, never looked up.
                ? List.of(InetAddress.getByName(link.host()))
                : network.resolver().resolve(link.host()))
            .subscribeOn(Schedulers.boundedElastic())
            .map(addresses -> {
                if (addresses == null || addresses.isEmpty()) {
                    throw failed("UNRESOLVED", "The link's host has no address");
                }
                for (InetAddress address : addresses) {
                    if (!network.addresses().test(address)) {
                        throw new Refusal(PlatformErrorCodes.FILE_FETCH_ADDRESS_NOT_PUBLIC,
                            "The link's host resolves to an address that is not public", Map.of());
                    }
                }
                return addresses.getFirst();
            });
    }

    private Mono<Hop> exchange(RemoteImageLink link, InetAddress address, long maxBytes, Path target) {
        return Mono.defer(() -> {
            InetSocketAddress remote;
            try {
                // Resolved, so nothing looks the name up again; the name stays for SNI and certificate checks.
                remote = new InetSocketAddress(InetAddress.getByAddress(link.host(), address.getAddress()),
                    link.port());
            } catch (UnknownHostException e) {
                return Mono.error(failed("UNREACHABLE", "The address is malformed"));
            }
            return client.remoteAddress(() -> remote)
                .secure(spec -> spec.sslContext(network.ssl()).handlerConfigurator(handler -> {
                    SSLEngine engine = handler.engine();
                    SSLParameters parameters = engine.getSSLParameters();
                    parameters.setEndpointIdentificationAlgorithm("HTTPS");
                    parameters.setServerNames(link.literal() ? Collections.emptyList()
                        : List.of(new SNIHostName(link.host())));
                    engine.setSSLParameters(parameters);
                }))
                .headers(headers -> headers.set(HttpHeaderNames.HOST, link.hostHeader()))
                .get()
                .uri(link.requestTarget())
                .response((response, body) -> answer(response, body, maxBytes, target))
                .next()
                .switchIfEmpty(Mono.error(() -> failed("EMPTY", "The server answered nothing")));
        });
    }

    private static Mono<Hop> answer(HttpClientResponse response, ByteBufFlux body, long maxBytes, Path target) {
        int status = response.status().code();
        if (REDIRECTS.contains(status)) {
            String location = response.responseHeaders().get(HttpHeaderNames.LOCATION);
            if (location == null || location.isBlank()) {
                return Mono.error(failed("STATUS", "A redirect without a location"));
            }
            return Mono.just(new Hop(location, -1));
        }
        if (status != 200) {
            return Mono.error(failed("STATUS", "The server answered " + status));
        }
        String length = response.responseHeaders().get(HttpHeaderNames.CONTENT_LENGTH);
        if (length != null) {
            try {
                if (Long.parseLong(length.strip()) > maxBytes) {
                    return Mono.error(tooLarge(maxBytes));
                }
            } catch (NumberFormatException e) {
                return Mono.error(failed("STATUS", "The answer's length is malformed"));
            }
        }
        AtomicLong size = new AtomicLong();
        // Counted as it arrives, then buffered (at most the limit) for the file: reading is not held back by the
        // writes, so the connection is read to its end, or abandoned at the limit, without pausing it.
        Flux<DataBuffer> content = body.asByteArray().<DataBuffer>map(bytes -> {
            if (size.addAndGet(bytes.length) > maxBytes) {
                throw tooLarge(maxBytes);
            }
            return DefaultDataBufferFactory.sharedInstance.wrap(bytes);
        }).onBackpressureBuffer();
        return DataBufferUtils.write(content, target, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)
            .then(Mono.fromCallable(() -> {
                if (size.get() == 0) {
                    throw failed("EMPTY", "The server sent no content");
                }
                return new Hop(null, size.get());
            }));
    }

    /** What a failure of the network or of TLS is, as a refusal; any other error as it is. */
    static Throwable refusalOf(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof Refusal || cause instanceof RemoteImageLink.Refused) {
                return cause;
            }
            if (cause instanceof TimeoutException || cause instanceof ReadTimeoutException
                || cause instanceof ConnectTimeoutException
                || cause instanceof io.netty.handler.timeout.TimeoutException) {
                return timeout("No answer in time");
            }
            if (cause instanceof SSLException) {
                return failed("TLS", "No secure connection could be made");
            }
            if (cause instanceof UnknownHostException) {
                return failed("UNRESOLVED", "The link's host was not found");
            }
        }
        if (error instanceof IOException || error instanceof io.netty.channel.ChannelException
            || error instanceof io.netty.handler.codec.DecoderException) {
            return failed("UNREACHABLE", "The server could not be reached or answered unusably");
        }
        return error;
    }

    static Refusal tooLarge(long maxBytes) {
        return new Refusal(PlatformErrorCodes.FILE_FETCH_TOO_LARGE, "The image is larger than " + maxBytes
            + " bytes", Map.of("max", FileUploadService.humanSize(maxBytes)));
    }

    private static Refusal timeout(String message) {
        return new Refusal(PlatformErrorCodes.FILE_FETCH_TIMEOUT, message, Map.of());
    }

    private static Refusal failed(String reason, String message) {
        return new Refusal(PlatformErrorCodes.FILE_FETCH_FAILED, message, Map.of("reason", reason));
    }

    /**
     * The client's resolver: takes only addresses already resolved and allowed, and resolves nothing. The fetcher
     * resolves and checks each host itself; this refuses whatever else might reach the connection.
     */
    static final class ValidatedAddresses extends AddressResolverGroup<InetSocketAddress> {

        private final Predicate<InetAddress> allowed;

        ValidatedAddresses(Predicate<InetAddress> allowed) {
            this.allowed = allowed;
        }

        @Override
        protected AddressResolver<InetSocketAddress> newResolver(EventExecutor executor) {
            return new AbstractAddressResolver<>(executor) {
                @Override
                protected boolean doIsResolved(InetSocketAddress address) {
                    return accepted(address);
                }

                @Override
                protected void doResolve(InetSocketAddress address, Promise<InetSocketAddress> promise) {
                    if (accepted(address)) {
                        promise.setSuccess(address);
                    } else {
                        promise.setFailure(new UnknownHostException("Not a checked address"));
                    }
                }

                @Override
                protected void doResolveAll(InetSocketAddress address, Promise<List<InetSocketAddress>> promise) {
                    if (accepted(address)) {
                        promise.setSuccess(List.of(address));
                    } else {
                        promise.setFailure(new UnknownHostException("Not a checked address"));
                    }
                }
            };
        }

        boolean accepted(SocketAddress address) {
            return address instanceof InetSocketAddress inet && !inet.isUnresolved()
                && allowed.test(inet.getAddress());
        }
    }
}
