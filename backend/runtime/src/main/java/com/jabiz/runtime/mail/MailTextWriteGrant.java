package com.jabiz.runtime.mail;

import java.util.Objects;

/**
 * What lets one write of a {@code MailText} pass {@link MailTextGuard} (decision D72): attached by the platform's own
 * mail text processes, for that template and language and that operation only. Only this package can make one, so
 * neither the dataset API nor the generic entity processes nor any other process writes a mail text.
 *
 * @param approved whether the write publishes a controlled change another person approved: only such a write may
 *                 give a template whose changes need approval a text of its own
 */
record MailTextWriteGrant(String template, String locale, long processSeqId, boolean approved) {

    MailTextWriteGrant {
        Objects.requireNonNull(template, "template must not be null");
        Objects.requireNonNull(locale, "locale must not be null");
    }

    boolean allows(Object writtenTemplate, Object writtenLocale, long operationSeqId) {
        return template.equals(writtenTemplate) && locale.equals(writtenLocale) && processSeqId == operationSeqId;
    }
}
