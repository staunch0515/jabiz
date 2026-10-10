package com.jabiz.runtime.mail;

import com.jabiz.entity.Violation;
import com.jabiz.event.DomainEvent;
import com.jabiz.event.EventSubscription;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.mail.MailTemplate;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.PermissionDeniedException;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.security.Rbac;
import com.jabiz.runtime.security.SecurityEntities;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Sending template mail and the recipients' choices (docs/design/18-numbering-approvals-tasks.md section 5.6;
 * decision D35). The consumer {@value #CONSUMER} runs {@value #SEND} once for every {@value SendMail#QUEUED} event,
 * always (with mail off it records the message as skipped): delivery, retries with backoff and processing once are the
 * outbox's (decision D14). {@value #PREFERENCE_SET} turns a notification template on or off for a user; it runs only
 * as that user, through the account settings or the unsubscribe link.
 */
@Configuration
public class MailProcesses {

    public static final String SEND = "MAIL_SEND";
    public static final String CONSUMER = "jabiz.mail";
    public static final String PREFERENCE_SET = "SEC_MAIL_PREFERENCE_SET";

    public record SendInput(@NotNull UUID messageId) {}

    /** @param outcome {@code SENT}, {@code SKIPPED}, or {@code DONE} for a message that was done before */
    public record SendOutput(String outcome) {}

    public record PreferenceInput(@NotNull String userId, @NotBlank String template, @NotNull Boolean subscribed) {}

    public record PreferenceOutput(String template, boolean subscribed) {}

    private static final String MESSAGE_ID = "messageId";
    private static final String OUTCOME = "outcome";
    private static final String INPUT = "input";
    private static final String PREFERENCES = "preferences";

    static final ProcessDefinition<SendInput, SendOutput, ProcessContext> SEND_PROCESS =
        ProcessDefinition.define(SEND, 1, SendInput.class, SendOutput.class, ProcessContext.class, pb -> pb
            .description("Sends one queued e-mail message.")
            .permissions(MailPermissions.SEND)
            .internal()
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(MESSAGE_ID, input.messageId());
                return ctx;
            })
            .outputMapper(ctx -> new SendOutput(String.valueOf(ctx.get(OUTCOME))))
            .step("Send the message", DeliverMail.of(MESSAGE_ID, OUTCOME)));

    static ProcessDefinition<PreferenceInput, PreferenceOutput, ProcessContext> preferenceSet(MailTemplates templates) {
        return ProcessDefinition.define(PREFERENCE_SET, 1, PreferenceInput.class, PreferenceOutput.class,
            ProcessContext.class, pb -> pb
                .description("Turns the notifications of a mail template on or off for the user.")
                .permissions(MailPermissions.PREFERENCE)
                .internal()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(ctx -> {
                    PreferenceInput input = ctx.get(INPUT, PreferenceInput.class);
                    return new PreferenceOutput(input.template(), input.subscribed());
                })
                .step("Load the preference", QueryEntities.of(SecurityEntities.USER_MAIL_PREFERENCE_DATASET,
                    ctx -> {
                        PreferenceInput input = ctx.get(INPUT, PreferenceInput.class);
                        return Rbac.all(new QueryPredicate.And(List.of(
                            new QueryPredicate.Eq("userId", input.userId()),
                            new QueryPredicate.Eq("template", input.template()))), "template");
                    }, PREFERENCES))
                .compute("Record the choice", (metadata, ctx) -> setPreference(ctx, templates)));
    }

    @SuppressWarnings("unchecked")
    private static void setPreference(ProcessContext ctx, MailTemplates templates) {
        PreferenceInput input = ctx.get(INPUT, PreferenceInput.class);
        // Like a second factor, a user's mail is the user's own business: an administrator does not unsubscribe them.
        if (!input.userId().equals(ctx.request().actorId())) {
            throw new PermissionDeniedException(MailPermissions.PREFERENCE,
                "Mail preferences are set by their own user only");
        }
        Optional<MailTemplate> template = templates.find(input.template()).filter(MailTemplate::unsubscribable);
        if (template.isEmpty()) {
            ctx.reject(new Violation("template", PlatformErrorCodes.INVALID_VALUE,
                input.template() + " is not a notification template", Map.of("field", "template")));
            return;
        }
        List<EntityInstance> existing = (List<EntityInstance>) ctx.get(PREFERENCES);
        if (existing == null || existing.isEmpty()) {
            if (input.subscribed()) {
                return;
            }
            Map<String, Object> preference = new LinkedHashMap<>();
            preference.put("userId", input.userId());
            preference.put("template", input.template());
            preference.put("subscribed", false);
            ctx.changes().insert(SecurityEntities.USER_MAIL_PREFERENCE, preference);
            return;
        }
        EntityInstance current = existing.getFirst();
        if (!input.subscribed().equals(current.get("subscribed"))) {
            ctx.changes().update(SecurityEntities.USER_MAIL_PREFERENCE, current.id(), current.version(),
                Map.of("subscribed", input.subscribed()));
        }
    }

    @Bean
    ProcessDefinition<SendInput, SendOutput, ProcessContext> mailSendProcess() {
        return SEND_PROCESS;
    }

    @Bean
    ProcessDefinition<PreferenceInput, PreferenceOutput, ProcessContext> mailPreferenceSetProcess(
        MailTemplates templates) {
        return preferenceSet(templates);
    }

    @Bean
    EventSubscription<SendInput> mailSubscription(
        ProcessDefinition<SendInput, SendOutput, ProcessContext> mailSendProcess) {
        return EventSubscription.of(CONSUMER, SendMail.QUEUED, mailSendProcess, MailProcesses::input);
    }

    private static SendInput input(DomainEvent event) {
        return new SendInput(UUID.fromString(String.valueOf(event.payload().get(MESSAGE_ID))));
    }
}
