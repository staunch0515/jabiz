package com.jabiz.runtime.task;

import com.jabiz.runtime.check.CheckProblem;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Task declarations and the startup check of e-mail (docs/design/18-numbering-approvals-tasks.md section 5). */
class TaskSpecAndMailChecksTest {

    @Test
    void aTaskIsForAUserOrForAPermission() {
        Instant due = Instant.parse("2026-05-01T00:00:00Z");
        TaskSpec spec = TaskSpec.forPermission("fin.close", "task.close", Map.of("period", "2026-04"), "fin.close")
            .about("FinPeriod", 42).link("/close").due(due).source("close:2026-04");
        assertThat(spec).extracting(TaskSpec::permission, TaskSpec::userId, TaskSpec::subjectId, TaskSpec::link,
            TaskSpec::dueTime, TaskSpec::sourceKey).containsExactly("fin.close", null, "42", "/close", due,
            "close:2026-04");
        assertThat(TaskSpec.forUser("x", "k", null, "u1").titleParams()).isEmpty();

        assertThatThrownBy(() -> new TaskSpec("x", "k", Map.of(), "u", "p", null, null, null, null, null))
            .hasMessageContaining("not both");
        assertThatThrownBy(() -> new TaskSpec("x", "k", Map.of(), null, null, null, null, null, null, null))
            .hasMessageContaining("not both");
        assertThatThrownBy(() -> TaskSpec.forUser("Bad Type", "k", Map.of(), "u")).hasMessageContaining("must match");
        assertThatThrownBy(() -> TaskSpec.forUser("x", " ", Map.of(), "u")).hasMessageContaining("titleKey");
    }

    private static MailChecks checks(boolean enabled, String from, JavaMailSender sender) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        if (sender != null) {
            beans.addBean("mail", sender);
        }
        return new MailChecks(enabled, from, beans.getBeanProvider(JavaMailSender.class));
    }

    @Test
    void mailThatIsOnNeedsAServerAndASender() {
        assertThat(checks(false, "", null).check()).isEmpty();
        assertThat(checks(true, " ", null).check()).extracting(CheckProblem::location)
            .containsExactly("jabiz.mail.from", "spring.mail.host");
        assertThat(checks(true, "jabiz@example.com", new JavaMailSenderImpl()).check()).isEmpty();
    }
}
