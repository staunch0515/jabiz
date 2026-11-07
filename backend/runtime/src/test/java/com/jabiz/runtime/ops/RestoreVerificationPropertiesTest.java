package com.jabiz.runtime.ops;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** A verification run of a restored copy turns off everything that acts on the outside world (ROADMAP 17zv). */
class RestoreVerificationPropertiesTest {

    private static StandardEnvironment environment(Map<String, Object> properties) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("production", properties));
        return environment;
    }

    @Test
    void overridesWhatProductionTurnedOn() {
        StandardEnvironment environment = environment(Map.of("jabiz.ops.verify-restore.enabled", "true",
            "jabiz.mail.enabled", "true", "jabiz.push.enabled", "true", "jabiz.jobs.scheduler.enabled", "true",
            "jabiz.events.delivery.enabled", "true", "jabiz.external-jobs.executor.enabled", "true",
            "server.port", "8080"));
        new RestoreVerificationProperties().postProcessEnvironment(environment, null);
        for (String off : new String[] {"jabiz.mail.enabled", "jabiz.push.enabled", "jabiz.jobs.scheduler.enabled",
            "jabiz.events.delivery.enabled", "jabiz.external-jobs.executor.enabled"}) {
            assertThat(environment.getProperty(off)).as(off).isEqualTo("false");
        }
        assertThat(environment.getProperty("server.address")).isEqualTo("127.0.0.1");
        assertThat(environment.getProperty("server.port")).isEqualTo("0");
    }

    @Test
    void changesNothingOtherwise() {
        StandardEnvironment environment = environment(Map.of("jabiz.mail.enabled", "true"));
        new RestoreVerificationProperties().postProcessEnvironment(environment, null);
        assertThat(environment.getProperty("jabiz.mail.enabled")).isEqualTo("true");
        assertThat(environment.getPropertySources().contains(RestoreVerificationProperties.SOURCE)).isFalse();
    }
}
