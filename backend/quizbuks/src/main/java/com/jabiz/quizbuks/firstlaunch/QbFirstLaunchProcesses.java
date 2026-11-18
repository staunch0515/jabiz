package com.jabiz.quizbuks.firstlaunch;

import com.jabiz.context.RequestContext;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.page.QbSponsorPages;
import com.jabiz.quizbuks.publishing.PublicationStatus;
import com.jabiz.quizbuks.publishing.QbPublications;
import com.jabiz.quizbuks.tree.QbTrees;
import com.jabiz.runtime.approval.ApprovalEntities;
import com.jabiz.runtime.approval.ApprovalProcesses;
import com.jabiz.runtime.approval.ControlChanges;
import com.jabiz.runtime.firstlaunch.FirstLaunchCheck;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * QuizBuks's own steps of the first launch (F-LAUNCH, docs/quizbuks/plans/impl/F-LAUNCH.md section 2.3), each run by
 * the platform's step process {@code FIRST_LAUNCH_STEP} as the system:
 * <ul>
 *   <li>{@value #PARAM}: a controlled parameter's initial value: the proposal {@code QB_SETUP} made is withdrawn, the
 *       configured (or {@link com.jabiz.quizbuks.setup.FirstLaunchSwitches first launch's}) value proposed and
 *       published by the platform's {@code CONTROL_CHANGE_FIRST_LAUNCH_PUBLISH} (decision D82);</li>
 *   <li>{@value #APPROVE}: every pending approval request, approved by the platform's
 *       {@code APPROVAL_FIRST_LAUNCH_DECIDE} (which refuses a request the run did not make);</li>
 *   <li>{@value #READY}: whether the graphs, pages and publications made are in effect (the run waits on it);</li>
 *   <li>{@value #LEAD}: inside {@code QB_OPERATOR_SAVE}, a lead whose address is not verified yet is accepted only when
 *       the platform finds the account made by a first launch whose content phase goes on (plan section 5.1).</li>
 * </ul>
 * None is open to a request: the first three are {@code internal()}, the last {@code subProcessOnly()}.
 */
public final class QbFirstLaunchProcesses {

    public static final String PARAM = "QB_FIRST_LAUNCH_PARAM";
    public static final String APPROVE = "QB_FIRST_LAUNCH_APPROVE";
    public static final String READY = "QB_FIRST_LAUNCH_READY";
    public static final String LEAD = "QB_FIRST_LAUNCH_LEAD";

    /**
     * @param withdraw    the proposal {@code QB_SETUP} made of the parameter
     * @param key         the parameter
     * @param kind        its value kind, as declared
     * @param value       the value it takes
     * @param description its description, as declared
     * @param reason      the proposal's reason (names the configuration's hash)
     */
    public record ParamInput(@NotNull UUID withdraw, @NotBlank String key, @NotNull Map<String, Object> kind,
        @NotNull Object value, String description, @NotBlank @Size(max = 500) String reason) {}

    public record ApproveInput() {}

    /** @param approved the requests approved */
    public record ApproveOutput(List<String> approved) {}

    /**
     * @param treeIds        graphs whose version must be in effect
     * @param pageUsers      sponsors whose page must be published
     * @param publicationIds publications that must be live or scheduled
     */
    public record ReadyInput(@NotNull List<String> treeIds, @NotNull List<String> pageUsers,
        @NotNull List<UUID> publicationIds) {}

    public record LeadInput(@NotBlank String userId) {}

    static final String INPUT = "input";
    static final String WITHDRAWN = "withdrawn";
    static final String PROPOSED = "proposed";
    static final String PUBLISHED = "published";
    static final String PENDING = "pending";
    static final String DECIDED = "decided";
    static final String TREE_VERSIONS = "treeVersions";
    static final String PAGES = "pages";
    static final String PUBLICATIONS = "publications";

    /** Publication states the first launch takes as in effect. */
    static final Set<String> IN_EFFECT = Set.of(PublicationStatus.LIVE, PublicationStatus.SCHEDULED);

    private QbFirstLaunchProcesses() {}

    private static ProcessContext start(com.jabiz.process.ProcessStart start, Object input) {
        ProcessContext ctx = new ProcessContext(start);
        ctx.put(INPUT, input);
        return ctx;
    }

    public static ProcessDefinition<ParamInput, ControlChanges.ChangeOutput, ProcessContext> param() {
        return ProcessDefinition.define(PARAM, 1, ParamInput.class, ControlChanges.ChangeOutput.class,
            ProcessContext.class, pb -> pb
                .description("First launch: a controlled parameter's initial value, proposed and published (D82).")
                .permissions(QbPermissions.SETUP)
                .internal()
                .contextFactory(QbFirstLaunchProcesses::start)
                .outputMapper(ctx -> ctx.get(PUBLISHED, ControlChanges.ChangeOutput.class))
                .step("Withdraw the setup's proposal", CallProcess.<ProcessContext>of(ControlChanges.WITHDRAW, 1,
                    ctx -> new ControlChanges.ChangeInput(input(ctx).withdraw()), WITHDRAWN))
                .step("Propose the value", CallProcess.<ProcessContext>of(ControlChanges.PROPOSE, 1, ctx -> {
                    ParamInput input = input(ctx);
                    Map<String, Object> values = new java.util.LinkedHashMap<>();
                    values.put(com.jabiz.runtime.param.ParamEntities.KEY, input.key());
                    values.put(com.jabiz.runtime.param.ParamEntities.KIND, input.kind());
                    values.put(com.jabiz.runtime.param.ParamEntities.VALUE, input.value());
                    values.put(com.jabiz.runtime.param.ParamEntities.DESCRIPTION, input.description());
                    return new ControlChanges.ProposeInput(com.jabiz.runtime.param.ParamEntities.ENTITY, null, false,
                        values, null, input.reason());
                }, PROPOSED))
                .step("Publish it", CallProcess.<ProcessContext>of(ControlChanges.FIRST_LAUNCH_PUBLISH, 1,
                    ctx -> new ControlChanges.ChangeInput(UUID.fromString(
                        ctx.get(PROPOSED, ControlChanges.ChangeOutput.class).changeId())), PUBLISHED)));
    }

    public static ProcessDefinition<ApproveInput, ApproveOutput, ProcessContext> approve() {
        return ProcessDefinition.define(APPROVE, 1, ApproveInput.class, ApproveOutput.class, ProcessContext.class, pb -> pb
            .description("First launch: approves every pending approval request the run made (D82).")
            .permissions(QbPermissions.SETUP)
            .internal()
            .contextFactory(QbFirstLaunchProcesses::start)
            .outputMapper(ctx -> new ApproveOutput(pending(ctx)))
            .step("Load the pending requests", QueryEntities.<ProcessContext>of(ApprovalEntities.REQUEST_DATASET,
                ctx -> EntityQuery.builder().where(new QueryPredicate.Eq("status", ApprovalEntities.PENDING))
                    .limit(500).build(), PENDING))
            .step("Approve each", CallProcess.<ProcessContext>forEach(ApprovalProcesses.FIRST_LAUNCH_DECIDE, 1,
                ctx -> pending(ctx).stream()
                    .map(id -> new ApprovalProcesses.FirstLaunchDecideInput(UUID.fromString(id))).toList(),
                DECIDED)));
    }

    public static ProcessDefinition<ReadyInput, Boolean, ProcessContext> ready() {
        return ProcessDefinition.define(READY, 1, ReadyInput.class, Boolean.class, ProcessContext.class, pb -> pb
            .description("First launch: whether the graphs, pages and publications it made are in effect.")
            .permissions(QbPermissions.SETUP)
            .internal()
            .contextFactory(QbFirstLaunchProcesses::start)
            .outputMapper(QbFirstLaunchProcesses::isReady)
            .step("Load them", QueryEntities.<ProcessContext>all(
                QueryEntities.query(QbTrees.defaultDataset(QbTrees.VERSION), ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.And(List.of(
                        new QueryPredicate.In("treeId", new ArrayList<>(ready(ctx).treeIds())),
                        new QueryPredicate.Eq("status", QbTrees.ACTIVE)))).limit(1000).build(), TREE_VERSIONS),
                QueryEntities.query(QbSponsorPages.DEFAULT_DATASET, ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.In("userId", new ArrayList<>(ready(ctx).pageUsers()))).limit(1000)
                    .build(), PAGES),
                QueryEntities.query(QbPublications.defaultDataset(QbPublications.PUBLICATION),
                    ctx -> EntityQuery.builder().where(new QueryPredicate.In("publicationId",
                        ready(ctx).publicationIds().stream().map(String::valueOf).map(Object.class::cast).toList()))
                        .limit(1000).build(), PUBLICATIONS))));
    }

    public static ProcessDefinition<LeadInput, Boolean, ProcessContext> lead() {
        return ProcessDefinition.define(LEAD, 1, LeadInput.class, Boolean.class, ProcessContext.class, pb -> pb
            .description("First launch: a new operator's lead the run made may not have verified the address yet.")
            .permissions(QbPermissions.OPERATORS_WRITE)
            .subProcessOnly()
            .contextFactory(QbFirstLaunchProcesses::start)
            .outputMapper(ctx -> Boolean.TRUE)
            .step("Check the first launch's power", FirstLaunchCheck.of("sec_user_version", "user_id",
                ctx -> ctx.get(INPUT, LeadInput.class).userId())));
    }

    private static ParamInput input(ProcessContext ctx) {
        return ctx.get(INPUT, ParamInput.class);
    }

    private static ReadyInput ready(ProcessContext ctx) {
        return ctx.get(INPUT, ReadyInput.class);
    }

    @SuppressWarnings("unchecked")
    private static List<EntityInstance> list(ProcessContext ctx, String key) {
        return ctx.contains(key) ? (List<EntityInstance>) ctx.get(key) : List.of();
    }

    private static List<String> pending(ProcessContext ctx) {
        return list(ctx, PENDING).stream().map(row -> String.valueOf((Object) row.get("requestId"))).toList();
    }

    static Boolean isReady(ProcessContext ctx) {
        ReadyInput input = ready(ctx);
        Set<String> trees = new java.util.HashSet<>();
        list(ctx, TREE_VERSIONS).forEach(row -> trees.add(String.valueOf((Object) row.get("treeId"))));
        long pages = list(ctx, PAGES).stream()
            .filter(row -> QbSponsorPages.PUBLISHED.equals(row.get("status"))).count();
        long publications = list(ctx, PUBLICATIONS).stream()
            .filter(row -> IN_EFFECT.contains(String.valueOf((Object) row.get("status")))).count();
        return trees.containsAll(input.treeIds()) && pages >= input.pageUsers().size()
            && publications >= input.publicationIds().size();
    }

    /** Whether a save of an operator runs as the system (only the first launch's step does). */
    static boolean system(ProcessContext ctx) {
        return RequestContext.SYSTEM_ACTOR.equals(ctx.request().actorId());
    }
}
