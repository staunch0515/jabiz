package com.jabiz.quizbuks.mailserver;

import com.jabiz.security.SealedField;
import com.jabiz.security.SealedFields;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The sealed password of a sender's mail server (platform decision D78), so that a new {@code JABIZ_SEAL_KEY} can take
 * over: {@link MailServerProcesses#set} seals it for the sender key the row keeps as {@code sponsorId} (the sponsor's
 * id, or {@code prospect:<owner>}).
 */
@Component
public class MailServerSealedFields implements SealedFields {

    @Override
    public List<SealedField> fields() {
        return List.of(SealedField.of(QbMailServers.ENTITY, "sealedPassword", "sponsorId"));
    }
}
