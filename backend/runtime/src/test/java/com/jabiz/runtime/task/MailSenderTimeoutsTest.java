package com.jabiz.runtime.task;

import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The mail server's timeouts are on the sender from its creation, before any session is made. */
class MailSenderTimeoutsTest {

    @Test
    void setsTheTimeoutsWhenTheSenderIsCreatedKeepingExplicitOnes() {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.getJavaMailProperties().put("mail.smtp.timeout", "2500");

        Object processed = new MailSenderTimeouts(Duration.ofSeconds(10)).postProcessAfterInitialization(sender, "m");

        assertThat(processed).isSameAs(sender);
        assertThat(sender.getJavaMailProperties()).containsEntry("mail.smtp.connectiontimeout", "10000")
            .containsEntry("mail.smtp.writetimeout", "10000").containsEntry("mail.smtps.timeout", "10000")
            .containsEntry("mail.smtp.timeout", "2500");
        // The session made from them (by the first send or the health check) has them.
        assertThat(sender.getSession().getProperty("mail.smtp.connectiontimeout")).isEqualTo("10000");
        assertThat(new MailSenderTimeouts(Duration.ofSeconds(1)).postProcessAfterInitialization("other", "x"))
            .isEqualTo("other");
        assertThatThrownBy(() -> new MailSenderTimeouts(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }
}
