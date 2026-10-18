package com.jabiz.runtime.security.protect;

import com.jabiz.approval.ApprovalLevel;
import com.jabiz.entity.Violation;
import com.jabiz.event.DomainEvent;
import com.jabiz.event.EventSubscription;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.process.ChangeSet;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.StepSpec;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.approval.ApprovalCase;
import com.jabiz.runtime.approval.ApprovalEntities;
import com.jabiz.runtime.approval.ApprovalOutcome;
import com.jabiz.runtime.approval.ApprovalProcesses;
import com.jabiz.runtime.approval.RequireApproval;
import com.jabiz.runtime.process.steps.LoadEntity;
import com.jabiz.runtime.security.RefreshTokenStore;
import com.jabiz.runtime.security.RevokeSessionsStep;
import com.jabiz.runtime.security.SecurityEntities;
import com.jabiz.runtime.security.SecurityPermissions;
import com.jabiz.runtime.storage.Rows;
import com.jabiz.security.MfaRequirement;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * The processes of protected grants (decision D48 item 1; docs/design/10-security.md section 16):
 * <ul>
 *   <li>{@code SEC_USER_ROLE_ASSIGN}, {@code SEC_ROLE_PERMISSION_ADD}, {@code SEC_USER_IDENTITY_LINK} assign a role,
 *       grant a role a permission, link a user to an account at an identity provider. A change that gives nothing
 *       protected (or one made during the setup window) is written at once, as through the dataset; otherwise it is
 *       recorded as a pending {@code SecProtectedChange} and an approval is requested (subject
 *       {@value ProtectedChangeEntities#SUBJECT}, one level: the permission of the kind of change). The answer says
 *       which ({@code status} {@code APPLIED} or {@code PENDING}, {@code approval}, {@code approvalRequestId}).</li>
 *   <li>{@code SEC_USER_SET_PASSWORD} and {@code SEC_MFA_RESET} do the same for a holder of a protected permission
 *       (through {@link #defer}).</li>
 *   <li>{@code SEC_PROTECTED_CHANGE_APPLY} (internal) runs on {@code jabiz.approval.approved} / {@code rejected}: an
 *       approved change whose content still hashes the same is written with a {@link ProtectedChangeGrant}, once; a
 *       rejected one is recorded as such. Nothing pending has any effect.</li>
 * </ul>
 */
@Configuration
public class ProtectedChangeProcesses {

    public static final String ASSIGN_ROLE = "SEC_USER_ROLE_ASSIGN";
    public static final String ADD_PERMISSION = "SEC_ROLE_PERMISSION_ADD";
    public static final String LINK_IDENTITY = "SEC_USER_IDENTITY_LINK";
    public static final String APPLY = "SEC_PROTECTED_CHANGE_APPLY";

    /** Context keys shared with the credential processes. */
    public static final String ASSESSMENT = "protected_assessment";
    public static final String CHANGE_RECORD = "protected_change";
    public static final String OUTCOME = "protected_approval";

    public record AssignRoleInput(@NotNull UUID userId, @NotNull UUID roleId, Instant dataFrom, Instant dataTo,
        Instant effectiveTime, @NotBlank @Size(max = 500) String reason) {}

    public record AddPermissionInput(@NotNull UUID roleId, @NotBlank @Size(max = 200) String permission,
        Instant effectiveTime, @NotBlank @Size(max = 500) String reason) {}

    public record LinkIdentityInput(@NotNull UUID userId, @NotBlank @Size(max = 40) String provider,
        @NotBlank @Size(max = 255) String subject, @NotBlank @Size(max = 500) String reason) {}

    /**
     * @param status            {@code APPLIED} (written now) or {@code PENDING} (waiting for approval)
     * @param targetId          the instance written; null while pending
     * @param changeId          the pending {@code SecProtectedChange}; null when written now
     * @param approval          {@code PENDING} while it waits; null when written now
     * @param approvalRequestId the approval request
     */
    public record ProtectedChangeOutput(String status, String targetId, String changeId, String approval,
        String approvalRequestId) {}

    /** @param approverId as the event says; the approver applied with is read from the recorded decision */
    public record ApplyInput(String requestId, String subject, String entityId, String status, String contentHash,
        String approverId) {}

    public record ApplyOutput(String changeId, String status) {}

    private static final String INPUT = "input";
    private static final String USER_ID = "userId";
    private static final String ROLE_ID = "roleId";
    private static final String USER = "user";
    private static final String TARGET = "target";
    private static final String OUTPUT = "output";
    private static final String APPLIED_PASSWORD = "applied_password";
    /** The users an applied grant is for: their sessions end (review M3). */
    private static final String GRANTEES = "grantees";

    /** The process that requests a change of {@code kind}. */
    public static String requestProcess(String kind) {
        return switch (kind) {
            case ProtectedChangeEntities.USER_ROLE -> ASSIGN_ROLE;
            case ProtectedChangeEntities.ROLE_PERMISSION -> ADD_PERMISSION;
            case ProtectedChangeEntities.IDENTITY_LINK -> LINK_IDENTITY;
            case ProtectedChangeEntities.PASSWORD -> "SEC_USER_SET_PASSWORD";
            case ProtectedChangeEntities.MFA_RESET -> "SEC_MFA_RESET";
            default -> throw new IllegalArgumentException("Unknown protected change kind " + kind);
        };
    }

    /**
     * The permission of the approval's level for a change of {@code kind}: an administrator's ({@code security.role.write}
     * for a role's permission, {@code security.user-role.write} otherwise). Its holders see the task.
     */
    static String levelPermission(String kind) {
        return switch (kind) {
            case ProtectedChangeEntities.USER_ROLE -> SecurityPermissions.USER_ROLE_WRITE;
            case ProtectedChangeEntities.ROLE_PERMISSION -> SecurityPermissions.ROLE_WRITE;
            // Taking over an account is approved by an administrator too (review H2), not by whoever may reset
            // passwords: ProtectedChangeDecisionCheck.covers asks the rest.
            case ProtectedChangeEntities.IDENTITY_LINK, ProtectedChangeEntities.PASSWORD,
                ProtectedChangeEntities.MFA_RESET -> SecurityPermissions.USER_ROLE_WRITE;
            default -> throw new IllegalArgumentException("Unknown protected change kind " + kind);
        };
    }

    /** What a recorded change would do, as a draft. */
    static ProtectedGrants.Draft draftOf(EntityInstance change) {
        return new ProtectedGrants.Draft(change.get("kind"), Rows.uuid(change.get("userId")),
            Rows.uuid(change.get("roleId")), change.get("permission"));
    }

    // ================= the request side, shared with the credential processes =================

    /** Assesses {@code draft} into {@link #ASSESSMENT}. */
    public static StepSpec<AssessProtectedChange.Metadata<ProcessContext>, ProcessContext> assess(
        Function<ProcessContext, ProtectedGrants.Draft> draft) {
        return AssessProtectedChange.of(draft, ASSESSMENT);
    }

    /**
     * Decides whether the assessed change waits for approval. Not protected, or during the setup window: false, the
     * caller writes it now. The first administrator while another exists: 403. Otherwise records the pending change
     * with {@code values} (its kind, user, role, permission, period, identity or new hash) and the input's reason, and
     * returns true; {@link #requireApproval()} then asks for the approval. Nothing of the change itself is written.
     */
    public static boolean defer(ProcessContext ctx, Map<String, Object> values, String reason) {
        ProtectedGrants.Assessment assessment = ctx.contains(ASSESSMENT)
            ? ctx.get(ASSESSMENT, ProtectedGrants.Assessment.class) : null;
        if (assessment == null || !assessment.protectedChange()) {
            return false;
        }
        if (assessment.retiredBootstrap()) {
            throw new BootstrapAdminRetiredException("Other administrators exist: the first administrator may no"
                + " longer make protected changes (" + assessment.gained() + ")");
        }
        if (!assessment.needsApproval()) {
            return false;
        }
        Map<String, Object> record = new LinkedHashMap<>();
        // Ids as text: the change names its user and role without referring to them (they may be deleted).
        values.forEach((key, value) -> record.put(key, value instanceof UUID id ? id.toString() : value));
        // What the approval covers (review H1): applying more is refused.
        record.put("gained", String.join(",", new java.util.TreeSet<>(assessment.gained())));
        record.put("affectedUsers", String.join(",", assessment.affected().stream().map(UUID::toString).sorted()
            .toList()));
        record.put("reason", reason.strip());
        record.put("requestedBy", ctx.request().actorId());
        record.put("status", ProtectedChangeEntities.PENDING);
        Object id = ctx.changes().insert(ProtectedChangeEntities.CHANGE, record);
        record.put("changeId", id);
        ctx.put(CHANGE_RECORD, record);
        return true;
    }

    /** Asks for the approval of the change {@link #defer} recorded, into {@link #OUTCOME}. */
    public static StepSpec<RequireApproval.Metadata<ProcessContext>, ProcessContext> requireApproval() {
        return RequireApproval.fixed(ctx -> ctx.contains(CHANGE_RECORD), ProtectedChangeEntities.SUBJECT,
            ctx -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> record = (Map<String, Object>) ctx.get(CHANGE_RECORD);
                @SuppressWarnings("unchecked")
                List<String> names = ctx.contains(ASSESSMENT + ".names")
                    ? (List<String>) ctx.get(ASSESSMENT + ".names") : List.of("", "");
                AssessProtectedChange.Described described = ctx.contains(ASSESSMENT + ".described")
                    ? ctx.get(ASSESSMENT + ".described", AssessProtectedChange.Described.class) : null;
                return approvalCase(record, names, described);
            },
            ctx -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> record = (Map<String, Object>) ctx.get(CHANGE_RECORD);
                return List.of(new ApprovalLevel(levelPermission((String) record.get("kind")), null));
            }, OUTCOME);
    }

    /** The output of a request: written now, or waiting for approval. */
    public static ProtectedChangeOutput output(ProcessContext ctx, Object targetId) {
        if (!ctx.contains(CHANGE_RECORD)) {
            return new ProtectedChangeOutput(ProtectedChangeEntities.APPLIED,
                targetId == null ? null : String.valueOf(targetId), null, null, null);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> record = (Map<String, Object>) ctx.get(CHANGE_RECORD);
        ApprovalOutcome outcome = ctx.get(OUTCOME, ApprovalOutcome.class);
        return new ProtectedChangeOutput(ProtectedChangeEntities.PENDING, null, String.valueOf(record.get("changeId")),
            outcome.status().name(), outcome.requestId());
    }

    /**
     * The case of a change: bound to everything the change does (its new hash included), so that the approval stays
     * valid only for exactly that; facts and reference for the approvers.
     */
    static ApprovalCase approvalCase(Map<String, ?> change, List<String> names,
        AssessProtectedChange.Described described) {
        String kind = String.valueOf(change.get("kind"));
        Map<String, Object> content = new LinkedHashMap<>();
        for (String field : List.of("changeId", "kind", "userId", "roleId", "permission", "dataFrom", "dataTo",
            "effectiveTime", "provider", "subject", "passwordHash", "mfaId", "gained", "affectedUsers",
            "requestedBy", "reason")) {
            Object value = change.get(field);
            if (value != null) {
                content.put(field, value instanceof Instant || value instanceof java.time.OffsetDateTime
                    ? String.valueOf(Rows.instant(value)) : String.valueOf(value));
            }
        }
        String user = names.get(0).isEmpty() ? null : names.get(0);
        String role = names.get(1).isEmpty() ? null : names.get(1);
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("kind", kind);
        if (user != null) {
            facts.put("user", user);
        }
        if (role != null) {
            facts.put("role", role);
        }
        if (change.get("permission") != null) {
            facts.put("permission", change.get("permission"));
        }
        String gained = change.get("gained") == null ? "" : String.valueOf(change.get("gained"));
        if (!gained.isEmpty()) {
            facts.put("gained", fact(gained));
        }
        if (described != null && !described.affected().isEmpty()) {
            facts.put("affected", fact(String.join(", ", described.affected())));
            facts.put("credentials", fact(String.join("; ", described.credentials())));
        }
        String reference = switch (kind) {
            case ProtectedChangeEntities.USER_ROLE -> user + " ← " + role + " (" + gained + ")";
            case ProtectedChangeEntities.ROLE_PERMISSION -> role + " + " + change.get("permission") + " ("
                + split(change.get("affectedUsers")).size() + ")";
            case ProtectedChangeEntities.IDENTITY_LINK -> user + " ← " + change.get("provider");
            default -> user + ": " + kind;
        };
        reference = reference.length() > 100 ? reference.substring(0, 100) : reference;
        return new ApprovalCase(String.valueOf(change.get("changeId")), facts, content, null,
            String.valueOf(change.get("requestedBy")), reference);
    }

    private static String fact(String text) {
        return text.length() > 500 ? text.substring(0, 497) + "..." : text;
    }

    /** The values of a comma-separated list (the gained permissions, the affected users), sorted. */
    static java.util.Set<String> split(Object list) {
        java.util.Set<String> values = new java.util.TreeSet<>();
        if (list != null) {
            for (String value : String.valueOf(list).split(",")) {
                if (!value.isBlank()) {
                    values.add(value.strip());
                }
            }
        }
        return values;
    }

    /** A recorded change's values, its id included. */
    static Map<String, Object> values(EntityInstance change) {
        Map<String, Object> values = new LinkedHashMap<>(change.attributes());
        values.put("changeId", change.id());
        return values;
    }

    private static Instant micros(Instant time) {
        return time == null ? null : time.truncatedTo(ChronoUnit.MICROS);
    }

    // ================= the request processes =================

    public static final ProcessDefinition<AssignRoleInput, ProtectedChangeOutput, ProcessContext> ASSIGN_ROLE_PROCESS =
        ProcessDefinition.define(ASSIGN_ROLE, 1, AssignRoleInput.class, ProtectedChangeOutput.class,
            ProcessContext.class, pb -> pb
                .description("Assigns a role to a user; a role giving protected permissions waits for another"
                    + " administrator's approval.")
                .permissions(SecurityPermissions.USER_ROLE_WRITE)
                .requiresMfa(MfaRequirement.ADMINISTRATION)
                .actsOn(SecurityEntities.USER, USER_ID)
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    ctx.put(USER_ID, input.userId());
                    ctx.put(ROLE_ID, input.roleId());
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get(OUTPUT, ProtectedChangeOutput.class))
                .step("Load the user", LoadEntity.by(SecurityEntities.USER_DATASET, USER_ID, USER))
                .step("Load the role", LoadEntity.by(SecurityEntities.ROLE_DATASET, ROLE_ID, "role"))
                .step("Assess the assignment", assess(ctx -> {
                    AssignRoleInput input = ctx.get(INPUT, AssignRoleInput.class);
                    return new ProtectedGrants.Draft(ProtectedChangeEntities.USER_ROLE, input.userId(),
                        input.roleId(), null);
                }))
                .compute("Assign or ask", (metadata, ctx) -> {
                    AssignRoleInput input = ctx.get(INPUT, AssignRoleInput.class);
                    Map<String, Object> values = new LinkedHashMap<>();
                    values.put("kind", ProtectedChangeEntities.USER_ROLE);
                    values.put("userId", input.userId());
                    values.put("roleId", input.roleId());
                    values.put("dataFrom", micros(input.dataFrom()));
                    values.put("dataTo", micros(input.dataTo()));
                    values.put("effectiveTime", micros(input.effectiveTime()));
                    if (deferred(ctx, values, input.reason(), input.effectiveTime(), input.dataFrom(),
                        input.dataTo())) {
                        return;
                    }
                    Map<String, Object> assignment = new LinkedHashMap<>();
                    assignment.put("userId", input.userId());
                    assignment.put("roleId", input.roleId());
                    assignment.put("dataFrom", input.dataFrom());
                    assignment.put("dataTo", input.dataTo());
                    ChangeSet.Target writes = ctx.changes().in(SecurityEntities.USER_ROLE_DATASET);
                    ctx.put(TARGET, (input.effectiveTime() == null ? writes : writes.effectiveAt(input.effectiveTime()))
                        .insert(SecurityEntities.USER_ROLE, assignment));
                })
                .step("Ask for approval", requireApproval())
                .compute("Answer", (metadata, ctx) -> ctx.put(OUTPUT,
                    output(ctx, ctx.contains(TARGET) ? ctx.get(TARGET) : null))));

    public static final ProcessDefinition<AddPermissionInput, ProtectedChangeOutput, ProcessContext>
        ADD_PERMISSION_PROCESS = ProcessDefinition.define(ADD_PERMISSION, 1, AddPermissionInput.class,
            ProtectedChangeOutput.class, ProcessContext.class, pb -> pb
                .description("Grants a role a permission; a protected permission for a role that users hold waits for"
                    + " another administrator's approval.")
                .permissions(SecurityPermissions.ROLE_WRITE)
                .requiresMfa(MfaRequirement.ADMINISTRATION)
                .actsOn(SecurityEntities.ROLE, ROLE_ID)
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    ctx.put(ROLE_ID, input.roleId());
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get(OUTPUT, ProtectedChangeOutput.class))
                .step("Load the role", LoadEntity.by(SecurityEntities.ROLE_DATASET, ROLE_ID, "role"))
                .step("Assess the grant", assess(ctx -> {
                    AddPermissionInput input = ctx.get(INPUT, AddPermissionInput.class);
                    return new ProtectedGrants.Draft(ProtectedChangeEntities.ROLE_PERMISSION, null, input.roleId(),
                        input.permission().strip());
                }))
                .compute("Grant or ask", (metadata, ctx) -> {
                    AddPermissionInput input = ctx.get(INPUT, AddPermissionInput.class);
                    String permission = input.permission().strip();
                    Map<String, Object> values = new LinkedHashMap<>();
                    values.put("kind", ProtectedChangeEntities.ROLE_PERMISSION);
                    values.put("roleId", input.roleId());
                    values.put("permission", permission);
                    values.put("effectiveTime", micros(input.effectiveTime()));
                    if (deferred(ctx, values, input.reason(), input.effectiveTime(), null, null)) {
                        return;
                    }
                    ChangeSet.Target writes = ctx.changes().in(SecurityEntities.ROLE_PERMISSION_DATASET);
                    ctx.put(TARGET, (input.effectiveTime() == null ? writes : writes.effectiveAt(input.effectiveTime()))
                        .insert(SecurityEntities.ROLE_PERMISSION, Map.of("roleId", input.roleId(),
                            "permission", permission)));
                })
                .step("Ask for approval", requireApproval())
                .compute("Answer", (metadata, ctx) -> ctx.put(OUTPUT,
                    output(ctx, ctx.contains(TARGET) ? ctx.get(TARGET) : null))));

    public static final ProcessDefinition<LinkIdentityInput, ProtectedChangeOutput, ProcessContext>
        LINK_IDENTITY_PROCESS = ProcessDefinition.define(LINK_IDENTITY, 1, LinkIdentityInput.class,
            ProtectedChangeOutput.class, ProcessContext.class, pb -> pb
                .description("Links a user to an account at an identity provider; for a holder of a protected"
                    + " permission it waits for another administrator's approval.")
                .permissions(SecurityPermissions.IDENTITY_WRITE)
                .requiresMfa(MfaRequirement.ADMINISTRATION)
                .actsOn(SecurityEntities.USER, USER_ID)
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    ctx.put(USER_ID, input.userId());
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get(OUTPUT, ProtectedChangeOutput.class))
                .step("Load the user", LoadEntity.by(SecurityEntities.USER_DATASET, USER_ID, USER))
                .step("Assess the link", assess(ctx -> new ProtectedGrants.Draft(ProtectedChangeEntities.IDENTITY_LINK,
                    ctx.get(INPUT, LinkIdentityInput.class).userId(), null, null)))
                .compute("Link or ask", (metadata, ctx) -> {
                    LinkIdentityInput input = ctx.get(INPUT, LinkIdentityInput.class);
                    Map<String, Object> values = new LinkedHashMap<>();
                    values.put("kind", ProtectedChangeEntities.IDENTITY_LINK);
                    values.put("userId", input.userId());
                    values.put("provider", input.provider().strip());
                    values.put("subject", input.subject().strip());
                    if (deferred(ctx, values, input.reason(), null, null, null)) {
                        return;
                    }
                    ctx.put(TARGET, ctx.changes().in(SecurityEntities.USER_IDENTITY_DATASET)
                        .insert(SecurityEntities.USER_IDENTITY, Map.of("userId", input.userId(),
                            "provider", input.provider().strip(), "subject", input.subject().strip())));
                })
                .step("Ask for approval", requireApproval())
                .compute("Answer", (metadata, ctx) -> ctx.put(OUTPUT,
                    output(ctx, ctx.contains(TARGET) ? ctx.get(TARGET) : null))));

    /** {@link #defer} after the checks a pending change needs at once (the written ones are the entity's own). */
    private static boolean deferred(ProcessContext ctx, Map<String, Object> values, String reason,
        Instant effectiveTime, Instant dataFrom, Instant dataTo) {
        ProtectedGrants.Assessment assessment = ctx.get(ASSESSMENT, ProtectedGrants.Assessment.class);
        if (assessment.needsApproval() && !assessment.retiredBootstrap()) {
            if (effectiveTime != null && !effectiveTime.isAfter(ctx.opTime())) {
                ctx.reject(new Violation("effectiveTime", PlatformErrorCodes.EFFECTIVE_TIME_NOT_FUTURE,
                    "A change waiting for approval takes effect when it is approved or later",
                    Map.of("time", String.valueOf(effectiveTime))));
            }
            if (dataFrom != null && dataTo != null && !dataFrom.isBefore(dataTo)) {
                ctx.reject(new Violation("dataTo", PlatformErrorCodes.DATA_PERIOD_ORDER,
                    "dataTo must be after dataFrom"));
            }
            if (ctx.hasViolations()) {
                return true;
            }
        }
        return defer(ctx, values, reason);
    }

    // ================= applying an approved change =================

    public static ProcessDefinition<ApplyInput, ApplyOutput, ProcessContext> applyProcess() {
        return ProcessDefinition.define(APPLY, 1, ApplyInput.class, ApplyOutput.class, ProcessContext.class, pb -> pb
            .description("Applies an approved protected change, or records its rejection.")
            .permissions(SecurityPermissions.USER_ROLE_WRITE)
            .internal()
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(INPUT, input);
                return ctx;
            })
            .outputMapper(ctx -> ctx.contains(OUTPUT) ? ctx.get(OUTPUT, ApplyOutput.class) : new ApplyOutput(null, null))
            .step("Load the change", LoadProtectedChange.of(INPUT, LoadProtectedChange.CHANGE))
            .step("Check the approval", RequireApproval.fixed(ProtectedChangeProcesses::toApply,
                ProtectedChangeEntities.SUBJECT,
                ctx -> approvalCase(values(ctx.get(LoadProtectedChange.CHANGE, EntityInstance.class)),
                    names(ctx), null),
                ctx -> List.of(new ApprovalLevel(levelPermission(
                    ctx.get(LoadProtectedChange.CHANGE, EntityInstance.class).get("kind")), null)), OUTCOME))
            .compute("Apply", (metadata, ctx) -> apply(ctx))
            .step("End the grantees' sessions", RevokeGrantedSessions.of(GRANTEES))
            .step("End the user's sessions", RevokeSessionsStep.class, new RevokeSessionsStep.Metadata(
                LoadProtectedChange.USER, RefreshTokenStore.REASON_PASSWORD, ctx -> ctx.contains(APPLIED_PASSWORD))));
    }

    @SuppressWarnings("unchecked")
    private static List<String> names(ProcessContext ctx) {
        return ctx.contains(LoadProtectedChange.NAMES) ? (List<String>) ctx.get(LoadProtectedChange.NAMES)
            : List.of("", "");
    }

    /** Whether the event approves a pending change of ours. */
    private static boolean toApply(ProcessContext ctx) {
        ApplyInput input = ctx.get(INPUT, ApplyInput.class);
        return ctx.contains(LoadProtectedChange.CHANGE) && ApprovalEntities.APPROVED.equals(input.status())
            && ProtectedChangeEntities.PENDING.equals(
                ctx.get(LoadProtectedChange.CHANGE, EntityInstance.class).get("status"));
    }

    private static void apply(ProcessContext ctx) {
        ApplyInput input = ctx.get(INPUT, ApplyInput.class);
        if (!ctx.contains(LoadProtectedChange.CHANGE)) {
            return;
        }
        EntityInstance change = ctx.get(LoadProtectedChange.CHANGE, EntityInstance.class);
        String changeId = String.valueOf(change.id());
        if (!ProtectedChangeEntities.PENDING.equals(change.get("status"))) {
            return;
        }
        String contentHash = com.jabiz.approval.ContentHash.of(approvalCase(values(change), names(ctx), null)
            .content());
        if (ApprovalEntities.REJECTED.equals(input.status())) {
            if (contentHash.equals(input.contentHash())) {
                finish(ctx, change, ProtectedChangeEntities.REJECTED, input.approverId(), null, null);
            }
            return;
        }
        if (!toApply(ctx) || ctx.get(OUTCOME, ApprovalOutcome.class).status() != ApprovalOutcome.Status.APPROVED) {
            return;
        }
        LoadProtectedChange.Checked checked = ctx.get(LoadProtectedChange.CHECKED, LoadProtectedChange.Checked.class);
        String decider = ctx.get(LoadProtectedChange.DECIDER, String.class);
        if (checked.failure() != null) {
            finish(ctx, change, ProtectedChangeEntities.FAILED, decider, null, checked.failure());
            return;
        }
        String kind = change.get("kind");
        Instant effective = Rows.instant(change.get("effectiveTime"));
        ProtectedChangeGrant grant;
        Object target;
        switch (kind) {
            case ProtectedChangeEntities.USER_ROLE -> {
                grant = new ProtectedChangeGrant(SecurityEntities.USER_ROLE, changeId, ctx.processSeqId());
                Map<String, Object> assignment = new LinkedHashMap<>();
                assignment.put("userId", Rows.uuid(change.get("userId")));
                assignment.put("roleId", Rows.uuid(change.get("roleId")));
                assignment.put("dataFrom", Rows.instant(change.get("dataFrom")));
                assignment.put("dataTo", Rows.instant(change.get("dataTo")));
                target = at(ctx.changes().in(SecurityEntities.USER_ROLE_DATASET).granted(grant), effective, ctx)
                    .insert(SecurityEntities.USER_ROLE, assignment);
            }
            case ProtectedChangeEntities.ROLE_PERMISSION -> {
                grant = new ProtectedChangeGrant(SecurityEntities.ROLE_PERMISSION, changeId, ctx.processSeqId());
                target = at(ctx.changes().in(SecurityEntities.ROLE_PERMISSION_DATASET).granted(grant), effective, ctx)
                    .insert(SecurityEntities.ROLE_PERMISSION, Map.of("roleId", Rows.uuid(change.get("roleId")),
                        "permission", change.get("permission")));
            }
            case ProtectedChangeEntities.IDENTITY_LINK -> {
                grant = new ProtectedChangeGrant(SecurityEntities.USER_IDENTITY, changeId, ctx.processSeqId());
                target = ctx.changes().in(SecurityEntities.USER_IDENTITY_DATASET).granted(grant)
                    .insert(SecurityEntities.USER_IDENTITY, Map.of("userId", Rows.uuid(change.get("userId")),
                        "provider", change.get("provider"), "subject", change.get("subject")));
            }
            case ProtectedChangeEntities.PASSWORD -> {
                EntityInstance user = ctx.get(LoadProtectedChange.USER, EntityInstance.class);
                ctx.changes().update(SecurityEntities.USER, user.id(), user.version(),
                    Map.of("passwordHash", change.get("passwordHash")));
                ctx.put(APPLIED_PASSWORD, true);
                target = user.id();
            }
            case ProtectedChangeEntities.MFA_RESET -> {
                EntityInstance mfa = ctx.get(LoadProtectedChange.MFA, EntityInstance.class);
                ctx.changes().delete(SecurityEntities.USER_MFA, mfa.id(), mfa.version());
                target = mfa.id();
            }
            default -> throw new IllegalStateException("Unknown protected change kind " + kind);
        }
        if (kind.equals(ProtectedChangeEntities.USER_ROLE) || kind.equals(ProtectedChangeEntities.ROLE_PERMISSION)) {
            ctx.put(GRANTEES, ctx.get(LoadProtectedChange.AFFECTED));
        }
        finish(ctx, change, ProtectedChangeEntities.APPLIED, decider, target, null);
    }

    /** At the change's effective time when that is still to come; otherwise now. */
    private static ChangeSet.Target at(ChangeSet.Target writes, Instant effective, ProcessContext ctx) {
        return effective != null && effective.isAfter(ctx.opTime()) ? writes.effectiveAt(effective) : writes;
    }

    private static void finish(ProcessContext ctx, EntityInstance change, String status, String decidedBy,
        Object target, String failure) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("status", status);
        values.put("decidedBy", decidedBy);
        values.put("resultId", target == null ? null : String.valueOf(target));
        values.put("failure", failure);
        ctx.changes().update(ProtectedChangeEntities.CHANGE, change.id(), change.version(), values);
        ctx.put(OUTPUT, new ApplyOutput(String.valueOf(change.id()), status));
    }

    private static ApplyInput applyInput(DomainEvent event) {
        Map<String, Object> payload = event.payload();
        return new ApplyInput(text(payload, "requestId"), text(payload, "subject"), text(payload, "entityId"),
            text(payload, "status"),
            text(payload, "contentHash"), text(payload, "approverId"));
    }

    private static String text(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? null : String.valueOf(value);
    }

    // ================= beans =================

    @Bean
    ProcessDefinition<AssignRoleInput, ProtectedChangeOutput, ProcessContext> secUserRoleAssignProcess() {
        return ASSIGN_ROLE_PROCESS;
    }

    @Bean
    ProcessDefinition<AddPermissionInput, ProtectedChangeOutput, ProcessContext> secRolePermissionAddProcess() {
        return ADD_PERMISSION_PROCESS;
    }

    @Bean
    ProcessDefinition<LinkIdentityInput, ProtectedChangeOutput, ProcessContext> secUserIdentityLinkProcess() {
        return LINK_IDENTITY_PROCESS;
    }

    @Bean
    ProcessDefinition<ApplyInput, ApplyOutput, ProcessContext> secProtectedChangeApplyProcess() {
        return APPLY_PROCESS;
    }

    private static final ProcessDefinition<ApplyInput, ApplyOutput, ProcessContext> APPLY_PROCESS = applyProcess();

    @Bean
    EventSubscription<ApplyInput> protectedChangeApprovedSubscription() {
        return EventSubscription.of("jabiz.security.protected-change-approved", ApprovalProcesses.APPROVED_EVENT,
            APPLY_PROCESS, ProtectedChangeProcesses::applyInput);
    }

    @Bean
    EventSubscription<ApplyInput> protectedChangeRejectedSubscription() {
        return EventSubscription.of("jabiz.security.protected-change-rejected", ApprovalProcesses.REJECTED_EVENT,
            APPLY_PROCESS, ProtectedChangeProcesses::applyInput);
    }
}
