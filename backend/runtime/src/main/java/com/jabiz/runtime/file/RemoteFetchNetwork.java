package com.jabiz.runtime.file;

import com.jabiz.net.PublicAddresses;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.net.ssl.SSLException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * What downloading images from links (decision D75) takes from the network: how host names are resolved, which
 * addresses may be connected to, and which certificates are trusted. In production the system resolver,
 * {@link PublicAddresses#isPublic} and the JVM's trusted certificates; integration tests replace the bean (a
 * {@code @Primary} one in their test configuration) to reach a stub on this machine. No property changes it.
 *
 * @param resolver  resolves a host name to its addresses; blocking, called on {@code boundedElastic}
 * @param addresses whether an address may be connected to
 * @param ssl       the client's TLS context (hostname verification is always on)
 */
public record RemoteFetchNetwork(HostResolver resolver, Predicate<InetAddress> addresses, SslContext ssl) {

    /** Resolves a host name to all of its addresses. */
    @FunctionalInterface
    public interface HostResolver {
        List<InetAddress> resolve(String host) throws UnknownHostException;
    }

    public RemoteFetchNetwork {
        Objects.requireNonNull(resolver, "resolver");
        Objects.requireNonNull(addresses, "addresses");
        Objects.requireNonNull(ssl, "ssl");
    }

    /** The system resolver, public addresses only, the JVM's trusted certificates. */
    public static RemoteFetchNetwork system() {
        try {
            return new RemoteFetchNetwork(host -> Arrays.asList(InetAddress.getAllByName(host)),
                PublicAddresses::isPublic,
                SslContextBuilder.forClient().protocols("TLSv1.3", "TLSv1.2").build());
        } catch (SSLException e) {
            throw new IllegalStateException("No TLS context for downloading images", e);
        }
    }

    @Configuration
    static class Defaults {

        @Bean
        RemoteFetchNetwork remoteFetchNetwork() {
            return system();
        }
    }
}
