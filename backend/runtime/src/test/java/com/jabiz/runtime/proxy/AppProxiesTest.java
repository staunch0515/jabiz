package com.jabiz.runtime.proxy;

import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.process.ProcessRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.ResolvableType;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/** The app proxies' configuration and startup check (decision D60), every problem at once. */
class AppProxiesTest {

    static final String SECRET = "a-secret-of-thirty-two-characters-at-least";

    record In() {}

    static ProcessDefinition<In, In, ProcessContext> process(String name,
        Consumer<ProcessDefinitionBuilder<In, In, ProcessContext>> declarations) {
        return ProcessDefinition.define(name, 1, In.class, In.class, ProcessContext.class, pb -> {
            pb.contextFactory((start, input) -> new ProcessContext(start))
                .outputMapper(ctx -> new In())
                .compute("Nothing", (NoMetadata metadata, ProcessContext ctx) -> { });
            declarations.accept(pb);
        });
    }

    static ProcessRegistry registry(ProcessDefinition<?, ?, ?>... definitions) {
        StaticListableBeanFactory declared = new StaticListableBeanFactory();
        for (int i = 0; i < definitions.length; i++) {
            declared.addBean("process" + i, definitions[i]);
        }
        return new ProcessRegistry(declared.getBeanProvider(ResolvableType.forClass(ProcessDefinition.class)));
    }

    static AppProxies.Config shop() {
        return new AppProxies.Config(List.of(SECRET), null, "[a-z]+\\.shop\\.example", null, null, null);
    }

    static List<String> messages(List<CheckProblem> problems) {
        return problems.stream().map(p -> p.severity() + " " + p.location() + ": " + p.message()).toList();
    }

    @Test
    void everyProblemIsReportedAndOnlyGoodProxiesAreServed() {
        ProcessRegistry registry = registry(
            process("GOOD", pb -> pb.appProxy("shop")),
            process("NOWHERE", pb -> pb.appProxy("nowhere")),
            process("TWO", pb -> pb.appProxy("shop").permissions("other")),
            process("ASKS", pb -> pb.appProxy("shop").requiresVerifiedEmail()),
            process("OPEN", pb -> pb.permissions("p")));
        AppProxies proxies = new AppProxies(Map.of(
            "shop", shop(),
            "Bad", shop(),
            "broken", new AppProxies.Config(List.of("short", "${UNSET_PROXY_SECRET_OF_SOME_LENGTH_HERE}"), "1x", "[",
                "", "ok", Duration.ofHours(1))), () -> registry);

        assertThat(proxies.find("shop")).isPresent();
        assertThat(proxies.find("broken")).isEmpty();
        assertThat(proxies.process("shop", "GOOD")).isPresent();
        // Another proxy's, an open one, an unknown one: not this proxy's.
        assertThat(proxies.process("shop", "NOWHERE")).isEmpty();
        assertThat(proxies.process("shop", "TWO")).isEmpty();
        assertThat(proxies.process("shop", "OPEN")).isEmpty();
        assertThat(proxies.process("shop", "NONE")).isEmpty();
        assertThat(messages(proxies.check())).containsExactlyInAnyOrder(
            "ERROR jabiz.app-proxies.Bad: not a valid name (lower-case letters, digits and '-', at most 40, starting"
                + " with a letter)",
            "ERROR jabiz.app-proxies.broken: secrets[0]: missing or shorter than 32 characters",
            "ERROR jabiz.app-proxies.broken: secrets[1]: an unresolved placeholder (is its environment variable set?)",
            "ERROR jabiz.app-proxies.broken: tenant-pattern is not a valid pattern",
            "ERROR jabiz.app-proxies.broken: tenant-parameter is not a valid parameter name",
            "ERROR jabiz.app-proxies.broken: customer-parameter is not a valid parameter name",
            "ERROR jabiz.app-proxies.broken: max-age must be positive and at most PT10M",
            "ERROR process NOWHERE v1: names no configured app proxy: nowhere (jabiz.app-proxies.<name>)",
            "ERROR process TWO v1: an appProxy() process holds its proxy's permission alone: its callers have no other"
                + " (decision D60)",
            "ERROR process ASKS v1: an appProxy() process asks nothing of a signed-in user (alternative permissions,"
                + " a second factor, a verified address): its callers are another platform's visitors");
        assertThat(proxies.find("shop").orElseThrow().toString()).doesNotContain(SECRET);
    }

    @Test
    void proxiesAreBoundFromTheEnvironment() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("jabiz.app-proxies.shop.secrets[0]", SECRET)
            .withProperty("jabiz.app-proxies.shop.tenant-pattern", "[a-z]+\\.shop\\.example")
            .withProperty("jabiz.app-proxies.shop.max-age", "30s");
        AppProxy proxy = new AppProxies(environment, new StaticListableBeanFactory()
            .getBeanProvider(ProcessRegistry.class)).find("shop").orElseThrow();
        assertThat(proxy.maxAge()).isEqualTo(Duration.ofSeconds(30));
        assertThat(proxy.tenantParameter()).isEqualTo("shop");
        assertThat(proxy.customerParameter()).isEqualTo("logged_in_customer_id");
        assertThat(proxy.isTenant("demo.shop.example")).isTrue();
        assertThat(proxy.isTenant("demo.shop.example.evil")).isFalse();
    }
}
