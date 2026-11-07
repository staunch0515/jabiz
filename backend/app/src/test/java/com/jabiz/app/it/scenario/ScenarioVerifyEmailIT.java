package com.jabiz.app.it.scenario;

import com.jabiz.app.App;
import com.jabiz.runtime.audit.AuditService;
import com.jabiz.runtime.test.scenario.Scenario;
import com.jabiz.runtime.test.scenario.ScenarioContexts;
import com.jabiz.runtime.test.scenario.ScenarioReplay;
import com.jabiz.runtime.test.scenario.ScenarioRunner;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The replay step {@code verifyEmail} (ROADMAP phase 17zp; docs/design/07-quality.md section 3.1) writes through the
 * platform's write path: the user's new version has an audit record like any other write, by the system, with the
 * address and the time it proved.
 */
class ScenarioVerifyEmailIT {

    private static final String SCENARIO = "scenarios/security/verify_email.yml";

    @Test
    void verifyingAnAddressInReplayLeavesAnAuditRecord() {
        Scenario scenario = ScenarioReplay.load(SCENARIO);
        try (ScenarioContexts.Started started = ScenarioContexts.start(App.class, scenario.properties())) {
            ScenarioRunner.Result result = new ScenarioRunner(started.context()).run(scenario);
            String hana = String.valueOf(result.variables().get("hana"));

            List<AuditService.AuditRecordEntry> records = started.context().getBean(AuditService.class)
                .records(new AuditService.RecordQuery("SecUser", hana, null, null, null, "SCENARIO_EMAIL_VERIFY",
                    "verifiedEmail", false, 0, 10))
                .block().items();

            assertThat(records).singleElement().satisfies(record -> {
                assertThat(record.actorId()).isEqualTo("system");
                assertThat(record.versionNo()).isEqualTo(2L);
                assertThat(record.changes().get("verifiedEmail"))
                    .isEqualTo(new AuditService.AuditFieldChange(null, "hana@example.com"));
                assertThat(record.changes()).containsKey("emailVerifiedAt");
                assertThat(record.recordedTime()).isEqualTo(Instant.parse("2026-05-01T01:00:00Z"));
            });
        }
    }
}
