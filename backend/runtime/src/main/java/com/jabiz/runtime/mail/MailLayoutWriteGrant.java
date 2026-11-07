package com.jabiz.runtime.mail;

import java.util.Objects;

/**
 * What lets one write of a {@code MailLayout} pass {@link MailLayoutGuard} (decision D72): attached by the platform's
 * own mail layout processes, for that language and that operation only. Only this package can make one.
 *
 * @param approved whether the write publishes a controlled change another person approved: only such a write may give
 *                 a language a layout of its own
 */
record MailLayoutWriteGrant(String locale, long processSeqId, boolean approved) {

    MailLayoutWriteGrant {
        Objects.requireNonNull(locale, "locale must not be null");
    }

    boolean allows(Object writtenLocale, long operationSeqId) {
        return locale.equals(writtenLocale) && processSeqId == operationSeqId;
    }
}
