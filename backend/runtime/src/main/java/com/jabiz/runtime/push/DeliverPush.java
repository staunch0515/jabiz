package com.jabiz.runtime.push;

import com.jabiz.i18n.MessageCatalog;
import com.jabiz.mail.MailPlaceholders;
import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.StepSpec;
import com.jabiz.push.PushTemplate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.StepHandler;
import com.jabiz.runtime.security.RefreshTokenStore;
import com.jabiz.runtime.security.SignInEntries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Pushes one queued notification to the user's devices ({@link PushProcesses#DELIVER}; decision D69): nothing when the
 * template is gone or the user turned it off; to each device of an entry the template is for, whose session is still
 * open, in the user's language: {@code {title, body, url}}, the url the page of the device's frontend
 * ({@code app-path} and the link). Devices whose session has ended or whose push service says they are gone are
 * forgotten. Fails (and the outbox tries again later) only when no device received it and one could not be reached.
 * Should a delivery be tried again after some device received it (its transaction failed), the copy carries the same
 * tag and topic, so it replaces the first instead of showing as a second notification.
 */
@Component
public class DeliverPush<C extends ProcessContext> implements StepHandler<NoMetadata, C> {

    private static final Logger log = LoggerFactory.getLogger(DeliverPush.class);

    public static <C extends ProcessContext> StepSpec<NoMetadata, C> spec() {
        return StepSpec.of(DeliverPush.class, NoMetadata.INSTANCE);
    }

    private final PushTemplates templates;
    private final MessageCatalog messages;
    private final WebPushSender webPush;
    private final ApnsSender apns;
    private final boolean apnsConfigured;
    private final RefreshTokenStore sessions;
    private final SignInEntries entries;
    private final JsonMapper json;

    public DeliverPush(PushTemplates templates, MessageCatalog messages, WebPushSender webPush, ApnsSender apns,
        PushProperties properties, RefreshTokenStore sessions, SignInEntries entries, JsonMapper json) {
        this.templates = templates;
        this.messages = messages;
        this.webPush = webPush;
        this.apns = apns;
        this.apnsConfigured = properties.apns().configured();
        this.sessions = sessions;
        this.entries = entries;
        this.json = json;
    }

    private record Sent(EntityInstance device, WebPushSender.Outcome outcome) {}

    /** How many devices are pushed to at once. */
    static final int CONCURRENCY = 8;

    @Override
    public Mono<Void> execute(NoMetadata metadata, C ctx) {
        return Mono.defer(() -> {
            PushProcesses.DeliverInput input = PushProcesses.deliver(ctx);
            Optional<PushTemplate> template = templates.find(input.template());
            List<EntityInstance> preferences = PushProcesses.list(ctx, PushProcesses.PREFERENCES);
            List<EntityInstance> users = PushProcesses.list(ctx, PushProcesses.USER);
            boolean off = !preferences.isEmpty() && Boolean.FALSE.equals(preferences.getFirst().get("subscribed"));
            if (template.isEmpty() || off || users.isEmpty()) {
                ctx.put(PushProcesses.RESULT, new PushProcesses.DeliverOutput(0, 0, true));
                return Mono.empty();
            }
            Locale locale = locale(users.getFirst());
            UUID user = UUID.fromString(input.userId());
            List<EntityInstance> devices = PushProcesses.list(ctx, PushProcesses.DEVICES).stream()
                .filter(device -> template.get().appliesTo(device.get("entry")))
                .toList();
            if (devices.isEmpty()) {
                ctx.put(PushProcesses.RESULT, new PushProcesses.DeliverOutput(0, 0, false));
                return Mono.empty();
            }
            // Live as at a refresh (not revoked, the newest token unused, not idle beyond the entry's policy): one
            // query for all the user's sessions.
            return sessions.liveSessions(user, null)
                .collectMap(RefreshTokenStore.Session::id, RefreshTokenStore.Session::entry)
                .flatMap(live -> Flux.fromIterable(devices)
                    .flatMap(device -> send(device, live, template.get(), input, locale), CONCURRENCY)
                    .collectList())
                .flatMap(sent -> settle(ctx, sent));
        });
    }

    private Mono<Sent> send(EntityInstance device, Map<UUID, String> live, PushTemplate template,
        PushProcesses.DeliverInput input, Locale locale) {
        String entry = device.get("entry");
        if (!entry.equals(live.get(session(device)))) {
            // Signed out, revoked, expired or idle: the device was this session's.
            return Mono.just(new Sent(device, WebPushSender.Outcome.GONE));
        }
        if (PushEntities.APNS.equals(device.get("kind"))) {
            if (!apnsConfigured) {
                // APNs taken out of the settings: the device stays for when it is back, and is not pushed to.
                return Mono.empty();
            }
            return apns.send(device.get("endpoint"), payload(Shape.APNS, template, input, locale, entry),
                    tag(template, input))
                .map(outcome -> new Sent(device, outcome));
        }
        if (!PushEntities.WEB_PUSH.equals(device.get("kind"))) {
            return Mono.empty();
        }
        return webPush.send(device.get("endpoint"), device.get("p256dh"), device.get("auth"),
                payload(Shape.WEB_PUSH, template, input, locale, entry), input.notification())
            .map(outcome -> new Sent(device, outcome));
    }

    private static UUID session(EntityInstance device) {
        String id = device.get("sessionId");
        try {
            return id == null ? null : UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Mono<Void> settle(C ctx, List<Sent> sent) {
        int delivered = 0;
        int failed = 0;
        int forgotten = 0;
        for (Sent one : sent) {
            switch (one.outcome()) {
                case DELIVERED -> delivered++;
                case FAILED -> failed++;
                case GONE -> {
                    forgotten++;
                    ctx.changes().delete(PushEntities.DEVICE, one.device().id(), one.device().version());
                }
            }
        }
        if (delivered == 0 && failed > 0) {
            log.info("Push {} reached none of {} devices; tried again later", PushProcesses.deliver(ctx).template(),
                failed);
            return Mono.error(new IllegalStateException("No device could be reached"));
        }
        ctx.put(PushProcesses.RESULT, new PushProcesses.DeliverOutput(delivered, forgotten, false));
        return Mono.empty();
    }

    /**
     * How a notification is written for a kind of device, and the most it may weigh: for Web Push
     * {@code {title, body, url, tag}}, which the platform's Service Worker shows, at most what every push service
     * accepts once encrypted ({@link WebPushCrypto#MAX_PLAINTEXT}); for APNs Apple's alert
     * {@code {aps: {alert: {title, body}, sound}, url, tag}}, which iOS shows and whose {@code url} the app's shell
     * opens on a tap, at most {@link ApnsSender#MAX_PAYLOAD}.
     */
    enum Shape {
        WEB_PUSH(WebPushCrypto.MAX_PLAINTEXT),
        APNS(ApnsSender.MAX_PAYLOAD);

        final int max;

        Shape(int max) {
            this.max = max;
        }
    }

    /**
     * The notification in the user's language: the body, then the title, shortened (on characters) when the whole is
     * longer than the kind takes. The tag is the notification's own: should a delivery be tried again after a device
     * received it, the second copy replaces the first instead of showing twice.
     */
    byte[] payload(Shape shape, PushTemplate template, PushProcesses.DeliverInput input, Locale locale, String entry) {
        return fit(json, shape, render(template.titleKey(), input.params(), locale),
            render(template.bodyKey(), input.params(), locale), url(entry, input.link()), tag(template, input));
    }

    /** The notification's own id (its event's), or the template's name for one without; the tag and collapse id. */
    static String tag(PushTemplate template, PushProcesses.DeliverInput input) {
        return input.notification() == null ? template.name() : input.notification();
    }

    /** As {@link #fit(JsonMapper, Shape, String, String, String, String)} for Web Push. */
    static byte[] fit(JsonMapper json, String title, String body, String url, String tag) {
        return fit(json, Shape.WEB_PUSH, title, body, url, tag);
    }

    /** The message as JSON of the shape, its body and then its title shortened until it fits. */
    static byte[] fit(JsonMapper json, Shape shape, String title, String body, String url, String tag) {
        Fitted fittedTitle = new Fitted(title);
        Fitted fittedBody = new Fitted(body);
        byte[] bytes = encode(json, shape, fittedTitle, fittedBody, url, tag);
        for (Fitted part : List.of(fittedBody, fittedTitle)) {
            while (bytes.length > shape.max && part.shorten(bytes.length - shape.max)) {
                bytes = encode(json, shape, fittedTitle, fittedBody, url, tag);
            }
        }
        return bytes;
    }

    private static byte[] encode(JsonMapper json, Shape shape, Fitted title, Fitted body, String url, String tag) {
        Map<String, Object> message = new LinkedHashMap<>();
        if (shape == Shape.APNS) {
            Map<String, Object> alert = new LinkedHashMap<>();
            alert.put("title", title.text());
            alert.put("body", body.text());
            Map<String, Object> aps = new LinkedHashMap<>();
            aps.put("alert", alert);
            aps.put("sound", "default");
            message.put("aps", aps);
        } else {
            message.put("title", title.text());
            message.put("body", body.text());
        }
        message.put("url", url);
        message.put("tag", tag);
        return json.writeValueAsBytes(message);
    }

    /** A text that may be shortened; shown with an ellipsis once shortened. */
    private static final class Fitted {
        private String text;
        private boolean shortened;

        Fitted(String text) {
            this.text = text;
        }

        String text() {
            return shortened && !text.isEmpty() ? text + "\u2026" : text;
        }

        /** Drops characters from the end worth at least {@code bytes} UTF-8 bytes; false when nothing is left. */
        boolean shorten(int bytes) {
            if (text.isEmpty()) {
                return false;
            }
            int end = text.length();
            int dropped = shortened ? 0 : -3;
            while (end > 0 && dropped < bytes) {
                int start = text.offsetByCodePoints(end, -1);
                dropped += utf8(text.substring(start, end)).length;
                end = start;
            }
            text = text.substring(0, end);
            shortened = true;
            return true;
        }
    }

    private String url(String entry, String link) {
        String root = entries.find(entry).map(SignInEntries.Entry::appPath).filter(path -> path != null)
            .orElse("/");
        String base = root.endsWith("/") ? root : root + "/";
        return link == null ? base : base + link;
    }

    private String render(String key, Map<String, String> params, Locale locale) {
        String text = messages.find(key, locale).or(() -> messages.find(key, messages.defaultLocale())).orElse("");
        return MailPlaceholders.fill(text, params);
    }

    private Locale locale(EntityInstance user) {
        Object language = user.get("locale");
        return messages.supported(language == null || String.valueOf(language).isBlank() ? messages.defaultLocale()
            : Locale.of(String.valueOf(language)));
    }

    static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
