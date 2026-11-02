package com.jabiz.runtime.security;

import com.jabiz.context.RequestContext;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.MessageCatalog;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.push.PushTemplate;
import com.jabiz.query.BoundValue;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.PermissionDeniedException;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.push.PushEntities;
import com.jabiz.runtime.push.PushProcesses;
import com.jabiz.runtime.push.PushProperties;
import com.jabiz.runtime.push.PushTemplates;
import com.jabiz.runtime.push.WebPushCrypto;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The signed-in user's push notifications (decision D69; docs/design/18-numbering-approvals-tasks.md section 5.7): whether they are on
 * and the key to subscribe with, the templates of the session's entry with the user's choice, the user's devices; a
 * device registered for the session in use (its end ends the device), removed by id or by its endpoint; a template
 * turned off or on for push. Writes go through the processes {@value PushProcesses#REGISTER},
 * {@value PushProcesses#REMOVE} and {@value PushProcesses#PREFERENCE_SET}.
 */
@RestController
class PushDeviceController {

    static final String BASE = "/api/auth/account/push";

    /**
     * @param webPushKey the VAPID public key a browser subscribes with (Base64url); null while push is off
     */
    record PushSettings(boolean enabled, String webPushKey, List<TemplateChoice> templates, List<DeviceView> devices) {}

    /** @param otherEntry a template of another entry, listed because the user turned it off there */
    record TemplateChoice(String template, String name, boolean subscribed, boolean otherEntry) {}

    /** @param current whether it was registered in the session in use */
    record DeviceView(String id, String kind, String entry, Instant createdAt, boolean current) {}

    record RegisterRequest(String kind, String endpoint, String p256dh, String auth) {
        @Override
        public String toString() {
            return "RegisterRequest[kind=" + kind + ", ***]";
        }
    }

    record RemoveRequest(String deviceId, String endpoint) {
        @Override
        public String toString() {
            return "RemoveRequest[deviceId=" + deviceId + ", ***]";
        }
    }

    record PreferenceRequest(String template, Boolean subscribed) {}

    record Registered(String deviceId) {}

    private final ProcessExecutor processes;
    private final RefreshTokenStore refreshTokens;
    private final PushProperties properties;
    private final PushTemplates templates;
    private final MessageCatalog messages;
    private final StorageAdapterRegistry storages;
    private final String poolRef;
    private final ProcessDefinition<PushProcesses.RegisterInput, PushProcesses.RegisterOutput, ProcessContext> register;
    private final ProcessDefinition<PushProcesses.RemoveInput, PushProcesses.RemoveOutput, ProcessContext> remove;
    private final ProcessDefinition<PushProcesses.PreferenceInput, PushProcesses.PreferenceOutput, ProcessContext>
        preference;

    PushDeviceController(ProcessExecutor processes, RefreshTokenStore refreshTokens, PushProperties properties,
        PushTemplates templates, MessageCatalog messages, StorageAdapterRegistry storages,
        @Value("${jabiz.storage.default-pool-ref:default}") String poolRef,
        ProcessDefinition<PushProcesses.RegisterInput, PushProcesses.RegisterOutput, ProcessContext> register,
        ProcessDefinition<PushProcesses.RemoveInput, PushProcesses.RemoveOutput, ProcessContext> remove,
        ProcessDefinition<PushProcesses.PreferenceInput, PushProcesses.PreferenceOutput, ProcessContext> preference) {
        this.processes = processes;
        this.refreshTokens = refreshTokens;
        this.properties = properties;
        this.templates = templates;
        this.messages = messages;
        this.storages = storages;
        this.poolRef = poolRef;
        this.register = register;
        this.remove = remove;
        this.preference = preference;
    }

    /** Whether push is on, the key, the templates of the session's entry with the user's choice, the devices. */
    @GetMapping(BASE)
    Mono<PushSettings> settings() {
        return RequestContexts.current().flatMap(context -> CurrentSession.open(refreshTokens, context)
            .flatMap(session -> {
                UUID user = requireUser(context);
                String entry = entryOf(context);
                Mono<Map<String, Boolean>> chosen = storages.getEngine(poolRef).select("""
                        SELECT DISTINCT ON (template) template, subscribed, is_deleted
                        FROM sec_user_push_preference_version WHERE user_id = :user
                        ORDER BY template, row_id DESC""", Map.of("user", BoundValue.of(user)))
                    .collectMap(row -> Rows.string(row.get("template")), row -> Boolean.TRUE.equals(row.get("is_deleted"))
                        || Boolean.TRUE.equals(row.get("subscribed")));
                Mono<List<DeviceView>> devices = storages.getEngine(poolRef).select("""
                        SELECT device_id AS id, kind, entry, created_at, session_id
                        FROM sec_user_device WHERE user_id = :user ORDER BY created_at""",
                        Map.of("user", BoundValue.of(user)))
                    .map(row -> new DeviceView(Rows.string(row.get("id")), Rows.string(row.get("kind")),
                        Rows.string(row.get("entry")), Rows.instant(row.get("created_at")),
                        session.isPresent() && session.get().toString().equals(Rows.string(row.get("session_id")))))
                    .collectList();
                return Mono.zip(chosen, devices).map(found -> {
                    List<TemplateChoice> listed = new ArrayList<>();
                    for (PushTemplate template : templates.all()) {
                        boolean subscribed = found.getT1().getOrDefault(template.name(), true);
                        boolean own = template.appliesTo(entry);
                        if (own || !subscribed) {
                            listed.add(new TemplateChoice(template.name(), displayName(template, context.locale()),
                                subscribed, !own));
                        }
                    }
                    listed.sort(java.util.Comparator.comparing(TemplateChoice::name));
                    return new PushSettings(properties.enabled(), properties.enabled()
                        ? properties.webPush().publicKey().trim() : null, listed, found.getT2());
                });
            }));
    }

    /**
     * Registers this browser's subscription (or a store app's token, 17k-b) for the session in use: 422
     * {@code PUSH_DISABLED} while push is off, {@code PUSH_SUBSCRIPTION_INVALID} for an endpoint of no allowed push
     * service or keys that are not a subscription's; a caller of no session cannot register (422
     * {@code SESSION_UNKNOWN}).
     */
    @PostMapping(BASE + "/devices")
    Mono<Registered> register(@RequestBody(required = false) RegisterRequest request) {
        return RequestContexts.current().flatMap(context -> CurrentSession.open(refreshTokens, context)
            .flatMap(session -> {
                UUID user = requireUser(context);
                if (!properties.enabled()) {
                    return Mono.error(refused(PlatformErrorCodes.PUSH_DISABLED, "Push notifications are off"));
                }
                UUID current = session.orElseThrow(() -> new BusinessRuleViolationException(new Violation(null,
                    PlatformErrorCodes.SESSION_UNKNOWN, "A device is registered for a session")));
                if (request == null || !PushEntities.WEB_PUSH.equals(request.kind())
                    || !properties.allowedEndpoint(request.endpoint()) || !subscription(request)) {
                    return Mono.error(refused(PlatformErrorCodes.PUSH_SUBSCRIPTION_INVALID,
                        "Not a usable push subscription"));
                }
                return processes.executeAtEntry(register, new PushProcesses.RegisterInput(user.toString(),
                        entryOf(context), current.toString(), request.kind(), request.endpoint(), request.p256dh(),
                        request.auth()))
                    .map(output -> new Registered(output.deviceId()));
            }));
    }

    /** Removes one of the user's devices, by its id or its endpoint: 204; none of theirs is 404. */
    @PostMapping(BASE + "/devices/remove")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    Mono<Void> remove(@RequestBody(required = false) RemoveRequest request) {
        return RequestContexts.current().flatMap(context -> CurrentSession.open(refreshTokens, context)
            .then(Mono.defer(() -> {
                UUID user = requireUser(context);
                if (request == null || request.deviceId() == null && request.endpoint() == null) {
                    return Mono.error(new com.jabiz.runtime.EntityNotFoundException("No device named"));
                }
                return processes.executeAtEntry(remove, new PushProcesses.RemoveInput(user.toString(),
                    request.deviceId(), request.endpoint())).then();
            })));
    }

    /** Turns a template off or on for push; the process decides which templates the session may set. */
    @PostMapping(BASE + "/preferences")
    Mono<PushProcesses.PreferenceOutput> setPreference(@RequestBody(required = false) PreferenceRequest request) {
        return RequestContexts.current().flatMap(context -> CurrentSession.open(refreshTokens, context)
            .then(Mono.defer(() -> {
                UUID user = requireUser(context);
                if (request == null || request.template() == null || request.subscribed() == null) {
                    return Mono.error(refused(PlatformErrorCodes.INVALID_VALUE, "template and subscribed are needed"));
                }
                return processes.executeAtEntry(preference, new PushProcesses.PreferenceInput(user.toString(),
                    request.template(), request.subscribed()));
            })));
    }

    /** A Web Push subscription's keys: a P-256 point and a 16-byte secret. */
    private static boolean subscription(RegisterRequest request) {
        try {
            WebPushCrypto.publicKey(WebPushCrypto.base64url(request.p256dh()));
            return request.auth() != null && WebPushCrypto.base64url(request.auth()).length == 16;
        } catch (IllegalArgumentException | NullPointerException e) {
            return false;
        }
    }

    private String displayName(PushTemplate template, Locale locale) {
        Locale language = locale == null ? messages.defaultLocale() : locale;
        return messages.find(template.nameKey(), language).or(() -> messages.find(template.nameKey(),
            messages.defaultLocale())).filter(name -> !name.isBlank()).orElse(template.name());
    }

    private static BusinessRuleViolationException refused(String code, String message) {
        return new BusinessRuleViolationException(new Violation(null, code, message, new HashMap<>()));
    }

    private static String entryOf(RequestContext context) {
        return context.entry() == null ? RequestContext.DEFAULT_ENTRY : context.entry();
    }

    private static UUID requireUser(RequestContext context) {
        return ActingUser.userId(context).orElseThrow(() -> new PermissionDeniedException(SecurityPermissions.ACCOUNT,
            "Only users of the platform have devices"));
    }
}
