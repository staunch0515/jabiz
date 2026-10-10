package com.jabiz.runtime.mail;

import com.jabiz.entity.Violation;
import com.jabiz.i18n.MessageCatalog;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.mail.MailRecipient;
import com.jabiz.mail.MailTemplate;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.StepSpec;
import com.jabiz.query.BoundValue;
import com.jabiz.runtime.process.StepHandler;
import com.jabiz.runtime.process.steps.CheckedStep;
import com.jabiz.runtime.process.steps.EventPublisher;
import com.jabiz.runtime.storage.Rows;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import com.jabiz.security.Sensitive;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Sends a mail from a process (docs/design/18-numbering-approvals-tasks.md section 5.6; decision D35 item 2):
 * <pre>{@code
 * .step("Tell the customer", SendMail.of(ORDER_SHIPPED, ctx -> MailRecipient.user(customer(ctx)),
 *     Map.of("orderNo", ctx -> order(ctx).get("orderNo"))))
 * }</pre>
 * In the process's transaction it records a {@code MailMessage} (template, recipient, language and the parameters as
 * text, which the mail is rendered from) and publishes {@value #QUEUED}; the platform's consumer sends it after the
 * commit, retried through the outbox. A rolled back process sends nothing. A user without an address is a violation
 * ({@code MAIL_NO_ADDRESS}), which a process avoids with {@link #when}. Notifications go to users only.
 *
 * <p>A mail carries no secret but its one-time tokens, drawn when it is sent: parameters are stored as they are, so
 * a parameter named like a secret or a masked field is refused at startup ({@code MailChecks}) and an {@code @Sensitive}
 * value with {@code MAIL_PARAM_SECRET}; parameters longer than {@value #MAX_PARAMS} characters as JSON are refused
 * ({@code MAIL_PARAMS_TOO_LARGE}), never cut.
 */
@Component
public class SendMail<C extends ProcessContext> implements StepHandler<SendMail.Metadata<C>, C>,
    CheckedStep<SendMail.Metadata<C>> {

    /** Longest parameters, as JSON. */
    public static final int MAX_PARAMS = 16 * 1024;

    /** Published for every recorded message; the payload names it. */
    public static final String QUEUED = "jabiz.mail.queued";

    /** Payload of {@link #QUEUED}. */
    public record Queued(String messageId) {}

    /**
     * @param params    the values of the template's parameters by name, computed on the context
     * @param condition whether to send at all (null: always)
     */
    public record Metadata<C>(MailTemplate template, Function<C, MailRecipient> recipient,
        Map<String, Function<C, ?>> params, Predicate<C> condition) {
        public Metadata {
            Objects.requireNonNull(template, "template must not be null");
            Objects.requireNonNull(recipient, "recipient must not be null");
            params = Map.copyOf(Objects.requireNonNull(params, "params must not be null"));
        }
    }

    public static <C extends ProcessContext> StepSpec<Metadata<C>, C> of(MailTemplate template,
        Function<C, MailRecipient> recipient, Map<String, Function<C, ?>> params) {
        return StepSpec.of(SendMail.class, new Metadata<>(template, recipient, params, null));
    }

    /** Sends only when {@code condition} holds, for example only to users with an address. */
    public static <C extends ProcessContext> StepSpec<Metadata<C>, C> when(Predicate<C> condition,
        MailTemplate template, Function<C, MailRecipient> recipient, Map<String, Function<C, ?>> params) {
        return StepSpec.of(SendMail.class, new Metadata<>(template, recipient, params,
            Objects.requireNonNull(condition, "condition must not be null")));
    }

    private final MailTemplates templates;
    private final MessageCatalog messages;
    private final JsonMapper json;
    private final ObjectProvider<EventPublisher> publisher;
    private final StorageAdapterRegistry storages;
    private final String poolRef;

    public SendMail(MailTemplates templates, MessageCatalog messages, JsonMapper json,
        ObjectProvider<EventPublisher> publisher, StorageAdapterRegistry storages,
        @Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.templates = templates;
        this.messages = messages;
        this.json = json;
        this.publisher = publisher;
        this.storages = storages;
        this.poolRef = poolRef;
    }

    /** The user's address and language, as far as known. */
    private record UserMail(String email, String locale) {}

    @Override
    public Mono<Void> execute(Metadata<C> metadata, C ctx) {
        return Mono.defer(() -> {
            if (metadata.condition() != null && !metadata.condition().test(ctx)) {
                return Mono.empty();
            }
            MailTemplate template = metadata.template();
            MailRecipient recipient = Objects.requireNonNull(metadata.recipient().apply(ctx),
                "the recipient must not be null");
            if (template.unsubscribable() && recipient.userId() == null) {
                return Mono.error(new IllegalArgumentException("Mail " + template.name() + " is a notification, "
                    + "which goes to users only (unsubscribing is per user)"));
            }
            Map<String, String> values = new TreeMap<>();
            for (String param : template.params()) {
                Function<C, ?> value = metadata.params().get(param);
                Object raw = value == null ? null : value.apply(ctx);
                if (secret(raw)) {
                    ctx.reject(new Violation(null, PlatformErrorCodes.MAIL_PARAM_SECRET, "Mail " + template.name()
                        + " was given a secret as " + param, Map.of("param", param)));
                    return Mono.empty();
                }
                values.put(param, raw == null ? "" : text(raw));
            }
            String params = json.writeValueAsString(values);
            if (params.length() > MAX_PARAMS) {
                ctx.reject(new Violation(null, PlatformErrorCodes.MAIL_PARAMS_TOO_LARGE, "The parameters of mail "
                    + template.name() + " are " + params.length() + " characters", Map.of("limit", MAX_PARAMS)));
                return Mono.empty();
            }
            Mono<UserMail> user = recipient.userId() == null ? Mono.just(new UserMail(null, null))
                : findUser(recipient.userId());
            return user.flatMap(found -> {
                String address = recipient.address() != null ? recipient.address() : found.email();
                if (address == null || address.isBlank()) {
                    ctx.reject(new Violation(null, PlatformErrorCodes.MAIL_NO_ADDRESS,
                        "User " + recipient.userId() + " has no e-mail address", Map.of("user",
                            String.valueOf(recipient.userId()))));
                    return Mono.<Void>empty();
                }
                Locale locale = messages.supported(recipient.locale() != null ? recipient.locale()
                    : found.locale() != null ? Locale.of(found.locale()) : messages.defaultLocale());
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("template", template.name());
                row.put("category", template.category().name());
                row.put("userId", recipient.userId());
                row.put("address", address);
                row.put("locale", locale.getLanguage());
                row.put("params", params);
                row.put("createdTime", ctx.opTime());
                row.put("processSeqId", BigDecimal.valueOf(ctx.processSeqId()));
                Object id = ctx.changes().insert(MailEntities.MESSAGE, row);
                EventPublisher target = publisher.getIfUnique();
                if (target == null) {
                    return Mono.error(new IllegalStateException("No EventPublisher is configured"));
                }
                return target.publish(QUEUED, new Queued(String.valueOf(id)), ctx.processSeqId());
            });
        });
    }

    /** A record with an {@code @Sensitive} component: a secret by its declaration. */
    private static boolean secret(Object raw) {
        return raw instanceof Record && Arrays.stream(raw.getClass().getRecordComponents())
            .anyMatch(component -> component.isAnnotationPresent(Sensitive.class));
    }

    /** A parameter as the mail shows it; the process formats what needs formatting (amounts, dates). */
    private static String text(Object raw) {
        return raw instanceof BigDecimal decimal ? decimal.toPlainString() : String.valueOf(raw);
    }

    private Mono<UserMail> findUser(String userId) {
        UUID id;
        try {
            id = UUID.fromString(userId);
        } catch (IllegalArgumentException e) {
            return Mono.error(new IllegalArgumentException("Not a user id: " + userId));
        }
        // The latest version: users are never scheduled.
        return storages.getEngine(poolRef).select("SELECT email, locale, is_deleted FROM sec_user_version"
                + " WHERE user_id = :user ORDER BY version_no DESC LIMIT 1", Map.of("user", BoundValue.of(id)))
            .next()
            .map(row -> Boolean.TRUE.equals(row.get("is_deleted")) ? new UserMail(null, null)
                : new UserMail(Rows.string(row.get("email")), Rows.string(row.get("locale"))))
            .defaultIfEmpty(new UserMail(null, null));
    }

    @Override
    public List<String> problems(Metadata<C> metadata) {
        List<String> problems = new ArrayList<>();
        MailTemplate template = metadata.template();
        templates.find(template.name()).ifPresentOrElse(declared -> {
            if (!declared.equals(template)) {
                problems.add("sends mail " + template.name() + " as declared otherwise than its bean");
            }
        }, () -> problems.add("sends mail " + template.name() + ", which is not a MailTemplate bean"));
        Set<String> given = new LinkedHashSet<>(metadata.params().keySet());
        for (String param : template.params()) {
            if (!given.remove(param)) {
                problems.add("sends mail " + template.name() + " without its parameter " + param);
            }
        }
        for (String extra : given) {
            problems.add("sends mail " + template.name() + " with " + extra + ", which it does not declare");
        }
        if (publisher.getIfUnique() == null) {
            problems.add("sends mail but no EventPublisher is configured");
        }
        return problems;
    }
}
