package com.jabiz.runtime.task;

/**
 * Sends one notification (docs/design/18-numbering-approvals-tasks.md section 5.4). Blocking: the platform calls it
 * on {@code boundedElastic}, after the commit. The default is {@link SmtpNotificationSender}.
 */
public interface NotificationSender {

    /** @throws Exception when the message was not accepted; the attempt is recorded and retried */
    void send(String to, String subject, String body) throws Exception;
}
