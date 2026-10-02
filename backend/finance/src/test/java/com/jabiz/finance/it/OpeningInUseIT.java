package com.jabiz.finance.it;

import com.jabiz.finance.gl.GlEntities;
import com.jabiz.finance.gl.OpeningProcesses;
import com.jabiz.finance.setup.FinanceRoles;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Books in use are opened no more (FIN-PC-002): once a period is closed, an opening entry is refused. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class OpeningInUseIT extends FinanceItSupport {

    @Test
    void aClosedPeriodEndsTheOpening() {
        loadSampleChart();
        ok("FIN_FISCAL_YEAR_CREATE", controller(), Map.of("fiscalYear", 2026, "adjustmentPeriod", true));
        closePeriod("2026-01");
        Map<String, Object> refused = importCsv("finance.opening_balances",
            inRoles("migrating-controller", FinanceRoles.CONTROLLER), sampleText("opening-balances.csv"), "commit",
            null, null, 422);
        assertThat(issues(refused)).contains("1:" + OpeningProcesses.BOOKS_IN_USE);
        assertThat(find(GlEntities.PERIOD_DATASET, "periodKey", "2026-00")).isEmpty();
    }
}
