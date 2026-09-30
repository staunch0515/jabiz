package com.jabiz.finance.gl;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountTypesTest {

    @Test
    void financeTypesMapOntoTheLedgersFive() {
        assertThat(AccountTypes.ledgerType("ASSET", "CREDIT")).isEqualTo("ASSET");
        assertThat(AccountTypes.ledgerType("TAX", "DEBIT")).isEqualTo("EXPENSE");
        assertThat(AccountTypes.ledgerType("OTHER", "DEBIT")).isEqualTo("EXPENSE");
        assertThat(AccountTypes.ledgerType("OTHER", "CREDIT")).isEqualTo("REVENUE");
        assertThatThrownBy(() -> AccountTypes.ledgerType("GADGET", "DEBIT"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(AccountTypes.fromChart(" Other ")).isEqualTo("OTHER");
        assertThat(AccountTypes.fromChart(null)).isNull();
        assertThat(AccountTypes.normalBalanceFromChart("c")).isEqualTo("CREDIT");
        assertThatThrownBy(() -> AccountTypes.normalBalanceFromChart("X")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theTemplateIsASoundChart() {
        List<ChartTemplate.Line> lines = ChartTemplate.lines();
        Set<String> seen = new HashSet<>();
        Set<String> summaries = new HashSet<>();
        for (ChartTemplate.Line line : lines) {
            assertThat(seen.add(line.accountCode())).as(line.accountCode()).isTrue();
            assertThat(line.accountCode()).matches(GlEntities.ACCOUNT_CODE_PATTERN);
            assertThat(GlEntities.FINANCIAL_TYPE_VALUES).contains(line.financialType());
            assertThat(line.statementLine()).isNotBlank();
            if (line.parentCode() != null) {
                // A parent comes first and is a summary account.
                assertThat(summaries).as(line.accountCode()).contains(line.parentCode());
            }
            if (line.controlClass() != null) {
                assertThat(GlEntities.CONTROL_CLASS_VALUES).contains(line.controlClass());
            }
            if (line.summary()) {
                summaries.add(line.accountCode());
            }
        }
        assertThat(lines).anySatisfy(l -> assertThat(l.statementLine()).isEqualTo("Accounts receivable, net"));
        assertThat(lines).filteredOn(ChartTemplate.Line::clearing).extracting(ChartTemplate.Line::accountCode)
            .containsExactly("2900");
        assertThat(Csv.fields("a,\"b, \"\"c\"\"\",")).containsExactly("a", "b, \"c\"", "");
    }
}
