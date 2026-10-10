package com.jabiz.runtime.task;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Properties;

/**
 * Sends notifications, documents and template mail through Spring's {@link JavaMailSender} ({@code spring.mail.*})
 * from {@code jabiz.mail.from}: plain text, template mail as {@code multipart/alternative} with its HTML part,
 * documents with their PDF attached. {@code MailChecks} refuses to start with mail on but either missing. Unless
 * {@code spring.mail.properties} say otherwise, connecting, reading and writing time out after
 * {@code jabiz.mail.timeout} (10 seconds): template mail is sent inside a delivery's transaction, which a hanging
 * server must not hold for long.
 */
@Component
public class SmtpNotificationSender implements NotificationSender {

    private static final List<String> TIMEOUTS = List.of("connectiontimeout", "timeout", "writetimeout");

    private final ObjectProvider<JavaMailSender> mail;
    private final String from;
    private final String timeoutMillis;

    SmtpNotificationSender(ObjectProvider<JavaMailSender> mail, @Value("${jabiz.mail.from:}") String from,
        @Value("${jabiz.mail.timeout:10s}") Duration timeout) {
        this.mail = mail;
        this.from = from;
        this.timeoutMillis = String.valueOf(timeout.toMillis());
    }

    private JavaMailSender sender() {
        JavaMailSender sender = mail.getIfAvailable();
        if (sender == null) {
            throw new IllegalStateException("No mail server is configured (spring.mail.host)");
        }
        // Before the first message, which creates the session from these properties.
        if (sender instanceof JavaMailSenderImpl impl) {
            Properties properties = impl.getJavaMailProperties();
            for (String protocol : List.of("smtp", "smtps")) {
                for (String timeout : TIMEOUTS) {
                    properties.putIfAbsent("mail." + protocol + "." + timeout, timeoutMillis);
                }
            }
        }
        return sender;
    }

    @Override
    public void send(String to, String subject, String body) {
        JavaMailSender sender = sender();
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);
        sender.send(message);
    }

    /** Plain text, with its HTML alternative and the attachments as parts of their own, in UTF-8. */
    @Override
    public void send(MailMessage message) throws MessagingException {
        JavaMailSender sender = sender();
        MimeMessage mime = sender.createMimeMessage();
        boolean multipart = !message.attachments().isEmpty() || message.htmlBody() != null;
        MimeMessageHelper helper = new MimeMessageHelper(mime, multipart, "UTF-8");
        helper.setFrom(from);
        helper.setTo(message.to());
        helper.setSubject(message.subject());
        if (message.htmlBody() != null) {
            helper.setText(message.body(), message.htmlBody());
        } else {
            helper.setText(message.body(), false);
        }
        for (MailMessage.Attachment attachment : message.attachments()) {
            helper.addAttachment(attachment.fileName(), new ByteArrayResource(attachment.content()),
                attachment.contentType());
        }
        sender.send(mime);
    }
}
