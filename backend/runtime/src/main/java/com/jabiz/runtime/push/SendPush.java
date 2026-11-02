package com.jabiz.runtime.push;

import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.StepSpec;
import com.jabiz.push.PushTemplate;
import com.jabiz.runtime.process.steps.CheckedStep;
import com.jabiz.runtime.process.StepHandler;
import com.jabiz.runtime.process.steps.EventPublisher;
import com.jabiz.security.Sensitive;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Sends a push notification from a process (decision D69), as {@code SendMail} sends a mail:
 * <pre>{@code
 * .step("Tell the customer", SendPush.of(ORDER_SHIPPED, ctx -> customer(ctx).userId(),
 *     Map.of("orderNo", ctx -> order(ctx).get("orderNo")), ctx -> "orders/" + order(ctx).id()))
 * }</pre>
 * In the process's transaction it publishes {@value #QUEUED} (template, user, parameters as text, and the page a tap
 * opens, relative to the root of the frontend of the device's sign-in entry); the platform's consumer pushes it to
 * the user's devices after the commit, in the user's language, unless the user turned the template off. A rolled back
 * process pushes nothing. Nothing at all while push notifications are off ({@code jabiz.push.enabled}).
 */
@Component
public class SendPush<C extends ProcessContext> implements StepHandler<SendPush.Metadata<C>, C>,
    CheckedStep<SendPush.Metadata<C>> {

    /** Published for every notification; the consumer pushes it. */
    public static final String QUEUED = "jabiz.push.queued";
    /** Longest parameter value; a notification is a line or two. */
    public static final int MAX_PARAM = 500;
    /** A page of the frontend: a relative path of plain segments, optionally with a query. */
    static final Pattern LINK = Pattern.compile("([A-Za-z0-9._~-]+(/[A-Za-z0-9._~-]+)*/?)?(\\?[A-Za-z0-9._~=&%-]*)?");

    /** Payload of {@link #QUEUED}. */
    public record Queued(String template, String userId, Map<String, String> params, String link) {}

    /**
     * @param link the page a tap opens, relative to the entry's frontend ({@code orders/42}); null: its home
     */
    public record Metadata<C>(PushTemplate template, Function<C, ?> user, Map<String, Function<C, ?>> params,
        Function<C, String> link, Predicate<C> condition) {
        public Metadata {
            Objects.requireNonNull(template, "template must not be null");
            Objects.requireNonNull(user, "user must not be null");
            params = Map.copyOf(Objects.requireNonNull(params, "params must not be null"));
        }
    }

    public static <C extends ProcessContext> StepSpec<Metadata<C>, C> of(PushTemplate template, Function<C, ?> user,
        Map<String, Function<C, ?>> params, Function<C, String> link) {
        return StepSpec.of(SendPush.class, new Metadata<>(template, user, params, link, null));
    }

    /** Pushes only when {@code condition} holds. */
    public static <C extends ProcessContext> StepSpec<Metadata<C>, C> when(Predicate<C> condition,
        PushTemplate template, Function<C, ?> user, Map<String, Function<C, ?>> params, Function<C, String> link) {
        return StepSpec.of(SendPush.class, new Metadata<>(template, user, params, link,
            Objects.requireNonNull(condition, "condition must not be null")));
    }

    private final PushTemplates templates;
    private final PushProperties properties;
    private final ObjectProvider<EventPublisher> publisher;

    public SendPush(PushTemplates templates, PushProperties properties, ObjectProvider<EventPublisher> publisher) {
        this.templates = templates;
        this.properties = properties;
        this.publisher = publisher;
    }

    @Override
    public Mono<Void> execute(Metadata<C> metadata, C ctx) {
        return Mono.defer(() -> {
            if (!properties.enabled() || metadata.condition() != null && !metadata.condition().test(ctx)) {
                return Mono.empty();
            }
            PushTemplate template = metadata.template();
            Object user = Objects.requireNonNull(metadata.user().apply(ctx), "the user must not be null");
            Map<String, String> values = new TreeMap<>();
            for (String param : template.params()) {
                Function<C, ?> value = metadata.params().get(param);
                Object raw = value == null ? null : value.apply(ctx);
                if (secret(raw)) {
                    ctx.reject(new Violation(null, PlatformErrorCodes.PUSH_PARAM_SECRET, "Push " + template.name()
                        + " was given a secret as " + param, Map.of("param", param)));
                    return Mono.empty();
                }
                String text = raw == null ? "" : raw instanceof BigDecimal decimal ? decimal.toPlainString()
                    : String.valueOf(raw);
                values.put(param, text.length() > MAX_PARAM ? text.substring(0, MAX_PARAM) : text);
            }
            String link = metadata.link() == null ? null : metadata.link().apply(ctx);
            if (link != null && (link.length() > 500 || !LINK.matcher(link).matches() || link.contains(".."))) {
                // A defect of the sending process: only pages of the frontend open from a notification.
                throw new IllegalArgumentException("Push " + template.name() + " links to " + link
                    + ", which is not a relative page of the frontend");
            }
            EventPublisher target = publisher.getIfUnique();
            if (target == null) {
                return Mono.error(new IllegalStateException("No EventPublisher is configured"));
            }
            return target.publish(QUEUED, new Queued(template.name(), String.valueOf(user), values, link),
                ctx.processSeqId());
        });
    }

    private static boolean secret(Object raw) {
        return raw instanceof Record && Arrays.stream(raw.getClass().getRecordComponents())
            .anyMatch(component -> component.isAnnotationPresent(Sensitive.class));
    }

    @Override
    public List<String> problems(Metadata<C> metadata) {
        List<String> problems = new ArrayList<>();
        PushTemplate template = metadata.template();
        templates.find(template.name()).ifPresentOrElse(declared -> {
            if (!declared.equals(template)) {
                problems.add("sends push " + template.name() + " as declared otherwise than its bean");
            }
        }, () -> problems.add("sends push " + template.name() + ", which is not a PushTemplate bean"));
        Set<String> given = new LinkedHashSet<>(metadata.params().keySet());
        for (String param : template.params()) {
            if (!given.remove(param)) {
                problems.add("sends push " + template.name() + " without its parameter " + param);
            }
        }
        for (String extra : given) {
            problems.add("sends push " + template.name() + " with " + extra + ", which it does not declare");
        }
        if (publisher.getIfUnique() == null) {
            problems.add("sends push but no EventPublisher is configured");
        }
        return problems;
    }
}
