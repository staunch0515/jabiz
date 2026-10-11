package com.jabiz.runtime.process.steps;

import com.jabiz.security.Sensitive;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** An input holds a secret when a value of an {@code @Sensitive} component is set, wherever it is, whatever its name. */
class StartExternalJobTest {

    record Credentials(@Sensitive String apiKey, String user) {}

    record Prompt(String text, Integer maxTokens, Object detail, List<Object> parts, Map<String, Object> extra,
        Object[] more) {}

    @Test
    void namesThatOnlySoundSecretAreFine() {
        assertThat(StartExternalJob.sensitivePath(new Prompt("hi", 512, null, List.of(), Map.of(), null), "", 0))
            .isEmpty();
        assertThat(StartExternalJob.sensitivePath(new Prompt("hi", 1, new Credentials(null, "u"), null, null, null),
            "", 0)).isEmpty();
    }

    @Test
    void sensitiveValuesAreFoundAtAnyDepth() {
        assertThat(StartExternalJob.sensitivePath(new Prompt("hi", 1, new Credentials("k", "u"), null, null, null),
            "", 0)).contains("detail.apiKey");
        assertThat(StartExternalJob.sensitivePath(new Prompt("hi", 1, null, List.of("a", new Credentials("k", "u")),
            null, null), "", 0)).contains("parts[1].apiKey");
        assertThat(StartExternalJob.sensitivePath(new Prompt("hi", 1, null, null, Map.of("auth",
            new Credentials("k", "u")), null), "", 0)).contains("extra[auth].apiKey");
        assertThat(StartExternalJob.sensitivePath(new Prompt("hi", 1, null, null, null,
            new Object[] {new Credentials("k", null)}), "", 0)).contains("more[0].apiKey");
        assertThat(StartExternalJob.sensitivePath(new Credentials("k", "u"), "", 0)).contains("apiKey");
    }
}
