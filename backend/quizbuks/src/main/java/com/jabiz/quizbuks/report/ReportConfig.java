package com.jabiz.quizbuks.report;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.dictionary.StaticDictionary;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.operator.OperatorScopes;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers takers' reports of publications (Q49a): the entity, its dataset (headquarters' and operators' reviewers,
 * an operator's session limited to its own sponsors, Q59c-2; written by the processes only), the dictionaries and the
 * processes.
 */
@Configuration
public class ReportConfig {

    private final String poolRef;

    ReportConfig(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.poolRef = poolRef;
    }

    @Bean
    EntityDefinition qbReportEntity() {
        return QbReports.ENTITY;
    }

    @Bean
    DatasetDefinition qbReportDataset() {
        return DatasetDefinition.define(QbReports.DEFAULT_DATASET, d -> d
            .targetEntityType(QbReports.REPORT)
            .asDefault()
            .permissions(QbPermissions.ADMIN_PUBLICATION_READ, QbPermissions.ADMIN_CONTENT_WRITE)
            .scope(OperatorScopes.of(QbReports.REPORT))
            .policy(p -> p.processOnlyWrites())
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    @Bean
    StaticDictionary qbReportReasonDictionary() {
        return StaticDictionary.define(QbReports.REASON_DICTIONARY, d -> d
            .item(QbReports.MISLEADING, "en", "False or misleading", "zh", "虚假或误导", "ja", "虚偽・誤解を招く内容")
            .item(QbReports.INAPPROPRIATE, "en", "Offensive or inappropriate", "zh", "冒犯或不当内容",
                "ja", "不快・不適切な内容")
            .item(QbReports.REWARD_NOT_GIVEN, "en", "Reward not given", "zh", "未兑现奖励", "ja", "報酬が支払われない")
            .item(QbReports.SPAM, "en", "Spam or advertising", "zh", "垃圾信息或广告", "ja", "スパム・宣伝")
            .item(QbReports.OTHER, "en", "Something else", "zh", "其他", "ja", "その他"));
    }

    @Bean
    StaticDictionary qbReportStatusDictionary() {
        return StaticDictionary.define(QbReports.STATUS_DICTIONARY, d -> d
            .item(QbReports.OPEN, "en", "Open", "zh", "待处理", "ja", "未対応")
            .item(QbReports.DISMISSED, "en", "Dismissed", "zh", "已驳回", "ja", "対応不要")
            .item(QbReports.ACTIONED, "en", "Acted on", "zh", "已处理", "ja", "対応済み"));
    }

    @Bean
    ProcessDefinition<ReportProcesses.SubmitInput, ReportProcesses.SubmitOutput, ProcessContext> qbReportSubmit() {
        return ReportProcesses.submit();
    }

    @Bean
    ProcessDefinition<ReportProcesses.ResolveInput, ReportProcesses.ResolveOutput, ProcessContext> qbReportResolve() {
        return ReportProcesses.resolve();
    }

    @Bean
    ProcessDefinition<ReportProcesses.ResolveInput, ReportProcesses.ResolveOutput, ProcessContext>
        qbOperatorReportResolve() {
        return ReportProcesses.operatorResolve();
    }
}
