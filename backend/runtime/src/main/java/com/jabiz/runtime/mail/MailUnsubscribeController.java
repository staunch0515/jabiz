package com.jabiz.runtime.mail;

import com.jabiz.context.RequestContext;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.mail.MailTemplate;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.BoundValue;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.PermissionDeniedException;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.process.ProcessInputs;
import com.jabiz.runtime.security.JwtService;
import com.jabiz.runtime.storage.Rows;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The recipient's side of template mail (docs/design/18-numbering-approvals-tasks.md section 5.6; decision D35 item
 * 5): the signed unsubscribe link of a notification ({@value #UNSUBSCRIBE}, anonymous: the token is the credential;
 * repeating it changes nothing), and for signed-in users the notification templates they receive and turning them on
 * or off. Both run {@code SEC_MAIL_PREFERENCE_SET} as the user concerned.
 */
@RestController
public class MailUnsubscribeController {

    public static final String UNSUBSCRIBE = "/api/auth/mail/unsubscribe";
    static final String PREFERENCES = "/api/auth/mail/preferences";

    /** @param token the token of the link ({@code ?token=} of the unsubscribe page) */
    public record UnsubscribeRequest(String token) {
        @Override
        public String toString() {
            return "UnsubscribeRequest[***]";
        }
    }

    /** One notification template and whether the user receives it. */
    public record TemplatePreference(String template, boolean subscribed) {}

    /** The user's notification templates; every one is received unless turned off. */
    public record MailPreferences(List<TemplatePreference> templates) {
        public MailPreferences {
            templates = List.copyOf(templates);
        }
    }

    public record PreferenceRequest(String template, Boolean subscribed) {}

    private final ProcessExecutor processes;
    private final ProcessInputs inputs;
    private final ProcessDefinition<MailProcesses.PreferenceInput, MailProcesses.PreferenceOutput, ProcessContext>
        preferenceSet;
    private final JwtService tokens;
    private final MailTemplates templates;
    private final StorageAdapterRegistry storages;
    private final String poolRef;

    MailUnsubscribeController(ProcessExecutor processes, ProcessInputs inputs,
        ProcessDefinition<MailProcesses.PreferenceInput, MailProcesses.PreferenceOutput, ProcessContext> preferenceSet,
        JwtService tokens, MailTemplates templates, StorageAdapterRegistry storages,
        @Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.processes = processes;
        this.inputs = inputs;
        this.preferenceSet = preferenceSet;
        this.tokens = tokens;
        this.templates = templates;
        this.storages = storages;
        this.poolRef = poolRef;
    }

    /** Turns the template of the link off for its user. A forged, altered or foreign token: 422 TOKEN_INVALID. */
    @PostMapping(UNSUBSCRIBE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    Mono<Void> unsubscribe(@RequestBody(required = false) UnsubscribeRequest request) {
        return Mono.defer(() -> {
            if (request == null || request.token() == null || request.token().isBlank()) {
                return Mono.error(tokenInvalid());
            }
            JwtService.Unsubscribe link;
            try {
                link = tokens.verifyUnsubscribe(request.token());
                UUID.fromString(link.userId());
            } catch (JwtService.InvalidTokenException | IllegalArgumentException e) {
                return Mono.error(tokenInvalid());
            }
            if (templates.find(link.template()).filter(MailTemplate::unsubscribable).isEmpty()) {
                return Mono.error(tokenInvalid());
            }
            return asUser(link.userId(), processes.execute(preferenceSet,
                new MailProcesses.PreferenceInput(link.userId(), link.template(), false))).then();
        });
    }

    @GetMapping(PREFERENCES)
    Mono<MailPreferences> preferences() {
        return RequestContexts.current().flatMap(context -> {
            UUID user = requireUser(context);
            List<String> notifications = templates.all().stream().filter(MailTemplate::unsubscribable)
                .map(MailTemplate::name).distinct().sorted().toList();
            return storages.getEngine(poolRef).select("""
                    SELECT DISTINCT ON (template) template, subscribed, is_deleted
                    FROM sec_user_mail_preference_version WHERE user_id = :user
                    ORDER BY template, row_id DESC""", Map.of("user", BoundValue.of(user)))
                .collectList()
                .map(rows -> {
                    Map<String, Boolean> chosen = new HashMap<>();
                    rows.forEach(row -> chosen.put(Rows.string(row.get("template")),
                        Boolean.TRUE.equals(row.get("is_deleted")) || Boolean.TRUE.equals(row.get("subscribed"))));
                    return new MailPreferences(notifications.stream()
                        .map(name -> new TemplatePreference(name, chosen.getOrDefault(name, true))).toList());
                });
        });
    }

    @PostMapping(PREFERENCES)
    Mono<MailProcesses.PreferenceOutput> setPreference(@RequestBody(required = false) PreferenceRequest request) {
        return RequestContexts.current().flatMap(context -> {
            MailProcesses.PreferenceInput input = new MailProcesses.PreferenceInput(requireUser(context).toString(),
                request == null ? null : request.template(), request == null ? null : request.subscribed());
            inputs.validate(input);
            return processes.execute(preferenceSet, input);
        });
    }

    private static BusinessRuleViolationException tokenInvalid() {
        return new BusinessRuleViolationException(new Violation(null, PlatformErrorCodes.TOKEN_INVALID,
            "Invalid unsubscribe token"));
    }

    /** Only users of the platform receive template mail; development header actors are not users. */
    private static UUID requireUser(RequestContext context) {
        try {
            return UUID.fromString(context.actorId());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new PermissionDeniedException(MailPermissions.PREFERENCE, "Only users of the platform have mail "
                + "preferences");
        }
    }

    /** Runs work as the user a link names: the operation record shows who unsubscribed. */
    private static <T> Mono<T> asUser(String userId, Mono<T> work) {
        return RequestContexts.current().flatMap(started -> work.contextWrite(view -> RequestContexts.put(view,
            new RequestContext(userId, null, started.locale(), started.requestId(), Set.of(), Set.of()))));
    }
}
