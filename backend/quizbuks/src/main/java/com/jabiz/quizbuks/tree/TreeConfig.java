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
}
