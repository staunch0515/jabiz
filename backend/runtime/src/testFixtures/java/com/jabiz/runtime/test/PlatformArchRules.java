package com.jabiz.runtime.test;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.lang.ArchRule;

import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLConnection;
import java.nio.channels.NetworkChannel;
import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Architecture rules the platform shares with its applications (decision D38, ROADMAP 16e Q21): an application's
 * {@code ArchitectureTest} checks its business packages with them, so the platform's own tests and every application
 * branch hold business code to the same limits.
 *
 * <pre>{@code
 * @Test
 * void businessCodeUsesNoNetworkClient() {
 *     PlatformArchRules.noDirectNetworkAccess("com.example.myapp..").check(CLASSES);
 * }
 * }</pre>
 */
public final class PlatformArchRules {

    /** Packages of network clients: the JDK's HTTP and TLS clients and Spring's HTTP clients. */
    static final String[] CLIENT_PACKAGES = {"java.net.http..", "javax.net..",
        "org.springframework.web.reactive.function.client..", "org.springframework.web.client.."};

    private static final Set<String> OPENING_METHODS = Set.of("openConnection", "openStream");

    private PlatformArchRules() {}

    /**
     * Business code calls other systems only through the declared external services of a blocking step
     * ({@code ExternalServices}, decision D38), whose every request the platform checks against the service's hosts,
     * times and observes. So the business packages may use no network client: no JDK or Spring HTTP client, no TLS
     * factory, no socket or socket channel, no {@code URLConnection}, and no {@code URL#openConnection} or
     * {@code URL#openStream}.
     *
     * @param businessPackages the application's packages, ArchUnit style ({@code com.example.app..})
     */
    public static ArchRule noDirectNetworkAccess(String... businessPackages) {
        return noClasses().that().resideInAnyPackage(businessPackages)
            .should().dependOnClassesThat().resideInAnyPackage(CLIENT_PACKAGES)
            .orShould().dependOnClassesThat(SOCKETS)
            .orShould().callMethodWhere(OPENS_URL)
            .because("business code calls other systems only through ExternalServices in a BlockingStep "
                + "(decision D38)");
    }

    private static final DescribedPredicate<JavaClass> SOCKETS = new DescribedPredicate<>(
        "are sockets, socket channels or URL connections") {
        @Override
        public boolean test(JavaClass type) {
            return type.isAssignableTo(Socket.class) || type.isAssignableTo(ServerSocket.class)
                || type.isAssignableTo(DatagramSocket.class) || type.isAssignableTo(NetworkChannel.class)
                || type.isAssignableTo(URLConnection.class);
        }
    };

    private static final DescribedPredicate<JavaMethodCall> OPENS_URL = new DescribedPredicate<>(
        "open a connection or stream of a URL") {
        @Override
        public boolean test(JavaMethodCall call) {
            return call.getTargetOwner().isEquivalentTo(URL.class) && OPENING_METHODS.contains(call.getName());
        }
    };
}
