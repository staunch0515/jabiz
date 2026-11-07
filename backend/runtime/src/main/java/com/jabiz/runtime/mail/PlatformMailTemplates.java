package com.jabiz.runtime.mail;

import com.jabiz.mail.MailCategory;
import com.jabiz.mail.MailTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

/**
 * The platform's mail that is not template mail sent by {@code SendMail}, declared as templates all the same so that
 * its texts are read where every mail's are ({@link MailTexts}) and administrators can change them (decision D72: "all
 * mail"):
 * <ul>
 *   <li>{@value #TASK_NOTIFICATION}: the notification of a new task ({@code TASK_NOTIFY},
 *       docs/design/18-numbering-approvals-tasks.md section 5.4), with the task's title ({@code {title}}) and the
 *       address of the task ({@code {taskUrl}}, empty for a task without a link), in the platform's default
 *       language;</li>
 *   <li>{@value #DOCUMENT_MAIL}: a document sent by {@code DOCUMENT_SEND} (docs/design/22-documents.md section 5),
 *       with its title, number and the issuing company; a layout's messages {@code document.<layout>.mail.*} are its
 *       default for that layout, an override of the template's texts applies to every layout.</li>
 * </ul>
 * Both are transactional (nobody unsubscribes from them) and carry no token. They are sent as plain text, as before.
 */
@Configuration
public class PlatformMailTemplates {

    public static final String TASK_NOTIFICATION = "jabiz.task-notification";
    public static final String DOCUMENT_MAIL = "jabiz.document-mail";

    /** The names, which the startup check accepts in the platform's namespace. */
    public static final Set<String> NAMES = Set.of(TASK_NOTIFICATION, DOCUMENT_MAIL);

    public static final MailTemplate TASK_NOTIFICATION_MAIL = MailTemplate.define(TASK_NOTIFICATION, t -> t
        .category(MailCategory.TRANSACTIONAL).param("title", "taskUrl")
        .sample("title", "Approve purchase order PO-2026-0001")
        .sample("taskUrl", "https://app.example.com/tasks"));

    public static final MailTemplate DOCUMENT_MAIL_TEMPLATE = MailTemplate.define(DOCUMENT_MAIL, t -> t
        .category(MailCategory.TRANSACTIONAL).param("title", "number", "company")
        .sample("title", "Invoice").sample("number", "INV-2026-0001").sample("company", "Example Ltd."));

    @Bean
    MailTemplate taskNotificationMail() {
        return TASK_NOTIFICATION_MAIL;
    }

    @Bean
    MailTemplate documentMail() {
        return DOCUMENT_MAIL_TEMPLATE;
    }
}
