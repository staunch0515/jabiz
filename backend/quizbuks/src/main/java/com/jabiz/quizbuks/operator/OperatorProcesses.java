package com.jabiz.quizbuks.operator;

import com.jabiz.entity.Violation;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessStart;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.identity.AccountEditing;
import com.jabiz.quizbuks.identity.QbAccounts;
import com.jabiz.quizbuks.identity.SponsorApprovals;
import com.jabiz.quizbuks.publishing.PublicationApprovals;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.approval.ApprovalEntities;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.security.SecurityEntities;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Headquarters' processes for operators (docs/quizbuks/plans/Q48-operators.md, Q48a) and the lead's removal of a
 * member:
 * <ul>
 *   <li>{@value #SAVE}: creates an operator or changes it (with the revision the page read); suspending it keeps its
 *       members out of the entry and their acts ({@code operatorActive} on each member's row, written here);</li>
 *   <li>{@value #MEMBER_ADD} / {@value #MEMBER_REMOVE}: names a person to an operator's staff (or changes whether
 *       they lead it) and removes them. The member's role {@code QB_OPERATOR} is a protected grant (it decides
 *       approvals), given through the platform's role assignment and its four eyes: membership says which operator
 *       they act for, the role what they may do;</li>
 *   <li>{@value #MEMBER_DROP}: the operator's lead removes a member of their own operator;</li>
 *   <li>{@value #TEAM}: a member reads their operator and its current members (a template cannot: it reads an
 *       entity through one dataset, and the caller's own membership is a dataset of its own);</li>
 *   <li>{@value #ASSIGN}: moves a sponsor to an operator, or back to headquarters (D-Q48-1), with a reason;</li>
 *   <li>{@value #SPOT_CHECK}: headquarters marks a review decided by an operator's member as checked, or as a problem
 *       with a note (D-Q48-5); headquarters then acts itself, which the operator does not undo.</li>
 * </ul>
 */
public final class OperatorProcesses {

    public static final String SAVE = "QB_OPERATOR_SAVE";
    public static final String MEMBER_ADD = "QB_OPERATOR_MEMBER_ADD";
    public static final String MEMBER_REMOVE = "QB_OPERATOR_MEMBER_REMOVE";
    public static final String MEMBER_DROP = "QB_OPERATOR_MEMBER_DROP";
    public static final String ASSIGN = "QB_SPONSOR_OPERATOR_ASSIGN";
    public static final String SPOT_CHECK = "QB_REVIEW_SPOT_CHECK";
    public static final String TEAM = "QB_OPERATOR_TEAM";

    /**
     * @param operatorId   null to create one
     * @param baseRevision the revision the page read (required when changing one)
     */
    public record SaveInput(String operatorId, @NotBlank @Size(max = QbOperators.NAME_LENGTH) String name,
        @NotBlank @Size(max = QbOperators.CONTACT_NAME_LENGTH) String contactName,
        @NotBlank @Size(max = QbOperators.EMAIL_LENGTH) String contactEmail, String status,
        @Size(max = QbOperators.NOTE_LENGTH) String note, Long baseRevision) {}

    public record SaveOutput(String operatorId, long revision, String status) {}

    public record MemberInput(@NotBlank String operatorId, @NotBlank String userId, boolean lead) {}

    public record MemberRef(@NotBlank String userId) {}

    /** @param changed false when nothing had to change */
    public record MemberOutput(String userId, String operatorId, boolean changed) {}

    /** @param operatorId null to move the sponsor back to headquarters */
    public record AssignInput(@NotBlank String userId, String operatorId,
        @NotBlank @Size(max = QbOperators.REASON_LENGTH) String reason) {}

    public record AssignOutput(String userId, String operatorId, boolean changed) {}

    public record SpotCheckInput(@NotBlank String decisionId, @NotBlank String result,
        @Size(max = QbOperators.NOTE_LENGTH) String note) {}

    public record SpotCheckOutput(String decisionId, String result, String operatorId) {}

    /** The caller's operator: nothing to give. */
    public record TeamInput() {}

    /** A current member; their address is their work address (the lead reaches them). */
    public record TeamMember(String userId, String displayName, String email, boolean lead, Instant addedAt,
        boolean self) {}

    /** @param lead whether the caller leads the operator (the page offers removing members then) */
    public record TeamOutput(String operatorId, String name, String status, String collection, boolean lead,
        List<TeamMember> members) {}

    static final String INPUT = "input";
    static final String OUTPUT = "output";
    static final String OPERATOR = "operator";
    static final String MEMBERS = "members";
    static final String TARGET = "target";
    static final String PLACE = "place";
    static final String USERS = "users";
    static final String SPONSOR = "sponsor";
    static final String ASSIGNMENT = "assignment";
    static final String DECISION = "decision";
    static final String REQUEST = "request";
    static final String DECIDER = "decider";
    static final String CHECK = "check";

    /** The review subjects whose decisions headquarters spot-checks. */
    static final Set<String> REVIEWED = Set.of(SponsorApprovals.SUBJECT, PublicationApprovals.SUBJECT);

    // ---- operators ---------------------------------------------------------------------------------------------------

    public static ProcessDefinition<SaveInput, SaveOutput, ProcessContext> save() {
        return ProcessDefinition.define(SAVE, 1, SaveInput.class, SaveOutput.class, ProcessContext.class, pb -> pb
            .description("Creates an operator or changes it; a suspended operator's members do not sign in or act.")
            .permissions(QbPermissions.OPERATORS_WRITE)
            .requiresVerifiedEmail()
            .contextFactory((start, input) -> start(start, input, blankToNull(input.operatorId())))
            .outputMapper(ctx -> ctx.get(OUTPUT, SaveOutput.class))
            .step("Lock the operator", HoldLock.<ProcessContext>exclusive(
                ctx -> QbOperators.operatorLockName(target(ctx))))
            .step("Load the operator and its members", QueryEntities.<ProcessContext>all(
                QueryEntities.query(QbOperators.defaultDataset(QbOperators.OPERATOR),
                    ctx -> byKey("operatorId", target(ctx)), OPERATOR),
                QueryEntities.query(QbOperators.defaultDataset(QbOperators.MEMBER),
                    ctx -> currentMembers(target(ctx)), MEMBERS)))
            .compute("Save", (metadata, ctx) -> save(ctx, ctx.get(INPUT, SaveInput.class))));
    }

    static void save(ProcessContext ctx, SaveInput input) {
        EntityInstance operator = OperatorChecks.first(ctx, OPERATOR);
        if (target(ctx) != null && operator == null) {
            throw new EntityNotFoundException(QbOperators.OPERATOR + " " + target(ctx) + " not found");
        }
        String status = input.status() == null || input.status().isBlank() ? QbOperators.ACTIVE : input.status();
        if (!QbOperators.STATUSES.contains(status)) {
            ctx.reject(new Violation("status", OperatorCodes.CODE, "Unknown status", Map.of("value", status)));
        }
        String email = input.contactEmail().strip();
        if (email.indexOf('@') < 1 || email.indexOf('@') == email.length() - 1 || email.chars().anyMatch(
            Character::isWhitespace)) {
            ctx.reject(new Violation("contactEmail", OperatorCodes.FIELD, "Not an e-mail address", Map.of()));
        }
        if (operator != null && (input.baseRevision() == null
            || input.baseRevision() != AccountEditing.number(operator.get("revision")))) {
            ctx.reject(new Violation("baseRevision", OperatorCodes.CHANGED, "The operator was changed since it was "
                + "read", Map.of("revision", AccountEditing.number(operator.get("revision")))));
        }
        if (ctx.hasViolations()) {
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name", input.name().strip());
        values.put("contactName", input.contactName().strip());
        values.put("contactEmail", email);
        values.put("status", status);
        values.put("note", input.note() == null || input.note().isBlank() ? null : input.note());
        long revision;
        String operatorId;
        if (operator == null) {
            revision = 1;
            values.put("collection", QbOperators.PLATFORM);
            values.put("createdAt", ctx.opTime());
            values.put("createdBy", ctx.request().actorId());
            values.put("revision", BigDecimal.ONE);
            operatorId = String.valueOf(ctx.changes().insert(QbOperators.OPERATOR, values));
        } else {
            revision = AccountEditing.number(operator.get("revision")) + 1;
            values.put("revision", BigDecimal.valueOf(revision));
            ctx.changes().update(QbOperators.OPERATOR, operator.id(), operator.version(), values);
            operatorId = String.valueOf(operator.id());
            boolean active = QbOperators.ACTIVE.equals(status);
            for (EntityInstance member : OperatorChecks.list(ctx, MEMBERS)) {
                if (active != Boolean.TRUE.equals(member.get("operatorActive"))) {
                    ctx.changes().update(QbOperators.MEMBER, member.id(), member.version(),
                        Map.of("operatorActive", active));
                }
            }
        }
        ctx.put(OUTPUT, new SaveOutput(operatorId, revision, status));
    }

    public static ProcessDefinition<TeamInput, TeamOutput, ProcessContext> team() {
        return ProcessDefinition.define(TEAM, 1, TeamInput.class, TeamOutput.class, ProcessContext.class, pb -> {
            pb.description("A member reads their operator and its current members.")
                .permissions(QbPermissions.OPERATOR_USE)
                .contextFactory((start, input) -> start(start, input, null))
                .outputMapper(ctx -> ctx.get(OUTPUT, TeamOutput.class));
            OperatorChecks.member(pb);
            pb.step("Load the operator and its members", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbOperators.defaultDataset(QbOperators.OPERATOR),
                        ctx -> byKey("operatorId", OperatorChecks.operatorId(ctx)), OPERATOR),
                    QueryEntities.query(QbOperators.defaultDataset(QbOperators.MEMBER),
                        ctx -> currentMembers(OperatorChecks.operatorId(ctx)), MEMBERS)))
                .step("Load the members' accounts", QueryEntities.<ProcessContext>of(SecurityEntities.USER_DATASET,
                    ctx -> EntityQuery.builder().where(new QueryPredicate.In("userId", OperatorChecks.list(ctx, MEMBERS)
                        .stream().map(member -> (Object) member.get("userId")).toList()))
                        .limit(QbOperators.MAX_MEMBERS + 1).build(), USERS))
                .compute("Describe them", (metadata, ctx) -> {
                    EntityInstance operator = OperatorChecks.first(ctx, OPERATOR);
                    Map<String, EntityInstance> users = new java.util.HashMap<>();
                    OperatorChecks.list(ctx, USERS).forEach(user -> users.put(String.valueOf(user.id()), user));
                    List<TeamMember> members = new ArrayList<>();
                    for (EntityInstance member : OperatorChecks.list(ctx, MEMBERS)) {
                        String userId = String.valueOf((Object) member.get("userId"));
                        EntityInstance user = users.get(userId);
                        members.add(new TeamMember(userId, user == null ? null : user.get("displayName"),
                            user == null ? null : user.get("email"), Boolean.TRUE.equals(member.get("lead")),
                            instant(member.get("addedAt")), userId.equals(ctx.request().actorId())));
                    }
                    members.sort(java.util.Comparator.comparing(TeamMember::addedAt,
                        java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())));
                    ctx.put(OUTPUT, new TeamOutput(String.valueOf(operator.id()), operator.get("name"),
                        operator.get("status"), operator.get("collection"),
                        Boolean.TRUE.equals(ctx.get(OperatorChecks.LEAD)), List.copyOf(members)));
                });
        });
    }

    // ---- members -----------------------------------------------------------------------------------------------------

    public static ProcessDefinition<MemberInput, MemberOutput, ProcessContext> memberAdd() {
        return ProcessDefinition.define(MEMBER_ADD, 1, MemberInput.class, MemberOutput.class, ProcessContext.class,
            pb -> pb
                .description("Names a person to an operator's staff (they still need the role QB_OPERATOR), or "
                    + "changes whether they lead it.")
                .permissions(QbPermissions.OPERATORS_WRITE)
                .requiresVerifiedEmail()
                .contextFactory((start, input) -> start(start, input, input.operatorId().strip()))
                .outputMapper(ctx -> ctx.get(OUTPUT, MemberOutput.class))
                .step("Lock the operator", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbOperators.operatorLockName(target(ctx))))
                .step("Lock the person's membership", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbOperators.memberLockName(person(ctx))))
                .step("Load the operator, the person and their place", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbOperators.defaultDataset(QbOperators.OPERATOR),
                        ctx -> byKey("operatorId", target(ctx)), OPERATOR),
                    QueryEntities.query(QbOperators.defaultDataset(QbOperators.MEMBER),
                        ctx -> currentMembers(target(ctx)), MEMBERS),
                    QueryEntities.query(QbOperators.defaultDataset(QbOperators.MEMBER),
                        ctx -> OperatorChecks.current(person(ctx)), PLACE),
                    QueryEntities.query(SecurityEntities.USER_DATASET, ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.In("userId", AccountEditing.uuids(person(ctx)))).limit(1).build(),
                        USERS),
                    QueryEntities.query(QbAccounts.defaultDataset(QbAccounts.SPONSOR), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.In("userId", List.of(person(ctx)))).limit(1).build(), SPONSOR)))
                .compute("Name them", (metadata, ctx) -> add(ctx, ctx.get(INPUT, MemberInput.class))));
    }

    static void add(ProcessContext ctx, MemberInput input) {
        EntityInstance operator = OperatorChecks.first(ctx, OPERATOR);
        if (operator == null) {
            throw new EntityNotFoundException(QbOperators.OPERATOR + " " + target(ctx) + " not found");
        }
        EntityInstance user = OperatorChecks.first(ctx, USERS);
        if (user == null) {
            throw new EntityNotFoundException(SecurityEntities.USER + " " + person(ctx) + " not found");
        }
        String userId = String.valueOf(user.id());
        EntityInstance place = OperatorChecks.first(ctx, PLACE);
        if (place != null && !target(ctx).equals(String.valueOf((Object) place.get("operatorId")))) {
            ctx.reject(new Violation("userId", OperatorCodes.MEMBER_ELSEWHERE, "The person is a member of another "
                + "operator", Map.of()));
            return;
        }
        if (place != null) {
            boolean changed = input.lead() != Boolean.TRUE.equals(place.get("lead"));
            if (changed) {
                ctx.changes().update(QbOperators.MEMBER, place.id(), place.version(), Map.of("lead", input.lead()));
            }
            ctx.put(OUTPUT, new MemberOutput(userId, target(ctx), changed));
            return;
        }
        if (OperatorChecks.first(ctx, SPONSOR) != null) {
            ctx.reject(new Violation("userId", OperatorCodes.MEMBER_IS_SPONSOR, "The person runs a sponsor account",
                Map.of()));
        }
        if (!verified(user)) {
            ctx.reject(new Violation("userId", OperatorCodes.MEMBER_UNVERIFIED, "The person's e-mail address is not "
                + "verified", Map.of()));
        }
        if (OperatorChecks.list(ctx, MEMBERS).size() >= QbOperators.MAX_MEMBERS) {
            ctx.reject(new Violation("userId", OperatorCodes.MEMBERS_FULL, "The operator has its most members",
                Map.of("max", QbOperators.MAX_MEMBERS)));
        }
        if (ctx.hasViolations()) {
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("operatorId", target(ctx));
        values.put("userId", user.id());
        values.put("lead", input.lead());
        values.put("operatorActive", QbOperators.ACTIVE.equals(operator.get("status")));
        values.put("addedAt", ctx.opTime());
        values.put("addedBy", ctx.request().actorId());
        ctx.changes().insert(QbOperators.MEMBER, values);
        ctx.put(OUTPUT, new MemberOutput(userId, target(ctx), true));
    }

    /** Whether the user's current address is the one they verified. */
    static boolean verified(EntityInstance user) {
        Object email = user.get("email");
        Object verified = user.get("verifiedEmail");
        return email != null && verified != null
            && email.toString().toLowerCase(Locale.ROOT).equals(verified.toString().toLowerCase(Locale.ROOT));
    }

    public static ProcessDefinition<MemberRef, MemberOutput, ProcessContext> memberRemove() {
        return ProcessDefinition.define(MEMBER_REMOVE, 1, MemberRef.class, MemberOutput.class, ProcessContext.class,
            pb -> pb
                .description("Headquarters removes a person from their operator's staff: they act for it no more.")
                .permissions(QbPermissions.OPERATORS_WRITE)
                .requiresVerifiedEmail()
                .contextFactory((start, input) -> start(start, input, null))
                .outputMapper(ctx -> ctx.get(OUTPUT, MemberOutput.class))
                .step("Lock the person's membership", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbOperators.memberLockName(person(ctx))))
                .step("Load the person's place", QueryEntities.<ProcessContext>of(
                    QbOperators.defaultDataset(QbOperators.MEMBER), ctx -> OperatorChecks.current(person(ctx)),
                    PLACE))
                .compute("Remove them", (metadata, ctx) -> remove(ctx, null)));
    }

    public static ProcessDefinition<MemberRef, MemberOutput, ProcessContext> memberDrop() {
        return ProcessDefinition.define(MEMBER_DROP, 1, MemberRef.class, MemberOutput.class, ProcessContext.class,
            pb -> pb
                .description("The operator's lead removes a member of their staff: they act for it no more.")
                .permissions(QbPermissions.OPERATOR_USE)
                .requiresVerifiedEmail()
                .contextFactory((start, input) -> start(start, input, null))
                .outputMapper(ctx -> ctx.get(OUTPUT, MemberOutput.class))
                // Both memberships exclusively, in the order of the users' ids: two leads removing each other take
                // their turns.
                .step("Lock the first membership", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbOperators.memberLockName(ordered(ctx).getFirst())))
                .step("Lock the second membership", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbOperators.memberLockName(ordered(ctx).getLast())))
                .step("Load both places", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbOperators.defaultDataset(QbOperators.MEMBER),
                        ctx -> OperatorChecks.current(ctx.request().actorId()), OperatorChecks.MEMBERSHIP),
                    QueryEntities.query(QbOperators.defaultDataset(QbOperators.MEMBER),
                        ctx -> OperatorChecks.current(person(ctx)), PLACE)))
                .compute("Remove them", (metadata, ctx) -> {
                    EntityInstance caller = OperatorChecks.first(ctx, OperatorChecks.MEMBERSHIP);
                    if (caller == null) {
                        throw new BusinessRuleViolationException(new Violation(null, OperatorCodes.NOT_MEMBER,
                            "The caller is no operator's member", Map.of()));
                    }
                    if (!Boolean.TRUE.equals(caller.get("operatorActive"))) {
                        throw new BusinessRuleViolationException(new Violation(null, OperatorCodes.SUSPENDED,
                            "The caller's operator is suspended", Map.of()));
                    }
                    if (!Boolean.TRUE.equals(caller.get("lead"))) {
                        throw new BusinessRuleViolationException(new Violation(null, OperatorCodes.NOT_LEAD,
                            "Only the operator's lead removes its members", Map.of()));
                    }
                    if (caller.id().equals(placeId(ctx))) {
                        throw new BusinessRuleViolationException(new Violation("userId", OperatorCodes.LEAD_SELF,
                            "A lead does not remove themself", Map.of()));
                    }
                    remove(ctx, String.valueOf((Object) caller.get("operatorId")));
                }));
    }

    private static Object placeId(ProcessContext ctx) {
        EntityInstance place = OperatorChecks.first(ctx, PLACE);
        return place == null ? null : place.id();
    }

    private static List<String> ordered(ProcessContext ctx) {
        String caller = ctx.request().actorId();
        String other = person(ctx);
        return caller.compareTo(other) <= 0 ? List.of(caller, other) : List.of(other, caller);
    }

    /** @param operatorId the caller's operator: a member of another is not found; null for headquarters */
    static void remove(ProcessContext ctx, String operatorId) {
        EntityInstance place = OperatorChecks.first(ctx, PLACE);
        if (place != null && operatorId != null && !operatorId.equals(String.valueOf((Object) place.get("operatorId")))) {
            throw new EntityNotFoundException(QbOperators.MEMBER + " " + person(ctx) + " not found");
        }
        if (place == null) {
            ctx.put(OUTPUT, new MemberOutput(person(ctx), operatorId, false));
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("removedAt", ctx.opTime());
        values.put("removedBy", ctx.request().actorId());
        ctx.changes().update(QbOperators.MEMBER, place.id(), place.version(), values);
        ctx.put(OUTPUT, new MemberOutput(String.valueOf((Object) place.get("userId")),
            String.valueOf((Object) place.get("operatorId")), true));
    }

    // ---- sponsors ----------------------------------------------------------------------------------------------------

    public static ProcessDefinition<AssignInput, AssignOutput, ProcessContext> assign() {
        return ProcessDefinition.define(ASSIGN, 1, AssignInput.class, AssignOutput.class, ProcessContext.class,
            pb -> pb
                .description("Moves a sponsor to an operator, or back to headquarters, with a reason.")
                .permissions(QbPermissions.OPERATORS_WRITE)
                .requiresVerifiedEmail()
                .actsOn(SecurityEntities.USER, "userId")
                .contextFactory((start, input) -> start(start, input, blankToNull(input.operatorId())))
                .outputMapper(ctx -> ctx.get(OUTPUT, AssignOutput.class))
                .step("Lock the sponsor's assignment", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbOperators.assignmentLockName(person(ctx))))
                .step("Load the sponsor, the operator and the assignment", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbAccounts.defaultDataset(QbAccounts.SPONSOR), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.In("userId", List.of(person(ctx)))).limit(1).build(), SPONSOR),
                    QueryEntities.query(QbOperators.defaultDataset(QbOperators.OPERATOR),
                        ctx -> byKey("operatorId", target(ctx)), OPERATOR),
                    QueryEntities.query(QbOperators.defaultDataset(QbOperators.ASSIGNMENT),
                        ctx -> byKey("userId", person(ctx)), ASSIGNMENT)))
                .compute("Assign", (metadata, ctx) -> assign(ctx, ctx.get(INPUT, AssignInput.class))));
    }

    static void assign(ProcessContext ctx, AssignInput input) {
        if (OperatorChecks.first(ctx, SPONSOR) == null) {
            ctx.reject(new Violation("userId", OperatorCodes.NOT_A_SPONSOR, "The user runs no sponsor account",
                Map.of()));
            return;
        }
        String operatorId = target(ctx);
        if (operatorId != null && OperatorChecks.first(ctx, OPERATOR) == null) {
            throw new EntityNotFoundException(QbOperators.OPERATOR + " " + operatorId + " not found");
        }
        EntityInstance assignment = OperatorChecks.first(ctx, ASSIGNMENT);
        String current = assignment == null ? null : String.valueOf((Object) assignment.get("operatorId"));
        if (java.util.Objects.equals(current, operatorId)) {
            ctx.reject(new Violation("operatorId", OperatorCodes.SAME_OPERATOR, "The sponsor is there already",
                Map.of()));
            return;
        }
        if (operatorId == null) {
            ctx.changes().delete(QbOperators.ASSIGNMENT, assignment.id(), assignment.version());
        } else {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("operatorId", operatorId);
            values.put("assignedAt", ctx.opTime());
            values.put("assignedBy", ctx.request().actorId());
            values.put("reason", input.reason().strip());
            if (assignment == null) {
                values.put("userId", person(ctx));
                ctx.changes().insert(QbOperators.ASSIGNMENT, values);
            } else {
                ctx.changes().update(QbOperators.ASSIGNMENT, assignment.id(), assignment.version(), values);
            }
        }
        ctx.put(OUTPUT, new AssignOutput(person(ctx), operatorId, true));
    }

    // ---- spot checks -------------------------------------------------------------------------------------------------

    public static ProcessDefinition<SpotCheckInput, SpotCheckOutput, ProcessContext> spotCheck() {
        return ProcessDefinition.define(SPOT_CHECK, 1, SpotCheckInput.class, SpotCheckOutput.class,
            ProcessContext.class, pb -> pb
                .description("Headquarters marks a review an operator decided as checked, or as a problem.")
                .permissions(QbPermissions.SPOT_CHECK)
                .requiresVerifiedEmail()
                .contextFactory((start, input) -> start(start, input, input.decisionId().strip()))
                .outputMapper(ctx -> ctx.get(OUTPUT, SpotCheckOutput.class))
                .step("Lock the decision's check", HoldLock.<ProcessContext>exclusive(
                    ctx -> "qb.spot-check:" + target(ctx)))
                .step("Load the decision and its check", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(ApprovalEntities.DECISION_DATASET,
                        ctx -> byKey("decisionId", target(ctx)), DECISION),
                    QueryEntities.query(QbOperators.defaultDataset(QbOperators.SPOT_CHECK),
                        ctx -> byKey("decisionId", target(ctx)), CHECK)))
                .step("Load the request and the decider's memberships", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(ApprovalEntities.REQUEST_DATASET, ctx -> byKey("requestId",
                        decisionField(ctx, "requestId")), REQUEST),
                    QueryEntities.query(QbOperators.defaultDataset(QbOperators.MEMBER), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.In("userId",
                            AccountEditing.uuids(decisionField(ctx, "approverId"))))
                        .limit(QbOperators.MAX_MEMBERS).build(), DECIDER)))
                .compute("Check", (metadata, ctx) -> check(ctx, ctx.get(INPUT, SpotCheckInput.class))));
    }

    private static Object decisionField(ProcessContext ctx, String field) {
        EntityInstance decision = OperatorChecks.first(ctx, DECISION);
        return decision == null ? null : decision.get(field);
    }

    static void check(ProcessContext ctx, SpotCheckInput input) {
        EntityInstance decision = OperatorChecks.first(ctx, DECISION);
        if (decision == null) {
            throw new EntityNotFoundException(ApprovalEntities.DECISION + " " + target(ctx) + " not found");
        }
        EntityInstance request = OperatorChecks.first(ctx, REQUEST);
        String operatorId = operatorAt(OperatorChecks.list(ctx, DECIDER), instant(decision.get("decidedTime")));
        if (request == null || !REVIEWED.contains(String.valueOf((Object) request.get("subject"))) || operatorId == null) {
            ctx.reject(new Violation("decisionId", OperatorCodes.NOT_AN_OPERATOR_DECISION, "Not a review decided by "
                + "an operator's member", Map.of()));
            return;
        }
        String result = input.result().strip();
        if (!QbOperators.CHECK_RESULTS.contains(result)) {
            ctx.reject(new Violation("result", OperatorCodes.CODE, "Unknown result", Map.of("value", result)));
            return;
        }
        String note = input.note() == null || input.note().isBlank() ? null : input.note().strip();
        if (QbOperators.CHECK_PROBLEM.equals(result) && note == null) {
            ctx.reject(new Violation("note", OperatorCodes.SPOT_CHECK_NOTE, "Say what the problem is", Map.of()));
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("result", result);
        values.put("note", note);
        values.put("checkedAt", ctx.opTime());
        values.put("checkedBy", ctx.request().actorId());
        EntityInstance check = OperatorChecks.first(ctx, CHECK);
        if (check == null) {
            values.put("decisionId", target(ctx));
            ctx.changes().insert(QbOperators.SPOT_CHECK, values);
        } else {
            ctx.changes().update(QbOperators.SPOT_CHECK, check.id(), check.version(), values);
        }
        ctx.put(OUTPUT, new SpotCheckOutput(target(ctx), result, operatorId));
    }

    /** The operator a person was a member of at a time, or null (headquarters, or nobody's). */
    static String operatorAt(List<EntityInstance> memberships, Instant time) {
        if (time == null) {
            return null;
        }
        for (EntityInstance membership : memberships) {
            Instant added = instant(membership.get("addedAt"));
            Instant removed = instant(membership.get("removedAt"));
            if (added != null && !added.isAfter(time) && (removed == null || removed.isAfter(time))) {
                return String.valueOf((Object) membership.get("operatorId"));
            }
        }
        return null;
    }

    // ---- shared ------------------------------------------------------------------------------------------------------

    private static ProcessContext start(ProcessStart start, Object input, String target) {
        ProcessContext ctx = new ProcessContext(start);
        ctx.put(INPUT, input);
        if (target != null) {
            ctx.put(TARGET, target);
        }
        return ctx;
    }

    /** The operator (or decision) the process is about; null when creating one. */
    static String target(ProcessContext ctx) {
        return ctx.contains(TARGET) ? ctx.get(TARGET, String.class) : null;
    }

    /** The person the input names, in the platform's form of a user id. */
    static String person(ProcessContext ctx) {
        Object input = ctx.get(INPUT);
        String userId = switch (input) {
            case MemberInput member -> member.userId();
            case MemberRef ref -> ref.userId();
            case AssignInput assign -> assign.userId();
            default -> throw new IllegalStateException("No person in " + input.getClass().getSimpleName());
        };
        return AccountEditing.canonical(userId.strip());
    }

    private static EntityQuery byKey(String field, Object value) {
        List<Object> values = new ArrayList<>();
        if (value != null) {
            values.add(value);
        }
        return EntityQuery.builder().where(new QueryPredicate.In(field, values)).limit(1).build();
    }

    private static EntityQuery currentMembers(String operatorId) {
        List<Object> values = new ArrayList<>();
        if (operatorId != null) {
            values.add(operatorId);
        }
        return EntityQuery.builder().where(new QueryPredicate.And(List.of(
            new QueryPredicate.In("operatorId", values), new QueryPredicate.IsNull("removedAt"))))
            .limit(QbOperators.MAX_MEMBERS + 1).build();
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    static Instant instant(Object value) {
        return value == null ? null : value instanceof OffsetDateTime time ? time.toInstant()
            : value instanceof Instant time ? time : Instant.parse(value.toString());
    }

    private OperatorProcesses() {}
}
