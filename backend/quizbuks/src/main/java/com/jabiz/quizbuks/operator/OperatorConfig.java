package com.jabiz.quizbuks.operator;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.approval.ApprovalSubject;
import com.jabiz.dictionary.StaticDictionary;
import com.jabiz.document.DocumentLayout;
import com.jabiz.event.EventSubscription;
import com.jabiz.job.JobDefinition;
import com.jabiz.job.JobItems;
import com.jabiz.quizbuks.wallet.TransferProcesses;
import com.jabiz.runtime.approval.ApprovalProcesses;
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

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
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
    private final ZoneId zone;

    OperatorConfig(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef,
        @Value("${jabiz.fiscal-year-zone:UTC}") String zone) {
        this.poolRef = poolRef;
        ZoneId zoneId;
        try {
            zoneId = ZoneId.of(zone);
        } catch (RuntimeException e) {
            zoneId = ZoneOffset.UTC;
        }
        this.zone = zoneId;
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

    // ---- Q48b: the monthly settlements ---------------------------------------------------------------------------------

    @Bean
    EntityDefinition qbOperatorSettlementEntity() {
        return OperatorSettlements.SETTLEMENT_ENTITY;
    }

    /** Finance reads them (and the operator through its own template); only the processes write them. */
    @Bean
    DatasetDefinition qbOperatorSettlementDataset() {
        return DatasetDefinition.define(QbOperators.defaultDataset(OperatorSettlements.SETTLEMENT), d -> d
            .targetEntityType(OperatorSettlements.SETTLEMENT)
            .asDefault()
            .permissions(QbPermissions.ADMIN_FINANCE_READ, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.processOnlyWrites())
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    @Bean
    StaticDictionary qbOperatorSettlementStatusDictionary() {
        return StaticDictionary.define(OperatorSettlements.STATUS_DICTIONARY, d -> d
            .item(OperatorSettlements.ISSUED, "en", "Issued", "zh", "已出具", "ja", "発行済み")
            .item(OperatorSettlements.PAYING, "en", "Payment in approval", "zh", "付款审批中", "ja", "支払い承認待ち")
            .item(OperatorSettlements.PAID, "en", "Paid", "zh", "已付款", "ja", "支払い済み"));
    }

    @Bean
    DocumentLayout qbOperatorSettlementLayout() {
        return OperatorSettlements.LAYOUT;
    }

    @Bean
    ApprovalSubject qbOperatorSettlementApprovalSubject() {
        return ApprovalSubject.define(OperatorSettlements.APPROVAL_SUBJECT, s -> s.entity(OperatorSettlements.SETTLEMENT)
            .bool("operatorPayment").number("amount").text("operatorId"));
    }

    @Bean
    ProcessDefinition<OperatorSettlements.RunInput, OperatorSettlements.SettlementOutput, ProcessContext>
        qbOperatorSettlementRun() {
        return OperatorSettlements.run(zone);
    }

    @Bean
    ProcessDefinition<OperatorSettlements.StatementInput, OperatorSettlements.SettlementOutput, ProcessContext>
        qbOperatorSettlementStatement() {
        return OperatorSettlements.statement();
    }

    @Bean
    ProcessDefinition<OperatorSettlements.PayInput, OperatorSettlements.SettlementOutput, ProcessContext>
        qbOperatorSettlementPay() {
        return OperatorSettlements.pay();
    }

    @Bean
    ProcessDefinition<TransferProcesses.ApprovalResultInput, OperatorSettlements.SettlementOutput, ProcessContext>
        qbOperatorSettlementApprovalResult() {
        return OperatorSettlements.approvalResult();
    }

    @Bean
    EventSubscription<TransferProcesses.ApprovalResultInput> qbOperatorSettlementApprovedSubscription(
        ProcessDefinition<TransferProcesses.ApprovalResultInput, OperatorSettlements.SettlementOutput, ProcessContext>
            qbOperatorSettlementApprovalResult) {
        return EventSubscription.of("qb.operator-settlement-approved", ApprovalProcesses.APPROVED_EVENT,
            qbOperatorSettlementApprovalResult, TransferProcesses::resultInput);
    }

    @Bean
    EventSubscription<TransferProcesses.ApprovalResultInput> qbOperatorSettlementRejectedSubscription(
        ProcessDefinition<TransferProcesses.ApprovalResultInput, OperatorSettlements.SettlementOutput, ProcessContext>
            qbOperatorSettlementApprovalResult) {
        return EventSubscription.of("qb.operator-settlement-rejected", ApprovalProcesses.REJECTED_EVENT,
            qbOperatorSettlementApprovalResult, TransferProcesses::resultInput);
    }

    /**
     * On the 1st of every month at 16:13 UTC (01:13 on the 2nd in Tokyo): each operator's previous month (Japan's) is
     * settled, one operator a transaction; a missed run is made up by the next, which settles only its own month.
     */
    @Bean
    JobDefinition<OperatorSettlements.RunInput> qbOperatorSettlementJob(
        ProcessDefinition<OperatorSettlements.RunInput, OperatorSettlements.SettlementOutput, ProcessContext>
            qbOperatorSettlementRun) {
        return JobDefinition.forEach(OperatorSettlements.JOB, "0 13 16 1 * *", ZoneOffset.UTC,
                JobItems.template(OperatorSettlements.DUE_TEMPLATE, scheduledTime -> {
                    String month = OperatorSettlements.previousMonth(scheduledTime, zone);
                    return Map.of("month", month, "periodEnd", OperatorSettlements.monthEnd(month, zone));
                }, "operatorId"),
                qbOperatorSettlementRun,
                (scheduledTime, row) -> new OperatorSettlements.RunInput(String.valueOf(row.get("operatorId")),
                    OperatorSettlements.previousMonth(scheduledTime, zone)))
            .maxItems(1_000);
    }
}
