package com.jabiz.runtime.push;

import com.jabiz.entity.Violation;
import com.jabiz.event.DomainEvent;
import com.jabiz.event.EventSubscription;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.push.PushTemplate;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.PermissionDeniedException;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.security.Rbac;
import com.jabiz.runtime.security.SecurityEntities;
import com.jabiz.runtime.security.SecurityPermissions;
import com.jabiz.runtime.security.secret.SingleUseSecrets;
import com.jabiz.security.Sensitive;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The processes of push notifications (decision D69): registering and removing a device (the signed-in user's own,
 * at the session's entry), turning a template off or on for push, and delivering a queued notification
 * ({@value #DELIVER}, the consumer of {@link SendPush#QUEUED}).
 */
@Configuration
public class PushProcesses {

    public static final String REGISTER = "SEC_DEVICE_REGISTER";
    public static final String REMOVE = "SEC_DEVICE_REMOVE";
    public static final String PREFERENCE_SET = "SEC_PUSH_PREFERENCE_SET";
    public static final String DELIVER = "PUSH_DELIVER";
    public static final String CONSUMER = "jabiz.push";
    /** Declared by {@value #DELIVER}, granted to no role: only the system delivers. */
    public static final String DELIVER_PERMISSION = "push.deliver";
    /** The most devices a user keeps: registering another forgets the one registered longest ago. */
    public static final int MAX_DEVICES = 20;

    /**
     * @param sessionId the session the device is registered in: when it ends, so does the device
     */
    public record RegisterInput(@NotNull String userId, @NotBlank String entry, @NotBlank String sessionId,
        @NotBlank String kind, @Sensitive @NotBlank String endpoint, @Sensitive String p256dh,
        @Sensitive String auth) {
        @Override
        public String toString() {
            return "RegisterInput[userId=" + userId + ", entry=" + entry + ", kind=" + kind + ", ***]";
        }
    }

    public record RegisterOutput(String deviceId) {}

    /** Removes the caller's device named by its id, or by its endpoint (a browser knows only that). */
    public record RemoveInput(@NotNull String userId, String deviceId, @Sensitive String endpoint) {
        @Override
        public String toString() {
            return "RemoveInput[userId=" + userId + ", deviceId=" + deviceId + ", ***]";
        }
    }

    public record RemoveOutput(String deviceId) {}

    public record PreferenceInput(@NotNull String userId, @NotBlank String template, @NotNull Boolean subscribed) {}

    public record PreferenceOutput(String template, boolean subscribed) {}

    /** @param notification the notification's own identifier (its event's, 32 hex digits): its tag and topic */
    public record DeliverInput(@NotBlank String template, @NotBlank String userId, Map<String, String> params,
        String link, String notification) {}

    /** How many devices received it, how many were forgotten, and whether the user had turned it off. */
    public record DeliverOutput(int delivered, int forgotten, boolean skipped) {}

    // Context keys.
    static final String INPUT = "input";
    static final String DEVICES = "devices";
    static final String SAME = "same_endpoint";
    static final String PREFERENCES = "preferences";
    static final String USER = "user";
    static final String RESULT = "result";
    static final String DEVICE_ID = "device_id";

    @Bean
    ProcessDefinition<RegisterInput, RegisterOutput, ProcessContext> secDeviceRegisterProcess() {
        return ProcessDefinition.define(REGISTER, 1, RegisterInput.class, RegisterOutput.class, ProcessContext.class,
            pb -> pb
                .description("Registers a device of the signed-in user to receive push notifications on.")
                .permissions(SecurityPermissions.ACCOUNT)
                .entryOnly()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(ctx -> new RegisterOutput(String.valueOf(ctx.get(DEVICE_ID))))
                // One registration of an endpoint at a time: two at once would both insert it.
                .step("One registration of the endpoint at a time", HoldLock.<ProcessContext>exclusive(ctx ->
                    "push.endpoint:" + SingleUseSecrets.sha256Hex(register(ctx).endpoint())))
                .step("Load the user's devices and the endpoint's", QueryEntities.all(
                    QueryEntities.<ProcessContext>query(PushEntities.DEVICE_DATASET, ctx -> Rbac.all(
                        new QueryPredicate.Eq("userId", register(ctx).userId()), "createdAt"), DEVICES),
                    QueryEntities.<ProcessContext>query(PushEntities.DEVICE_DATASET, ctx -> Rbac.all(
                        new QueryPredicate.Eq("endpoint", register(ctx).endpoint()), "createdAt"), SAME)))
                .compute("Register it", (metadata, ctx) -> registerDevice(ctx)));
    }

    @Bean
    ProcessDefinition<RemoveInput, RemoveOutput, ProcessContext> secDeviceRemoveProcess() {
        return ProcessDefinition.define(REMOVE, 1, RemoveInput.class, RemoveOutput.class, ProcessContext.class,
            pb -> pb
                .description("Removes a device of the signed-in user: it receives no more push notifications.")
                .permissions(SecurityPermissions.ACCOUNT)
                .entryOnly()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(ctx -> new RemoveOutput(String.valueOf(ctx.get(DEVICE_ID))))
                .step("Load the user's devices", QueryEntities.<ProcessContext>of(PushEntities.DEVICE_DATASET,
                    ctx -> Rbac.all(new QueryPredicate.Eq("userId", ((RemoveInput) ctx.get(INPUT)).userId()),
                        "createdAt"), DEVICES))
                .compute("Remove it", (metadata, ctx) -> removeDevice(ctx)));
    }

    @Bean
    ProcessDefinition<PreferenceInput, PreferenceOutput, ProcessContext> secPushPreferenceSetProcess(
        PushTemplates templates) {
        return ProcessDefinition.define(PREFERENCE_SET, 1, PreferenceInput.class, PreferenceOutput.class,
            ProcessContext.class, pb -> pb
                .description("Turns the push notifications of a template on or off for the user.")
                .permissions(SecurityPermissions.ACCOUNT)
                .entryOnly()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(ctx -> {
                    PreferenceInput input = (PreferenceInput) ctx.get(INPUT);
                    return new PreferenceOutput(input.template(), input.subscribed());
                })
                .step("Load the preference", QueryEntities.<ProcessContext>of(PushEntities.PREFERENCE_DATASET,
                    ctx -> {
                        PreferenceInput input = (PreferenceInput) ctx.get(INPUT);
                        return Rbac.all(new QueryPredicate.And(List.of(
                            new QueryPredicate.Eq("userId", input.userId()),
                            new QueryPredicate.Eq("template", input.template()))), "template");
                    }, PREFERENCES))
                .compute("Record the choice", (metadata, ctx) -> setPreference(ctx, templates)));
    }

    @Bean
    ProcessDefinition<DeliverInput, DeliverOutput, ProcessContext> pushDeliverProcess() {
        return ProcessDefinition.define(DELIVER, 1, DeliverInput.class, DeliverOutput.class, ProcessContext.class,
            pb -> pb
                .description("Pushes a queued notification to the user's devices.")
                .permissions(DELIVER_PERMISSION)
                .internal()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(ctx -> (DeliverOutput) ctx.get(RESULT))
                .step("Load the user, their devices and their choice", QueryEntities.all(
                    QueryEntities.<ProcessContext>query(SecurityEntities.USER_DATASET, ctx -> Rbac.all(
                        new QueryPredicate.Eq("userId", deliver(ctx).userId()), "userId"), USER),
                    QueryEntities.<ProcessContext>query(PushEntities.DEVICE_DATASET, ctx -> Rbac.all(
                        new QueryPredicate.Eq("userId", deliver(ctx).userId()), "createdAt"), DEVICES),
                    QueryEntities.<ProcessContext>query(PushEntities.PREFERENCE_DATASET, ctx -> Rbac.all(
                        new QueryPredicate.And(List.of(new QueryPredicate.Eq("userId", deliver(ctx).userId()),
                            new QueryPredicate.Eq("template", deliver(ctx).template()))), "template"), PREFERENCES)))
                .step("Push it to each device", DeliverPush.spec()));
    }

    @Bean
    EventSubscription<DeliverInput> pushSubscription(
        ProcessDefinition<DeliverInput, DeliverOutput, ProcessContext> pushDeliverProcess) {
        return EventSubscription.of(CONSUMER, SendPush.QUEUED, pushDeliverProcess, PushProcesses::input);
    }

    @SuppressWarnings("unchecked")
    private static DeliverInput input(DomainEvent event) {
        Map<String, Object> payload = event.payload();
        Object params = payload.get("params");
        Map<String, String> values = new LinkedHashMap<>();
        if (params instanceof Map<?, ?> map) {
            map.forEach((key, value) -> values.put(String.valueOf(key), value == null ? "" : String.valueOf(value)));
        }
        Object link = payload.get("link");
        return new DeliverInput(String.valueOf(payload.get("template")), String.valueOf(payload.get("userId")),
            values, link == null ? null : String.valueOf(link),
            event.eventId() == null ? null : event.eventId().toString().replace("-", ""));
    }

    private static void registerDevice(ProcessContext ctx) {
        RegisterInput input = register(ctx);
        if (!input.userId().equals(ctx.request().actorId())) {
            throw new PermissionDeniedException(SecurityPermissions.ACCOUNT, "Devices are registered by their user");
        }
        // The endpoint registered before, by this user or another (a browser signed in to another account): replaced.
        for (EntityInstance same : list(ctx, SAME)) {
            ctx.changes().delete(PushEntities.DEVICE, same.id(), same.version());
        }
        // The oldest first, as queried.
        List<EntityInstance> others = list(ctx, DEVICES).stream()
            .filter(device -> list(ctx, SAME).stream().noneMatch(same -> same.id().equals(device.id())))
            .toList();
        for (int i = 0; i <= others.size() - MAX_DEVICES; i++) {
            ctx.changes().delete(PushEntities.DEVICE, others.get(i).id(), others.get(i).version());
        }
        Map<String, Object> device = new LinkedHashMap<>();
        device.put("userId", input.userId());
        device.put("entry", input.entry());
        device.put("kind", input.kind());
        device.put("endpoint", input.endpoint());
        device.put("p256dh", input.p256dh());
        device.put("auth", input.auth());
        device.put("sessionId", input.sessionId());
        device.put("createdAt", ctx.opTime());
        device.put("lastUsedAt", ctx.opTime());
        ctx.put(DEVICE_ID, ctx.changes().insert(PushEntities.DEVICE, device));
    }

    private static void removeDevice(ProcessContext ctx) {
        RemoveInput input = (RemoveInput) ctx.get(INPUT);
        if (!input.userId().equals(ctx.request().actorId())) {
            throw new PermissionDeniedException(SecurityPermissions.ACCOUNT, "Devices are removed by their user");
        }
        Optional<EntityInstance> device = list(ctx, DEVICES).stream()
            .filter(d -> input.deviceId() != null ? String.valueOf(d.id()).equals(input.deviceId())
                : input.endpoint() != null && input.endpoint().equals(d.get("endpoint")))
            .findFirst();
        if (device.isEmpty()) {
            throw new EntityNotFoundException("No such device of the user");
        }
        ctx.changes().delete(PushEntities.DEVICE, device.get().id(), device.get().version());
        ctx.put(DEVICE_ID, device.get().id());
    }

    private static void setPreference(ProcessContext ctx, PushTemplates templates) {
        PreferenceInput input = (PreferenceInput) ctx.get(INPUT);
        if (!input.userId().equals(ctx.request().actorId())) {
            throw new PermissionDeniedException(SecurityPermissions.ACCOUNT,
                "Push preferences are set by their own user only");
        }
        List<EntityInstance> existing = list(ctx, PREFERENCES);
        boolean off = !existing.isEmpty() && Boolean.FALSE.equals(existing.getFirst().get("subscribed"));
        Optional<PushTemplate> template = templates.find(input.template());
        String entry = ctx.request().entry();
        // The session's own entry's templates, and those of other entries the user turned off (to turn them on again).
        if (template.isEmpty() || entry != null && !template.get().appliesTo(entry) && !off) {
            ctx.reject(new Violation("template", PlatformErrorCodes.INVALID_VALUE, input.template()
                + " is not a push template", Map.of("field", "template")));
            return;
        }
        if (existing.isEmpty()) {
            if (!input.subscribed()) {
                Map<String, Object> preference = new LinkedHashMap<>();
                preference.put("userId", input.userId());
                preference.put("template", input.template());
                preference.put("subscribed", false);
                ctx.changes().insert(PushEntities.PREFERENCE, preference);
            }
            return;
        }
        EntityInstance current = existing.getFirst();
        if (!Boolean.valueOf(input.subscribed()).equals(current.get("subscribed"))) {
            ctx.changes().update(PushEntities.PREFERENCE, current.id(), current.version(),
                Map.of("subscribed", input.subscribed()));
        }
    }

    static RegisterInput register(ProcessContext ctx) {
        return (RegisterInput) ctx.get(INPUT);
    }

    static DeliverInput deliver(ProcessContext ctx) {
        return (DeliverInput) ctx.get(INPUT);
    }

    @SuppressWarnings("unchecked")
    static List<EntityInstance> list(ProcessContext ctx, String key) {
        Object rows = ctx.get(key);
        return rows instanceof List<?> found ? (List<EntityInstance>) found : List.of();
    }
}
