package com.jabiz.runtime.query;

import com.jabiz.entity.SemanticKind;
import com.jabiz.i18n.MessageCatalog;
import com.jabiz.query.custom.AdvancedQueryDefinition;
import com.jabiz.query.custom.ReportSpec;
import com.jabiz.runtime.check.CheckProblem;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/** docs/design/19-reports.md section 3.1: a report needs a title in every language; other templates do not. */
class ReportMessagesCheckTest {

    private static final MessageCatalog PLATFORM = new MessageCatalog(List.of(MessageCatalog.PLATFORM_BUNDLE),
        List.of(Locale.of("zh"), Locale.of("ja"), Locale.ENGLISH), Locale.ENGLISH,
        ReportMessagesCheckTest.class.getClassLoader());

    private static AdvancedQueryDefinition query(String id, ReportSpec report) {
        return AdvancedQueryDefinition.define(id, q -> q.returns("one", new SemanticKind.Bool()).report(report)
            .sqlTemplate("SELECT true AS one"));
    }

    @Test
    void everyLanguageMissingAReportTitleIsReported() {
        List<CheckProblem> problems = ReportMessagesCheck.problems(PLATFORM, List.of(
            query("probe.report", ReportSpec.PLAIN),
            query("probe.plain", null),
            // The platform's own reports have their titles.
            query("jabiz.ledger.account_balances", ReportSpec.PLAIN)));

        assertThat(problems).extracting(CheckProblem::location)
            .containsExactlyInAnyOrder("query.probe.report [en]", "query.probe.report [zh]", "query.probe.report [ja]");
        assertThat(problems).allSatisfy(p -> assertThat(p.category()).isEqualTo("MESSAGES"));
    }
}
