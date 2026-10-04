package com.jabiz.runtime.task;

import com.jabiz.i18n.MessageCatalog;
import com.jabiz.i18n.MessageTemplate;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The title of a task in a language (docs/design/18-numbering-approvals-tasks.md section 5): its message with its
 * params; an approval task names its subject by the subject's label ({@code approval.subject.<name>}) where the
 * messages have one. The task list and the notification mail title tasks alike.
 */
public final class TaskTitles {

    /** Type of the tasks of approval requests. */
    public static final String APPROVAL_TYPE = "approval";

    private TaskTitles() {}

    public static Map<String, String> params(String type, Map<String, String> params, MessageCatalog messages,
        Locale locale) {
        String subject = params.get("subject");
        if (!APPROVAL_TYPE.equals(type) || subject == null) {
            return params;
        }
        return messages.find("approval.subject." + subject, locale).map(label -> {
            Map<String, String> named = new HashMap<>(params);
            named.put("subject", label);
            return Map.copyOf(named);
        }).orElse(params);
    }

    public static String title(String type, String key, Map<String, String> params, MessageCatalog messages,
        Locale locale) {
        Map<String, String> named = params(type, params, messages, locale);
        return messages.find(key, locale).map(text -> MessageTemplate.format(text, named)).orElse(key);
    }
}
