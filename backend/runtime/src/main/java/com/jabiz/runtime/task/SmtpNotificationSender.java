package com.jabiz.runtime.task;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * Sends notifications and documents as plain-text e-mail, documents with their PDF attached, through Spring's {@link JavaMailSender} ({@code spring.mail.*}) from
 * {@code jabiz.mail.from}. {@link MailChecks} refuses to start with mail on but either missing.
 */
@Component
public class SmtpNotificationSender implements NotificationSender {

    private final ObjectProvider<JavaMailSender> mail;
    private final String from;

    SmtpNotificationSender(ObjectProvider<JavaMailSender> mail, @Value("${jabiz.mail.from:}") String from) {
        this.mail = mail;
        this.from = from;
    }

    @Override
    public void send(String to, String subject, String body) {
        JavaMailSender sender = mail.getIfAvailable();
        if (sender == null) {
            throw new IllegalStateException("No mail server is configured (spring.mail.host)");
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);
        sender.send(message);
    }

    /** Plain text with the attachments as parts of their own, in UTF-8. */
    @Override
    public void send(MailMessage message) throws MessagingException {
        JavaMailSender sender = mail.getIfAvailable();
        if (sender == null) {
            throw new IllegalStateException("No mail server is configured (spring.mail.host)");
        }
        MimeMessage mime = sender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(mime, !message.attachments().isEmpty(), "UTF-8");
        helper.setFrom(from);
        helper.setTo(message.to());
        helper.setSubject(message.subject());
        helper.setText(message.body(), false);
        for (MailMessage.Attachment attachment : message.attachments()) {
            helper.addAttachment(attachment.fileName(), new ByteArrayResource(attachment.content()),
                attachment.contentType());
        }
        sender.send(mime);
    }
}
