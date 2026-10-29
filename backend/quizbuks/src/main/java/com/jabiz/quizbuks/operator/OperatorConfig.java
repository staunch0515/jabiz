package com.jabiz.quizbuks.operator;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.dictionary.StaticDictionary;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.identity.AccountEditing;
import com.jabiz.quizbuks.identity.BanProcesses;
import com.jabiz.quizbuks.publishing.PublicationAdmin;
import com.jabiz.quizbuks.publishing.PublicationOutput;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Registers operators (docs/quizbuks/plans/Q48-operators.md, Q48a): the four entities and their default datasets,
 * read by headquarters ({@code qb.admin.operators.read}) and written by the processes only; the three dictionaries;
 * headquarters' processes ({@link OperatorProcesses}) and the operator's acts (the takedowns of
 * {@link PublicationAdmin} and the bans of {@link BanProcesses}, each in the operator's form). Operators read their
 * own data through templates ({@code queries/qb/operator}), never through these datasets.
 */
@Configuration
public class OperatorConfig {

    private final String poolRef;

    OperatorConfig(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.poolRef = poolRef;
    }

    private DatasetDefinition defaultDataset(String entity) {
        return DatasetDefinition.define(QbOperators.defaultDataset(entity), d -> d
            .targetEntityType(entity)
            .asDefault()
            .permissions(QbPermissions.OPERATORS_READ, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.processOnlyWrites())
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    @Bean
    EntityDefinition qbOperatorEntity() {
        return QbOperators.OPERATOR_ENTITY;
    }

    @Bean
    EntityDefinition qbOperatorMemberEntity() {
        return QbOperators.MEMBER_ENTITY;
    }

    @Bean
    EntityDefinition qbSponsorOperatorEntity() {
        return QbOperators.ASSIGNMENT_ENTITY;
    }

    @Bean
    EntityDefinition qbReviewSpotCheckEntity() {
        return QbOperators.SPOT_CHECK_ENTITY;
    }

    @Bean
    DatasetDefinition qbOperatorDataset() {
        return defaultDataset(QbOperators.OPERATOR);
    }

    @Bean
    DatasetDefinition qbOperatorMemberDataset() {
        return defaultDataset(QbOperators.MEMBER);
    }

    @Bean
    DatasetDefinition qbSponsorOperatorDataset() {
        return defaultDataset(QbOperators.ASSIGNMENT);
    }

    @Bean
    DatasetDefinition qbReviewSpotCheckDataset() {
        return defaultDataset(QbOperators.SPOT_CHECK);
    }

    /**
     * The caller's own memberships, read only and without history: the operator's templates start from it to know
     * which operator the caller acts for ({@code qb.operator.use}).
     */
    @Bean
    DatasetDefinition qbOwnOperatorMemberDataset() {
        return DatasetDefinition.define(QbOperators.OWN_MEMBERSHIP_DATASET, d -> d
            .targetEntityType(QbOperators.MEMBER)
            .permissions(QbPermissions.OPERATOR_USE, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.readOnly(true).allowTimeTravel(false))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef))
            .scope(s -> s.fromContext("userId", "actorId", context -> AccountEditing.uuidOrNull(context.actorId()))));
    }

    @Bean
    StaticDictionary qbOperatorStatusDictionary() {
        return StaticDictionary.define(QbOperators.STATUS_DICTIONARY, d -> d
            .item(QbOperators.ACTIVE, "en", "Active", "zh", "运营中", "ja", "稼働中")
            .item(QbOperators.SUSPENDED, "en", "Suspended", "zh", "已停用", "ja", "停止中"));
    }

    @Bean
    StaticDictionary qbOperatorCollectionDictionary() {
        return StaticDictionary.define(QbOperators.COLLECTION_DICTIONARY, d -> d
            .item(QbOperators.PLATFORM, "en", "Collected by the platform, settled monthly", "zh", "平台代收、按月结算",
                "ja", "プラットフォームが代理受領し月次で精算")
            .item(QbOperators.DIRECT, "en", "Collected by the operator", "zh", "运营商直接收款",
                "ja", "運営パートナーが直接受領"));
    }

    @Bean
    StaticDictionary qbSpotCheckResultDictionary() {
        return StaticDictionary.define(QbOperators.SPOT_CHECK_DICTIONARY, d -> d
            .item(QbOperators.CHECK_OK, "en", "Checked", "zh", "已抽查", "ja", "確認済み")
            .item(QbOperators.CHECK_PROBLEM, "en", "Problem", "zh", "有问题", "ja", "問題あり"));
    }

    // ---- headquarters ------------------------------------------------------------------------------------------------

    @Bean
    ProcessDefinition<OperatorProcesses.SaveInput, OperatorProcesses.SaveOutput, ProcessContext> qbOperatorSave() {
        return OperatorProcesses.save();
    }

    @Bean
    ProcessDefinition<OperatorProcesses.MemberInput, OperatorProcesses.MemberOutput, ProcessContext>
        qbOperatorMemberAdd() {
        return OperatorProcesses.memberAdd();
    }

    @Bean
    ProcessDefinition<OperatorProcesses.MemberRef, OperatorProcesses.MemberOutput, ProcessContext>
        qbOperatorMemberRemove() {
        return OperatorProcesses.memberRemove();
    }

    @Bean
    ProcessDefinition<OperatorProcesses.AssignInput, OperatorProcesses.AssignOutput, ProcessContext>
        qbSponsorOperatorAssign() {
        return OperatorProcesses.assign();
    }

    @Bean
    ProcessDefinition<OperatorProcesses.SpotCheckInput, OperatorProcesses.SpotCheckOutput, ProcessContext>
        qbReviewSpotCheck() {
        return OperatorProcesses.spotCheck();
    }

    // ---- the operator's own acts -------------------------------------------------------------------------------------

    @Bean
    ProcessDefinition<OperatorProcesses.TeamInput, OperatorProcesses.TeamOutput, ProcessContext> qbOperatorTeam() {
        return OperatorProcesses.team();
    }

    @Bean
    ProcessDefinition<OperatorProcesses.MemberRef, OperatorProcesses.MemberOutput, ProcessContext>
        qbOperatorMemberDrop() {
        return OperatorProcesses.memberDrop();
    }

    @Bean
    ProcessDefinition<PublicationAdmin.AdminInput, PublicationOutput, ProcessContext> qbOperatorPublicationPause() {
        return PublicationAdmin.operatorPause();
    }

    @Bean
    ProcessDefinition<PublicationAdmin.AdminInput, PublicationOutput, ProcessContext> qbOperatorPublicationResume() {
        return PublicationAdmin.operatorResume();
    }

    @Bean
    ProcessDefinition<PublicationAdmin.AdminInput, PublicationOutput, ProcessContext> qbOperatorPublicationEnd() {
        return PublicationAdmin.operatorEnd();
    }

    /** As {@code QB_USER_BAN}: administrators (the roles the administration's entry accepts) are not banned. */
    @Bean
    ProcessDefinition<BanProcesses.BanInput, BanProcesses.BanOutput, ProcessContext> qbOperatorSponsorBan(
        Environment environment) {
        List<String> admins = Binder.get(environment).bind("jabiz.security.entries.admin.accepted-roles",
            Bindable.listOf(String.class)).orElse(List.of("*"));
        return BanProcesses.operatorBan(admins.stream().map(String::strip).filter(code -> !code.isEmpty())
            .collect(Collectors.toSet()));
    }

    @Bean
    ProcessDefinition<BanProcesses.UnbanInput, BanProcesses.BanOutput, ProcessContext> qbOperatorSponsorUnban() {
        return BanProcesses.operatorUnban();
    }
}
