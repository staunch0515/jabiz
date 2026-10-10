package com.jabiz.runtime.task;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.time.Duration;
import java.util.List;
import java.util.Properties;

/**
 * Connect, read and write timeouts of the mail server ({@code jabiz.mail.timeout}, 10 seconds), set on every
 * {@link JavaMailSenderImpl} when the bean is created: its session is made from these properties once, by whatever
 * uses it first (a send, or the mail health check), so they cannot be added later. {@code spring.mail.properties} set
 * explicitly win. Template mail is sent inside a delivery's transaction, which a hanging server must not hold for long
 * (docs/design/18-numbering-approvals-tasks.md section 5.6).
 */
public class MailSenderTimeouts implements BeanPostProcessor {

    /** The JavaMail properties set, for SMTP and SMTPS. */
    public static final List<String> PROPERTIES = List.of("connectiontimeout", "timeout", "writetimeout").stream()
        .flatMap(name -> List.of("mail.smtp." + name, "mail.smtps." + name).stream()).toList();

    private final String timeoutMillis;

    public MailSenderTimeouts(Duration timeout) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("jabiz.mail.timeout must be positive");
        }
        this.timeoutMillis = String.valueOf(timeout.toMillis());
    }

    @Configuration
    static class Registration {

        /** Static: a post-processor is created before the other beans, from the environment only. */
        @Bean
        static MailSenderTimeouts mailSenderTimeouts(Environment environment) {
            return new MailSenderTimeouts(DurationStyle.detectAndParse(
                environment.getProperty("jabiz.mail.timeout", "10s")));
        }
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof JavaMailSenderImpl sender) {
            Properties properties = sender.getJavaMailProperties();
            PROPERTIES.forEach(name -> properties.putIfAbsent(name, timeoutMillis));
        }
        return bean;
    }
}
