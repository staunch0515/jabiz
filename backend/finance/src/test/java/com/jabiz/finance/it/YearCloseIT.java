package com.jabiz.finance.it;

import com.jabiz.finance.gl.PeriodProcesses;
import com.jabiz.finance.gl.YearCloseProcesses;
import com.jabiz.finance.setup.FinanceRoles;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.YearMonth;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the year-end close (FIN-PC-008) waits for: the retained earnings account set, the year in the calendar with
 * its adjustment period 13, and the year before closed when the books hold it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class YearCloseIT extends FinanceItSupport {

    @Test
    void theYearCloseWaitsForWhatItNeeds() {
        String controller = inRoles("controller", FinanceRoles.CONTROLLER);
        openBooks();
        assertThat(refused(YearCloseProcesses.YEAR_CLOSE, controller, Map.of("fiscalYear", 2026), 422))
            .isEqualTo(YearCloseProcesses.NO_SETTINGS);
        assertThat(refused(YearCloseProcesses.SETTINGS_SET, controller, Map.of("retainedEarningsAccount", "9999"),
            422)).isEqualTo(YearCloseProcesses.SETTINGS_ACCOUNT);
        assertThat(ok(YearCloseProcesses.SETTINGS_SET, controller, Map.of("retainedEarningsAccount", "3200")))
            .containsEntry("retainedEarningsAccount", "3200");
        assertThat(refused(YearCloseProcesses.YEAR_CLOSE, controller, Map.of("fiscalYear", 2030), 422))
            .isEqualTo(YearCloseProcesses.NO_YEAR);
        ok(PeriodProcesses.FISCAL_YEAR_CREATE, controller(), Map.of("fiscalYear", 2027));
        assertThat(refused(YearCloseProcesses.YEAR_CLOSE, controller, Map.of("fiscalYear", 2027), 422))
            .isEqualTo(YearCloseProcesses.NO_ADJUSTMENT_PERIOD);

        // 2028's months all closed while 2027 is not: 2027 first.
        ok(PeriodProcesses.FISCAL_YEAR_CREATE, controller(), Map.of("fiscalYear", 2028, "adjustmentPeriod", true));
        for (int month = 1; month <= 12; month++) {
            closePeriod(YearMonth.of(2028, month).toString());
        }
        controller = inRoles("controller", FinanceRoles.CONTROLLER);
        assertThat(refused(YearCloseProcesses.YEAR_CLOSE, controller, Map.of("fiscalYear", 2028), 422))
            .isEqualTo(YearCloseProcesses.EARLIER_OPEN);
    }
}
