package com.jabiz.runtime.task;

import java.util.List;
import java.util.Objects;

/**
 * One e-mail to one address (docs/design/18-numbering-approvals-tasks.md section 5.4, docs/design/22-documents.md
 * section 5): plain text, optionally with files attached.
 */
public record MailMessage(String to, String subject, String body, List<Attachment> attachments) {

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
}
