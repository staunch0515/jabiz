package com.jabiz.runtime.task;

import com.jabiz.mail.MailSenderSettings;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Properties;

/**
 * Sends through a mail server named by its settings rather than the platform's (decision D56): a
 * {@code JavaMailSenderImpl} made for the one send, with the platform's timeouts ({@link MailSenderTimeouts}),
 * authentication and TLS as the settings say (SMTPS, or STARTTLS required), the server's certificate checked against
 * its host name. Never falls back to the platform's server. Blocking: the delivery calls it on {@code boundedElastic};
 * an application sends its test mail through it from a {@code BlockingStep}.
 *
 * <p>{@code jabiz.mail.senders.trust-all-for-tests=true} (tests only, a warning elsewhere) accepts any certificate
 * and a local host, so that a test can run a mail server of its own.
 */
@Component
public class MailSenders {

    private final MailSenderTimeouts timeouts;
    private final boolean trustAllForTests;

    public MailSenders(MailSenderTimeouts timeouts,
        @Value("${jabiz.mail.senders.trust-all-for-tests:false}") boolean trustAllForTests) {
        this.timeouts = timeouts;
        this.trustAllForTests = trustAllForTests;
    }

    /** Whether a server on this machine, on any port and with any certificate, is accepted (tests only). */
    public boolean lenient() {
        return trustAllForTests;
    }

    /** Why the platform will not send through the settings ({@link MailSenderSettings#problems(boolean)}). */
    public List<String> problems(MailSenderSettings settings) {
        return settings.problems(trustAllForTests);
    }

    /**
     * Sends one message through the server, from the settings' address and name.
     *
     * @param password the opened password
     * @throws IllegalArgumentException when the settings have {@link #problems}
     * @throws Exception                what the server or the connection answered: recorded with the attempt
     */
    public void send(MailSenderSettings settings, String password, MailMessage message) throws Exception {
        List<String> problems = problems(settings);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("The mail server settings are refused: " + String.join("; ", problems));
        }
        org.springframework.mail.javamail.JavaMailSenderImpl sender =
            new org.springframework.mail.javamail.JavaMailSenderImpl();
        sender.setHost(settings.host());
        sender.setPort(settings.port());
        sender.setUsername(settings.username());
        sender.setPassword(password);
        sender.setDefaultEncoding("UTF-8");
        Properties properties = sender.getJavaMailProperties();
        // The test switch trusts any certificate of a server on this machine only: set in production by mistake, it
        // cannot weaken the check of a merchant's server.
        boolean trustAll = trustAllForTests && settings.onThisMachine();
        if (settings.tls() == MailSenderSettings.Tls.SMTPS) {
            sender.setProtocol("smtps");
            properties.put("mail.smtps.auth", "true");
            properties.put("mail.smtps.ssl.checkserveridentity", trustAll ? "false" : "true");
            if (trustAll) {
                properties.put("mail.smtps.ssl.trust", "*");
            }
        } else {
            sender.setProtocol("smtp");
            properties.put("mail.smtp.auth", "true");
            properties.put("mail.smtp.starttls.enable", "true");
            properties.put("mail.smtp.starttls.required", "true");
            properties.put("mail.smtp.ssl.checkserveridentity", trustAll ? "false" : "true");
            if (trustAll) {
                properties.put("mail.smtp.ssl.trust", "*");
            }
        }
        timeouts.apply(sender);
        // The session is made here, on whatever thread sends (boundedElastic): Jakarta Mail finds its providers through
        // the context class loader, which a pooled thread may not carry.
        Thread thread = Thread.currentThread();
        ClassLoader before = thread.getContextClassLoader();
        thread.setContextClassLoader(MailSenders.class.getClassLoader());
        try {
            SmtpNotificationSender.send(sender, settings.fromAddress(), settings.fromName(), message);
        } finally {
            thread.setContextClassLoader(before);
        }
    }
}
