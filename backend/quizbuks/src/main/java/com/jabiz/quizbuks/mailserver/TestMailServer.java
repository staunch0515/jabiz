package com.jabiz.quizbuks.mailserver;

import com.jabiz.entity.Violation;
import com.jabiz.i18n.MessageCatalog;
import com.jabiz.mail.MailSenderSettings;
import com.jabiz.process.BlockingStep;
import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessContext;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.security.secret.SecretSealer;
import com.jabiz.runtime.task.MailMessage;
import com.jabiz.runtime.task.MailSenders;
import com.jabiz.security.SealedSecrets;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Sends a test mail through the settings a sponsor is saving ({@code QB_MAIL_SERVER_SET}), to the sponsor's own
 * address, the way invitations will go (platform {@code MailSenders}, 18 section 5.6): settings the platform refuses
 * are violations on their fields; a server that refuses or cannot be reached leaves its answer in the context, and the
 * row is saved unverified with it. Repeating the mail is harmless, so the step runs in the transaction.
 */
@Component
public class TestMailServer implements BlockingStep<NoMetadata, ProcessContext> {

    static final String SUBJECT = "qb.mail-server.test.subject";
    static final String BODY = "qb.mail-server.test.body";

    private final MailSenders senders;
    private final SecretSealer sealer;
    private final MessageCatalog messages;

    public TestMailServer(MailSenders senders, SecretSealer sealer, MessageCatalog messages) {
        this.senders = senders;
        this.sealer = sealer;
        this.messages = messages;
    }

    @Override
    public void run(NoMetadata metadata, ProcessContext ctx) {
        if (ctx.hasViolations()) {
            return;
        }
        MailServerProcesses.SetInput input = ctx.get(MailServerProcesses.INPUT, MailServerProcesses.SetInput.class);
        String sponsorId = String.valueOf((Object) ctx.get(MailServerProcesses.SPONSOR_ID));
        String sealed = MailServerProcesses.sealed(ctx);
        MailSenderSettings settings = new MailSenderSettings(input.host(), input.port(), QbMailServers.tls(input.tls()),
            input.username(), sealed, input.fromAddress(), input.fromName());
        List<String> problems = senders.problems(settings);
        if (!problems.isEmpty()) {
            for (String problem : problems) {
                ctx.reject(new Violation(QbMailServers.fieldOf(problem), MailServerCodes.INVALID, problem,
                    Map.of("detail", problem)));
            }
            return;
        }
        String address = MailServerProcesses.address(ctx);
        if (address == null) {
            ctx.reject(new Violation(null, MailServerCodes.NO_ADDRESS, "The account has no address to send the test "
                + "mail to", Map.of()));
            return;
        }
        Locale locale = messages.supported(ctx.request().locale());
        String subject = text(SUBJECT, locale, settings.host());
        String body = text(BODY, locale, settings.host());
        // The password as typed, else the stored one opened for this sponsor's row: one sealed under another key (the
        // sealing key was changed) cannot be opened, and the sponsor types it again.
        String password;
        try {
            password = input.password() != null && !input.password().isBlank() ? input.password()
                : sealer.open(sealed, sponsorId);
        } catch (SealedSecrets.UnreadableSecretException e) {
            ctx.reject(new Violation("password", MailServerCodes.PASSWORD, "The stored password cannot be opened: "
                + "enter it again", Map.of()));
            return;
        }
        try {
            senders.send(settings, password, new MailMessage(address, subject, body, null, List.of()));
            ctx.put(MailServerProcesses.SENT_TO, address);
        } catch (Exception e) {
            ctx.put(MailServerProcesses.ERROR, QbMailServers.reason(e));
        }
    }

    private String text(String code, Locale locale, String host) {
        return messages.find(code, locale).orElse(code).replace("{host}", host);
    }

    /** For tests: the row loaded into the context, if any. */
    static EntityInstance existing(ProcessContext ctx) {
        return MailServerProcesses.existing(ctx);
    }
}
