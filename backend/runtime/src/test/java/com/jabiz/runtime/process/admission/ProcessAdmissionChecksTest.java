package com.jabiz.runtime.process.admission;

import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.process.ProcessRegistry;
import com.jabiz.runtime.web.ProcessRateLimitProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.ResolvableType;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/** The startup checks of admission queues and process rate limits (decision D45), all reported at once. */
class ProcessAdmissionChecksTest {

    record In(String value) {}

    private static ProcessDefinition<In, In, ProcessContext> process(String name,
        Consumer<ProcessDefinitionBuilder<In, In, ProcessContext>> declarations) {
        return ProcessDefinition.define(name, 1, In.class, In.class, ProcessContext.class, pb -> {
            pb.permissions("p")
                .contextFactory((start, input) -> new ProcessContext(start))
                .outputMapper(ctx -> new In("x"))
                .compute("Nothing", (NoMetadata metadata, ProcessContext ctx) -> { });
            declarations.accept(pb);
        });
    }

    private static List<CheckProblem> check(AdmissionSettings settings, Map<String, ProcessRateLimitProperties.Limit>
        limits, ProcessDefinition<?, ?, ?>... definitions) {
        StaticListableBeanFactory declared = new StaticListableBeanFactory();
        for (int i = 0; i < definitions.length; i++) {
            declared.addBean("process" + i, definitions[i]);
        }
        ProcessRegistry registry = new ProcessRegistry(
            declared.getBeanProvider(ResolvableType.forClass(ProcessDefinition.class)));
        return new ProcessAdmissionChecks(registry, settings, new ProcessRateLimitProperties(limits), 10,
            Duration.ofSeconds(30)).check();
    }

    private static List<String> messages(List<CheckProblem> problems) {
        return problems.stream().map(p -> p.severity() + " " + p.location() + ": " + p.message()).toList();
    }

    @Test
    void wellDeclaredQueuesAndLimitsPass() {
        AdmissionSettings settings = new AdmissionSettings(true, 4, null, null, null,
            Map.of("test.hot", new AdmissionSettings.Family(1, 100, Duration.ofSeconds(1), null)));
        assertThat(check(settings, Map.of("OPEN", new ProcessRateLimitProperties.Limit(20, null, null, null)),
            process("OPEN", pb -> pb.admission("test.hot", In::value)),
            process("ITEM", pb -> pb.internal().admission("test.hot", In::value)))).isEmpty();
        assertThat(check(AdmissionSettings.DEFAULT, Map.of())).isEmpty();
    }

    @Test
    void everyProblemIsReportedAtOnce() {
        AdmissionSettings settings = new AdmissionSettings(true, 10, 0, 0,
            new AdmissionSettings.Family(0, -1, Duration.ofMinutes(1), Duration.ofMinutes(1)),
            Map.of("nobody.declares", new AdmissionSettings.Family(17, 10_001, Duration.ZERO, null)));
        List<String> problems = messages(check(settings, Map.of(
                "UNKNOWN", new ProcessRateLimitProperties.Limit(1, null, null, null),
                "INTERNAL", new ProcessRateLimitProperties.Limit(null, 0, null, Duration.ZERO),
                "SUB", new ProcessRateLimitProperties.Limit(null, null, null, null)),
            process("BAD_FAMILY", pb -> pb.admission("Bad:Family", In::value)),
            process("SUB", pb -> pb.subProcessOnly().admission("test.hot", In::value)),
            process("INTERNAL", pb -> pb.internal())));
        assertThat(problems).anyMatch(p -> p.startsWith("ERROR BAD_FAMILY v1: admission family 'Bad:Family'"))
            .anyMatch(p -> p.startsWith("ERROR SUB v1: is subProcessOnly() and declares an admission"))
            .anyMatch(p -> p.startsWith("WARNING jabiz.process.admission.families[nobody.declares]: no process"))
            .anyMatch(p -> p.startsWith("ERROR jabiz.process.admission.defaults.concurrency"))
            .anyMatch(p -> p.startsWith("ERROR jabiz.process.admission.defaults.max-queue"))
            .anyMatch(p -> p.startsWith("ERROR jabiz.process.admission.defaults.max-wait"))
            .anyMatch(p -> p.startsWith("ERROR jabiz.process.admission.defaults.retry-after"))
            .anyMatch(p -> p.startsWith("ERROR jabiz.process.admission.families[nobody.declares].concurrency"))
            .anyMatch(p -> p.startsWith("ERROR jabiz.process.admission.families[nobody.declares].max-queue"))
            .anyMatch(p -> p.startsWith("ERROR jabiz.process.admission.families[nobody.declares].max-wait"))
            .anyMatch(p -> p.startsWith("ERROR jabiz.process.admission.max-active"))
            .anyMatch(p -> p.startsWith("ERROR jabiz.process.admission.max-keys"))
            .anyMatch(p -> p.startsWith("ERROR jabiz.process.admission.max-waiting"))
            .anyMatch(p -> p.startsWith("ERROR jabiz.process.rate-limits.UNKNOWN: names no registered process"))
            .anyMatch(p -> p.startsWith("ERROR jabiz.process.rate-limits.INTERNAL: INTERNAL is INTERNAL"))
            .anyMatch(p -> p.startsWith("ERROR jabiz.process.rate-limits.INTERNAL.per-address"))
            .anyMatch(p -> p.startsWith("ERROR jabiz.process.rate-limits.INTERNAL.period"))
            .anyMatch(p -> p.startsWith("ERROR jabiz.process.rate-limits.SUB: SUB is SUB_PROCESS_ONLY"))
            .anyMatch(p -> p.startsWith("ERROR jabiz.process.rate-limits.SUB: declares none"));
        // The family inherits the defaults' retry-after, reported for both.
        assertThat(problems).hasSize(20);
    }

    @Test
    void anAppProxysProcessIsLimitedPerTenantAndVisitorAndOnlyIt() {
        // Its callers come through the proxy, not the process API: its limits count there (decision D60).
        assertThat(check(AdmissionSettings.DEFAULT, Map.of("PROXIED",
                new ProcessRateLimitProperties.Limit(null, null, null, null, 10, 2)),
            process("PROXIED", pb -> pb.appProxy("shop")))).isEmpty();
        assertThat(messages(check(AdmissionSettings.DEFAULT, Map.of("OPEN",
                new ProcessRateLimitProperties.Limit(null, null, null, null, 10, null)),
            process("OPEN", pb -> { })))).containsExactly("ERROR jabiz.process.rate-limits.OPEN: per-tenant and"
                + " per-visitor count the callers of an app proxy only (decision D60)");
        assertThat(messages(check(AdmissionSettings.DEFAULT, Map.of("PROXIED",
                new ProcessRateLimitProperties.Limit(5, null, null, null, 10, null)),
            process("PROXIED", pb -> pb.appProxy("shop"))))).containsExactly("ERROR jabiz.process.rate-limits.PROXIED:"
                + " per-user does not tell an app proxy's callers apart: use per-tenant and per-visitor (decision D60)");
    }

    @Test
    void settingsMergeOverTheDefaults() {
        AdmissionSettings settings = new AdmissionSettings(null, null, null, null,
            new AdmissionSettings.Family(3, null, null, null),
            Map.of("test.hot", new AdmissionSettings.Family(null, 7, null, null)));
        assertThat(settings.enabled()).isTrue();
        assertThat(settings.limitsOf("test.hot")).isEqualTo(new AdmissionSettings.Family(3, 7,
            AdmissionSettings.DEFAULT_MAX_WAIT, AdmissionSettings.DEFAULT_RETRY_AFTER));
        assertThat(settings.limitsOf("other")).isEqualTo(new AdmissionSettings.Family(3,
            AdmissionSettings.DEFAULT_MAX_QUEUE, AdmissionSettings.DEFAULT_MAX_WAIT,
            AdmissionSettings.DEFAULT_RETRY_AFTER));
        assertThat(settings.maxActiveFor(10)).isEqualTo(5);
        assertThat(settings.maxActiveFor(1)).isEqualTo(1);
        assertThat(new AdmissionSettings(true, 3, null, null, null, null).maxActiveFor(10)).isEqualTo(3);
        assertThat(settings.toString()).contains("test.hot");
    }
}
