package com.jabiz.app.it.security;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetup;
import com.jabiz.context.RequestContext;
import com.jabiz.job.JobDefinition;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.firstlaunch.FirstLaunchRun;
import com.jabiz.runtime.firstlaunch.FirstLaunchRuns;
import com.jabiz.runtime.firstlaunch.accounts.FirstLaunchWaiting;
import com.jabiz.runtime.firstlaunch.accounts.WaitingRole;
import com.jabiz.runtime.job.JobRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The first launch's deadline (plan F-LAUNCH section 2.1): once {@code jabiz.first-launch.deadline} has passed since the
 * content phase, the run ends {@code EXPIRED}, the waiting role is disabled for good - so an account that kept only it
 * no longer signs in - and the report lists what is left.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "jabiz.first-launch.await-interval=50ms", "jabiz.jobs.lock-at-least-for=0s", "jabiz.first-launch.deadline=PT2H",
    "jabiz.mail.enabled=true", "jabiz.mail.from=jabiz@example.com", "jabiz.mail.base-url=https://app.example.com",
    "spring.mail.host=localhost", "spring.mail.port=3045",
    "jabiz.security.entries.admin.accepted-roles=IT_STAFF,IT_SUPER," + WaitingRole.CODE})
@Import({SignUpItSupport.FixedTimeLimits.class, FirstLaunchWaitingIT.Handler.class})
class FirstLaunchExpiryIT extends SignUpItSupport {

    @RegisterExtension
    static final GreenMailExtension MAIL = new GreenMailExtension(new ServerSetup(3045, null, ServerSetup.PROTOCOL_SMTP));

    private static final String A = "fl-x-a@example.com";
    private static final String B = "fl-x-b@example.com";

    @DynamicPropertySource
    static void firstLaunch(DynamicPropertyRegistry registry) {
        String path = FirstLaunchWaitingIT.configFileIn(FirstLaunchWaitingIT.config(A, B));
        registry.add("jabiz.first-launch.config", () -> path);
    }

    @Autowired
    FirstLaunchRuns runs;

    @Autowired
    JobRunner jobs;

    @Autowired
    @Qualifier("firstLaunchJob")
    JobDefinition<FirstLaunchWaiting.AdvanceInput> job;

    @Override
    GreenMailExtension mail() {
        return MAIL;
    }

    @Override
    void customerRole() {
    }

    private FirstLaunchRun run() {
        return runs.current().contextWrite(view -> RequestContexts.put(view,
            RequestContext.system(Locale.ENGLISH, "it"))).block().orElseThrow();
    }

    private void at(Instant scheduled) {
        clock.set(scheduled.plusSeconds(1));
        assertThat(jobs.run(job, scheduled)).isEqualTo(JobRunner.Outcome.SUCCEEDED);
        deliver();
    }

    @Test
    void pastTheDeadlineTheRunExpiresAndTheWaitingRoleIsDisabled() throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (runs.current().contextWrite(view -> RequestContexts.put(view,
                RequestContext.system(Locale.ENGLISH, "it"))).block()
            .map(found -> found.state() == FirstLaunchRun.State.RUNNING).orElse(true)) {
            assertThat(System.nanoTime()).isLessThan(end);
            Thread.sleep(50);
        }
        Instant done = run().stateTime();
        Instant first = done.plus(Duration.ofMinutes(1)).truncatedTo(ChronoUnit.MINUTES);
        at(first);
        // B accepts but binds no second factor; nobody else does anything.
        anonymous("/api/auth/invitation/confirm", Map.of("token", token(InvitationIT.INVITE_LINK, B),
            "password", "own horse battery")).expectStatus().isNoContent();
        assertThat(session(B, "own horse battery", null)).containsEntry("status", "MFA_ENROLLMENT_REQUIRED");
        at(first.plus(Duration.ofMinutes(30)));
        assertThat(run().state()).isEqualTo(FirstLaunchRun.State.CONTENT_DONE);

        at(done.plus(Duration.ofHours(2)).truncatedTo(ChronoUnit.MINUTES).plus(Duration.ofMinutes(1)));
        assertThat(run().state()).isEqualTo(FirstLaunchRun.State.EXPIRED);
        assertThat(query("SELECT enabled FROM sec_role_version WHERE role_code = ? ORDER BY version_no DESC LIMIT 1",
            WaitingRole.CODE).getFirst()).containsEntry("enabled", false);
        assertThat(text(to(A).getLast())).contains("deadline has passed").contains("#1 has no configured role")
            .contains("#2 has no configured role");
        // The waiting role is disabled: an account that held only it no longer signs in.
        assertThat(refusal(loginTo(B, "own horse battery", null), 401)).isEqualTo("LOGIN_FAILED");
    }
}
