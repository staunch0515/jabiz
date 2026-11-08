package com.jabiz.quizbuks.tree;

import com.jabiz.approval.ApprovalSubject;
import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.dictionary.StaticDictionary;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.event.EventSubscription;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.runtime.approval.ApprovalProcesses;
import com.jabiz.context.RequestContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static com.jabiz.quizbuks.tree.TreeProcesses.ApprovalResultInput;
import static com.jabiz.quizbuks.tree.TreeProcesses.TreeOutput;

/**
 * Knowledge graphs (F-S2a): the four entities, their default datasets (the platform's administrators read every
 * graph; Sponsors and operators read theirs through their templates; nobody writes through them), the dictionaries,
 * the approval subject with both subscriptions, and the processes.
 */
@Configuration
public class TreeConfig {

    private static final ProcessDefinition<ApprovalResultInput, TreeOutput, ProcessContext> APPROVAL_RESULT =
        TreeProcesses.approvalResult();

    private final String poolRef;

    TreeConfig(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.poolRef = poolRef;
    }

    private DatasetDefinition defaultDataset(String entity) {
        return DatasetDefinition.define(QbTrees.defaultDataset(entity), d -> d
            .targetEntityType(entity)
            .asDefault()
            .permissions(QbPermissions.TREES_READ, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.processOnlyWrites())
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    @Bean
    EntityDefinition qbTreeEntity() {
        return QbTrees.TREE_ENTITY;
    }

    @Bean
    EntityDefinition qbTreeVersionEntity() {
        return QbTrees.VERSION_ENTITY;
    }

    @Bean
    EntityDefinition qbTreeNodeEntity() {
        return QbTrees.NODE_ENTITY;
    }

    @Bean
    EntityDefinition qbTreeNodeDefEntity() {
        return QbTrees.NODE_DEF_ENTITY;
    }

    @Bean
    DatasetDefinition qbTreeDataset() {
        return defaultDataset(QbTrees.TREE);
    }

    @Bean
    DatasetDefinition qbTreeVersionDataset() {
        return defaultDataset(QbTrees.VERSION);
    }

    @Bean
    DatasetDefinition qbTreeNodeDataset() {
        return defaultDataset(QbTrees.NODE);
    }

    @Bean
    DatasetDefinition qbTreeNodeDefDataset() {
        return defaultDataset(QbTrees.NODE_DEF);
    }

    // ---- F-S2b1: references, lit records and revocations ------------------------------------------------------------

    @Bean
    EntityDefinition qbTreeNodeRefEntity() {
        return QbTrees.NODE_REF_ENTITY;
    }

    @Bean
    EntityDefinition qbTreeLitEntity() {
        return QbTrees.LIT_ENTITY;
    }

    @Bean
    EntityDefinition qbTreeLitRevocationEntity() {
        return QbTrees.REVOCATION_ENTITY;
    }

    @Bean
    DatasetDefinition qbTreeNodeRefDataset() {
        return defaultDataset(QbTrees.NODE_REF);
    }

    @Bean
    DatasetDefinition qbTreeLitDataset() {
        return defaultDataset(QbTrees.LIT);
    }

    @Bean
    DatasetDefinition qbTreeLitRevocationDataset() {
        return defaultDataset(QbTrees.REVOCATION);
    }

    /** A person's own lit records (a Taker's: the subject is the caller as a person), for the templates and a start. */
    @Bean
    DatasetDefinition qbMyTreeLitDataset() {
        return mine(QbTrees.MY_LITS, QbTrees.LIT);
    }

    @Bean
    DatasetDefinition qbMyTreeLitRevocationDataset() {
        return mine(QbTrees.MY_REVOCATIONS, QbTrees.REVOCATION);
    }

    private DatasetDefinition mine(String id, String entity) {
        return DatasetDefinition.define(id, d -> d
            .targetEntityType(entity)
            .permissions(QbPermissions.TREES_READ, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.readOnly(true).allowTimeTravel(false))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef))
            .scope(s -> s.fromContext("subjectId", "actorId", RequestContext::actorId)
                .fixed("subjectKind", QbTrees.USER)));
    }

    @Bean
    StaticDictionary qbTreeLitSubjectDictionary() {
        return StaticDictionary.define(QbTrees.LIT_SUBJECT_DICTIONARY, d -> d
            .item(QbTrees.USER, "en", "A Taker", "zh", "学者", "ja", "テイカー")
            .item(QbTrees.SPONSOR, "en", "A Sponsor", "zh", "发起方", "ja", "主催者"));
    }

    @Bean
    StaticDictionary qbTreeOwnerDictionary() {
        return StaticDictionary.define(QbTrees.OWNER_DICTIONARY, d -> d
            .item(QbTrees.PLATFORM, "en", "The platform's template", "zh", "平台模板", "ja", "プラットフォームのテンプレート")
            .item(QbTrees.OPERATOR, "en", "An operator's copy", "zh", "运营商的副本", "ja", "運営パートナーのコピー")
            .item(QbTrees.SPONSOR, "en", "A Sponsor's", "zh", "发起方的", "ja", "主催者の"));
    }

    @Bean
    StaticDictionary qbTreeSubjectDictionary() {
        return StaticDictionary.define(QbTrees.SUBJECT_DICTIONARY, d -> d
            .item(QbTrees.TAKER, "en", "Takers", "zh", "学者", "ja", "テイカー")
            .item(QbTrees.SPONSOR, "en", "Sponsors", "zh", "发起方", "ja", "主催者"));
    }

    @Bean
    StaticDictionary qbTreeStatusDictionary() {
        return StaticDictionary.define(QbTrees.STATUS_DICTIONARY, d -> d
            .item(QbTrees.ACTIVE, "en", "In use", "zh", "使用中", "ja", "使用中")
            .item(QbTrees.RETIRED, "en", "Retired", "zh", "已停用", "ja", "停止"));
    }

    @Bean
    StaticDictionary qbTreeVersionStatusDictionary() {
        return StaticDictionary.define(QbTrees.VERSION_STATUS_DICTIONARY, d -> d
            .item(QbTrees.DRAFT, "en", "Draft", "zh", "草稿", "ja", "下書き")
            .item(QbTrees.REVIEW, "en", "In review", "zh", "审核中", "ja", "審査中")
            .item(QbTrees.IN_EFFECT, "en", "In effect", "zh", "生效", "ja", "有効")
            .item(QbTrees.SUPERSEDED, "en", "Superseded", "zh", "已被取代", "ja", "旧版")
            .item(QbTrees.REJECTED, "en", "Sent back", "zh", "已驳回", "ja", "差し戻し"));
    }

    @Bean
    ApprovalSubject qbTreeApprovalSubject() {
        return TreeApprovals.subject();
    }

    @Bean
    EventSubscription<ApprovalResultInput> qbTreeApprovedSubscription() {
        return EventSubscription.of("qb.tree-approved", ApprovalProcesses.APPROVED_EVENT, APPROVAL_RESULT,
            TreeApprovals::resultInput);
    }

    @Bean
    EventSubscription<ApprovalResultInput> qbTreeRejectedSubscription() {
        return EventSubscription.of("qb.tree-rejected", ApprovalProcesses.REJECTED_EVENT, APPROVAL_RESULT,
            TreeApprovals::resultInput);
    }

    @Bean
    ProcessDefinition<ApprovalResultInput, TreeOutput, ProcessContext> qbTreeApprovalResult() {
        return APPROVAL_RESULT;
    }

    @Bean
    ProcessDefinition<TreeProcesses.CreateInput, TreeOutput, ProcessContext> qbTreeCreate() {
        return TreeProcesses.create();
    }

    @Bean
    ProcessDefinition<TreeProcesses.CopyInput, TreeOutput, ProcessContext> qbTreeCopy() {
        return TreeProcesses.copy();
    }

    @Bean
    ProcessDefinition<TreeProcesses.SaveInput, TreeOutput, ProcessContext> qbTreeSave() {
        return TreeProcesses.save();
    }

    @Bean
    ProcessDefinition<TreeProcesses.AdoptInput, TreeOutput, ProcessContext> qbTreeAdopt() {
        return TreeProcesses.adopt();
    }

    @Bean
    ProcessDefinition<TreeProcesses.TreeInput, TreeOutput, ProcessContext> qbTreeSubmit() {
        return TreeProcesses.submit();
    }

    @Bean
    ProcessDefinition<TreeProcesses.TreeInput, TreeOutput, ProcessContext> qbTreeWithdraw() {
        return TreeProcesses.withdraw();
    }

    @Bean
    ProcessDefinition<TreeProcesses.TreeInput, TreeOutput, ProcessContext> qbTreeRetire() {
        return TreeProcesses.retire();
    }

    private static final ProcessDefinition<TreeLighting.EvaluateInput, TreeLighting.EvaluateOutput, ProcessContext>
        EVALUATE = TreeLighting.evaluate();

    @Bean
    ProcessDefinition<TreeLighting.EvaluateInput, TreeLighting.EvaluateOutput, ProcessContext> qbTreeEvaluate() {
        return EVALUATE;
    }

    /** A pass of a publication bound to a node evaluates the node (F-S2b1). */
    @Bean
    EventSubscription<TreeLighting.EvaluateInput> qbTreePassedSubscription() {
        return EventSubscription.of(TreeLighting.PASSED_CONSUMER,
            com.jabiz.quizbuks.play.AttemptFinishing.TREE_PASSED_EVENT, EVALUATE, TreeLighting::passedInput);
    }

    /** Nodes lit evaluate the nodes depending on them, one level per transaction. */
    @Bean
    EventSubscription<TreeLighting.EvaluateInput> qbTreeLitSubscription() {
        return EventSubscription.of(TreeLighting.LIT_CONSUMER, TreeLighting.LIT_EVENT, EVALUATE,
            TreeLighting::litInput);
    }
}
