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
import java.util.List;
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
 *
 * <p>The rules are guard-rails against the usual ways, not a sandbox: they read the compiled classes, so a call made
 * through reflection, a method handle or a library that business code hands a URL to is not seen. Reviews still
 * matter.
 */
public final class PlatformArchRules {

    /**
     * Packages of network clients: the JDK's HTTP and TLS clients, Spring's HTTP clients and request factories, and
     * Netty (Reactor Netty included).
     */
    static final String[] CLIENT_PACKAGES = {"java.net.http..", "javax.net..",
        "org.springframework.web.reactive.function.client..", "org.springframework.web.client..",
        "org.springframework.http.client..", "io.netty..", "reactor.netty.."};

    /** The platform's own machinery of external calls, which only the platform drives. */
    static final String EXTERNAL_INTERNALS = "com.jabiz.runtime.external..";

    private static final Set<String> OPENING_METHODS = Set.of("openConnection", "openStream", "getContent");

    /** Types whose {@code parse(String uri)} fetches the document from the address. */
    private static final List<String> URI_PARSERS = List.of("javax.xml.parsers.DocumentBuilder",
        "javax.xml.parsers.SAXParser", "org.xml.sax.XMLReader");

    private PlatformArchRules() {}

    /**
     * Business code calls other systems only through the declared external services of a blocking step
     * ({@code ExternalServices}, decision D38), whose every request the platform checks against the service's hosts,
     * times and observes. So the business packages may use no network client: no JDK, Spring or Netty HTTP client,
     * no TLS factory, no socket or socket channel, no {@code URLConnection} or Spring {@code UrlResource}, no
     * {@code URL#openConnection}, {@code URL#openStream} or {@code URL#getContent}, and no XML parser reading a
     * document from an address ({@code parse(String uri)}).
     *
     * @param businessPackages the application's packages, ArchUnit style ({@code com.example.app..})
     */
    public static ArchRule noDirectNetworkAccess(String... businessPackages) {
        return noClasses().that().resideInAnyPackage(businessPackages)
            .should().dependOnClassesThat().resideInAnyPackage(CLIENT_PACKAGES)
            .orShould().dependOnClassesThat(NETWORK_TYPES)
            .orShould().callMethodWhere(OPENS_URL)
            .orShould().callMethodWhere(PARSES_FROM_ADDRESS)
            .because("business code calls other systems only through ExternalServices in a BlockingStep "
                + "(decision D38)");
    }

    /**
     * Business code does not touch the platform's machinery of external calls ({@code com.jabiz.runtime.external}):
     * it uses the core's {@code ExternalServices} only, so it cannot open a call scope of its own and pass for a
     * blocking step or a real run when it is a dry run (decision D38).
     *
     * @param businessPackages the application's packages, ArchUnit style ({@code com.example.app..})
     */
    public static ArchRule noPlatformInternals(String... businessPackages) {
        return noClasses().that().resideInAnyPackage(businessPackages)
            .should().dependOnClassesThat().resideInAPackage(EXTERNAL_INTERNALS)
            .because("external services are used through com.jabiz.external.ExternalServices only (decision D38)");
    }

    /** Database access: JDBC, R2DBC, Spring's data access, and the platform's storage, queries and entity manager. */
    static final List<String> DATABASE_PACKAGES = List.of("java.sql..", "javax.sql..", "io.r2dbc..",
        "org.springframework.r2dbc..", "org.springframework.jdbc..", "org.springframework.data..",
        "com.jabiz.runtime.storage..", "com.jabiz.runtime.query..");

    /**
     * External job handlers and blocking steps of the business packages do not touch the database (decision D38): a
     * handler runs with no transaction at all, a blocking step beside its process's transaction, and both on a
     * virtual thread where only external calls belong. Data reaches them through their input or context, and their
     * results go back through a process.
     */
    public static ArchRule handlersStayOutOfTheDatabase(String... businessPackages) {
        DescribedPredicate<JavaClass> handlers = JavaClass.Predicates.resideInAnyPackage(businessPackages)
            .and(JavaClass.Predicates.assignableTo("com.jabiz.external.ExternalJobHandler")
                .or(JavaClass.Predicates.assignableTo("com.jabiz.process.BlockingStep")))
            .as("external job handlers and blocking steps of " + String.join(", ", businessPackages));
        return noClasses().that(handlers)
            .should().dependOnClassesThat().resideInAnyPackage(DATABASE_PACKAGES.toArray(String[]::new))
            .orShould().dependOnClassesThat().haveFullyQualifiedName("com.jabiz.runtime.DatasetEntityManager")
            .because("handlers and blocking steps make external calls only; data goes in and out through processes "
                + "(decision D38)");
    }

        /** The platform's own ways of starting processes and of acting as someone, which only the platform uses. */
    static final List<String> PROCESS_MACHINERY = List.of("com.jabiz.runtime.process.ProcessExecutor",
        "com.jabiz.runtime.process.ExecutionOptions", "com.jabiz.runtime.security.ActingUser");

    /**
     * Business code does not start processes or choose who runs them (decision D41): no {@code ProcessExecutor}, no
     * {@code ExecutionOptions} (with which a platform entry point declares itself), no {@code ActingUser}, and no
     * {@code RequestContext.system}. Business processes run through the process API, events, jobs and
     * {@code CallProcess}, as their declarations allow; reading who acts ({@code RequestContext.SYSTEM_ACTOR}) is fine.
     *
     * @param businessPackages the application's packages, ArchUnit style ({@code com.example.app..})
     */
    public static ArchRule noProcessMachinery(String... businessPackages) {
        return noClasses().that().resideInAnyPackage(businessPackages)
            .should().dependOnClassesThat(PROCESS_MACHINERY_TYPES)
            .orShould().callMethod("com.jabiz.context.RequestContext", "system", "java.util.Locale",
                "java.lang.String")
            .because("processes are started by the platform, as their declarations allow (decision D41)");
    }

    private static final DescribedPredicate<JavaClass> PROCESS_MACHINERY_TYPES = new DescribedPredicate<>(
        "are the platform's process executor, execution options or acting user") {
        @Override
        public boolean test(JavaClass type) {
            return PROCESS_MACHINERY.contains(type.getName());
        }
    };

    private static final DescribedPredicate<JavaClass> NETWORK_TYPES = new DescribedPredicate<>(
        "are sockets, socket channels, URL connections or URL resources") {
        @Override
        public boolean test(JavaClass type) {
            return type.isAssignableTo(Socket.class) || type.isAssignableTo(ServerSocket.class)
                || type.isAssignableTo(DatagramSocket.class) || type.isAssignableTo(NetworkChannel.class)
                || type.isAssignableTo(URLConnection.class)
                || type.isAssignableTo("org.springframework.core.io.UrlResource");
        }
    };

    private static final DescribedPredicate<JavaMethodCall> OPENS_URL = new DescribedPredicate<>(
        "open a connection, stream or content of a URL") {
        @Override
        public boolean test(JavaMethodCall call) {
            return call.getTargetOwner().isEquivalentTo(URL.class) && OPENING_METHODS.contains(call.getName());
        }
    };

    private static final DescribedPredicate<JavaMethodCall> PARSES_FROM_ADDRESS = new DescribedPredicate<>(
        "parse XML from an address") {
        @Override
        public boolean test(JavaMethodCall call) {
            return call.getName().equals("parse")
                && URI_PARSERS.stream().anyMatch(parser -> call.getTargetOwner().isAssignableTo(parser))
                && !call.getTarget().getRawParameterTypes().isEmpty()
                && call.getTarget().getRawParameterTypes().getFirst().isEquivalentTo(String.class);
        }
    };
}
