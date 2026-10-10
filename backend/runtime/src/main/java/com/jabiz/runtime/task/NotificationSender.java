package com.jabiz.runtime.task;

/**
 * Sends one notification (docs/design/18-numbering-approvals-tasks.md section 5.4) or one document
 * (docs/design/22-documents.md section 5). Blocking: the platform calls it on {@code boundedElastic}, after the
 * commit. The default is {@link SmtpNotificationSender}.
 */
public interface NotificationSender {

    /** @throws Exception when the message was not accepted; the attempt is recorded and retried */
    void send(String to, String subject, String body) throws Exception;

    /**
     * A message that may carry attachments and an HTML part. A sender that cannot attach files refuses such a message
     * rather than send it without them; one that cannot send HTML sends the plain text, which has the same content.
     *
     * @throws Exception when the message was not accepted; the attempt is recorded and retried
     */
    default void send(MailMessage message) throws Exception {
        if (!message.attachments().isEmpty()) {
            throw new UnsupportedOperationException(getClass().getName() + " cannot send attachments");
        }
        send(message.to(), message.subject(), message.body());
    }
}
