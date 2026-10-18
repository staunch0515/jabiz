package com.jabiz.runtime.security.protect;

import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.process.ProcessContext;
import com.jabiz.runtime.DatasetEntityManager;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.approval.ApprovalDecisionCheck;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.PermissionDeniedException;
import com.jabiz.runtime.security.MfaPolicy;
import com.jabiz.runtime.security.SecurityPermissions;
import com.jabiz.runtime.storage.Rows;
import com.jabiz.security.MfaRequirement;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Set;

/**
 * Who may decide a protected change ({@code APPROVAL_DECIDE} on {@value ProtectedChangeEntities#SUBJECT}; decision D48
 * item 1), besides the level's permission and not being its requester ({@code APPROVAL_OWN_REQUEST}):
 * <ul>
 *   <li>{@value SecurityPermissions#PROTECTED_CHANGE_DECIDE} instead of {@code approval.decide}, which decides every
 *       other approval but not these (decision D51; {@link #decidePermission});</li>
 *   <li>a recent second factor (as for every administration, {@code ADMINISTRATION});</li>
 *   <li>not the first administrator while another administrator exists (403 {@code BOOTSTRAP_ADMIN_RETIRED});</li>
 *   <li>to approve: not a user the change is for, as judged now (422 {@code APPROVAL_TARGET_USER}) - for a role's new
 *       permission, every holder of the role who would gain it.</li>
 * </ul>
 */
@Component
class ProtectedChangeDecisionCheck implements ApprovalDecisionCheck {

    private final ProtectedGrants grants;
    private final DatasetEntityManager entities;
    private final DatasetRegistry datasets;
    private final MfaPolicy mfa;

    ProtectedChangeDecisionCheck(ProtectedGrants grants, DatasetEntityManager entities, DatasetRegistry datasets,
        MfaPolicy mfa) {
        this.grants = grants;
        this.entities = entities;
        this.datasets = datasets;
        this.mfa = mfa;
    }

    @Override
    public boolean covers(String subject) {
        return ProtectedChangeEntities.SUBJECT.equals(subject);
    }

    @Override
    public String decidePermission() {
        return SecurityPermissions.PROTECTED_CHANGE_DECIDE;
    }

    @Override
    public Mono<Void> check(EntityInstance request, boolean approve, ProcessContext ctx) {
        return Mono.defer(() -> {
            mfa.require(ctx.request(), MfaRequirement.ADMINISTRATION, "Deciding a protected change");
            String actor = ctx.request().actorId();
            return grants.retiredBootstrap(actor, ctx.processSeqId(), ctx.opTime()).flatMap(retired -> {
                if (retired) {
                    return Mono.error(new BootstrapAdminRetiredException(
                        "Other administrators exist: the first administrator may no longer decide protected changes"));
                }
                return approve ? load(request.get("entityId")).flatMap(change -> approverCheck(change, actor, ctx))
                    : Mono.empty();
            });
        });
    }

    /**
     * Whether a holder of {@code held} may approve a change giving (or, for a credential, taking over the holder of)
     * {@code gained} (review H2): {@code *}; or an administrator's permission ({@code administration}, configured,
     * decision D51) and every permission of {@code gained}. Nobody approves more than they hold themselves.
     */
    static boolean covers(Set<String> held, Set<String> gained, Set<String> administration) {
        if (held.contains("*")) {
            return true;
        }
        return held.stream().anyMatch(administration::contains) && held.containsAll(gained);
    }

    /**
     * Whether a holder of {@code held} may still decide a change of {@code kind} giving {@code gained} when it is
     * applied (review H2 verification): {@code *}; or {@value SecurityPermissions#PROTECTED_CHANGE_DECIDE} (decision
     * D51; {@code approval.decide} does not decide these), the permission of the approval's level and {@link #covers}
     * - what deciding needed in the first place, judged on what the approver holds now.
     */
    static boolean mayDecide(Set<String> held, String kind, Set<String> gained, Set<String> administration) {
        if (held.contains("*")) {
            return true;
        }
        return held.contains(SecurityPermissions.PROTECTED_CHANGE_DECIDE)
            && held.contains(ProtectedChangeProcesses.levelPermission(kind)) && covers(held, gained, administration);
    }

    private Mono<Void> approverCheck(EntityInstance change, String actor, ProcessContext ctx) {
        Set<String> approved = ProtectedChangeProcesses.split(change.get("gained"));
        if (!covers(ctx.request().permissions(), approved, grants.settings().administratorPermissions())) {
            return Mono.error(new PermissionDeniedException("*", "Approving protected change " + change.id()
                + " needs an administrator's permission and every permission it gives: " + approved));
        }
        // Judged as for its requester: who the change is for does not depend on who decides it.
        return grants.lock().then(grants.assess(ProtectedChangeProcesses.draftOf(change), change.get("requestedBy"),
                ctx.processSeqId(), ctx.opTime(), String.valueOf(change.id())))
            .doOnNext(assessment -> {
                if (assessment.affected().stream().anyMatch(user -> user.toString().equals(actor))
                    || ProtectedChangeProcesses.split(change.get("affectedUsers")).contains(actor)) {
                    ctx.reject(new Violation("requestId", PlatformErrorCodes.APPROVAL_TARGET_USER,
                        "You are a user protected change " + change.id() + " is for and cannot approve it",
                        Map.of("change", String.valueOf(change.id()))));
                }
            })
            .then();
    }

    private Mono<EntityInstance> load(String changeId) {
        return Mono.defer(() -> entities.findById(datasets.findById(ProtectedChangeEntities.CHANGE_DATASET)
                .orElseThrow(), ProtectedChangeEntities.SEC_PROTECTED_CHANGE, Rows.uuid(changeId)))
            .onErrorResume(EntityNotFoundException.class, e -> Mono.empty());
    }
}
