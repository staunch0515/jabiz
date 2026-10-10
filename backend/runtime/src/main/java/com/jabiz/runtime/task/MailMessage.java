package com.jabiz.runtime.task;

import java.util.List;
import java.util.Objects;

/**
 * One e-mail to one address (docs/design/18-numbering-approvals-tasks.md sections 5.4 and 5.6,
 * docs/design/22-documents.md section 5): plain text, optionally with an HTML alternative of the same content
 * (rendered mail templates) and with files attached.
 *
 * @param htmlBody the same content as HTML, or null for plain text only
 */
public record MailMessage(String to, String subject, String body, String htmlBody, List<Attachment> attachments) {

    /** A file attached as it is. */
    public record Attachment(String fileName, String contentType, byte[] content) {
        public Attachment {
            Objects.requireNonNull(fileName, "fileName must not be null");
            Objects.requireNonNull(contentType, "contentType must not be null");
            Objects.requireNonNull(content, "content must not be null");
        }
    }

    public MailMessage {
        Objects.requireNonNull(to, "to must not be null");
        Objects.requireNonNull(subject, "subject must not be null");
        Objects.requireNonNull(body, "body must not be null");
        attachments = List.copyOf(attachments);
    }

    /** Plain text only. */
    public MailMessage(String to, String subject, String body, List<Attachment> attachments) {
        this(to, subject, body, null, attachments);
    }
}
