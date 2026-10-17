package com.jabiz.runtime.security.protect;

import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.process.ProcessContext;
import com.jabiz.runtime.DatasetEntityManager;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.approval.ApprovalDecisionCheck;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.security.MfaPolicy;
import com.jabiz.runtime.storage.Rows;
import com.jabiz.security.MfaRequirement;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Who may decide a protected change ({@code APPROVAL_DECIDE} on {@value ProtectedChangeEntities#SUBJECT}; decision D48
 * item 1), besides the level's permission and not being its requester ({@code APPROVAL_OWN_REQUEST}):
 * <ul>
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
    public Mono<Void> check(EntityInstance request, boolean approve, ProcessContext ctx) {
        return Mono.defer(() -> {
            mfa.require(ctx.request(), MfaRequirement.ADMINISTRATION, "Deciding a protected change");
            String actor = ctx.request().actorId();
            return grants.retiredBootstrap(actor).flatMap(retired -> {
                if (retired) {
                    return Mono.error(new BootstrapAdminRetiredException(
                        "Other administrators exist: the first administrator may no longer decide protected changes"));
                }
                return approve ? load(request.get("entityId")).flatMap(change -> targetCheck(change, actor, ctx))
                    : Mono.empty();
            });
        });
    }

    private Mono<Void> targetCheck(EntityInstance change, String actor, ProcessContext ctx) {
        // Judged as for its requester: who the change is for does not depend on who decides it.
        return grants.assess(ProtectedChangeProcesses.draftOf(change), change.get("requestedBy"), ctx.processSeqId(),
                ctx.opTime())
            .doOnNext(assessment -> {
                if (assessment.affected().stream().anyMatch(user -> user.toString().equals(actor))) {
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
