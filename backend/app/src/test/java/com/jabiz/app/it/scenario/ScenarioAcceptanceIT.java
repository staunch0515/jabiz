package com.jabiz.app.it.scenario;

import com.jabiz.app.App;
import com.jabiz.runtime.test.scenario.Scenario;
import com.jabiz.runtime.test.scenario.ScenarioReplay;
import com.jabiz.runtime.test.scenario.ScenarioRunner;
import com.jabiz.runtime.test.scenario.SnapshotStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Acceptance of ROADMAP phase 8: the sample scenario replays identically, a scheduled parameter change shows in the
 * results exactly from its effective time, and the snapshot difference after changing a parameter names exactly the
 * data it affects.
 */
class ScenarioAcceptanceIT {

    private static final String MONTHLY_CLOSE = "scenarios/freight/monthly_close.yml";

    /** Acceptance 1: ten consecutive replays, each in a fresh database, give the same (committed) snapshot. */
    @Test
    void theSampleScenarioReplaysIdenticallyTenTimes() {
        Scenario scenario = ScenarioReplay.load(MONTHLY_CLOSE);
        List<String> snapshots = new ArrayList<>();
        for (int run = 0; run < 10; run++) {
            ScenarioRunner.Result result = ScenarioReplay.replay(App.class, scenario);
            snapshots.add(SnapshotStore.render(SnapshotStore.document(result)));
            SnapshotStore.verify(result);
        }
        assertThat(snapshots).hasSize(10).containsOnly(snapshots.getFirst());
    }

    /**
     * Acceptance 2: with a rate change scheduled for 1 February, waybills shipped before and after it are charged at
     * different rates; without the schedule both use the old rate.
     */
    @Test
    void aScheduledRateChangeAppliesFromItsEffectiveTime() {
        ScenarioRunner.Result scheduled = ScenarioReplay.replay(App.class, rates("0.1", true));
        assertThat(charge(scheduled, "chargeA")).containsEntry("surchargeRate", 0.1).containsEntry("surchargeAmount", 2000);
        assertThat(charge(scheduled, "chargeB")).containsEntry("surchargeRate", 0.15)
            .containsEntry("surchargeAmount", 4500);
        // A charged after the change keeps the rate of its shipping time.
        assertThat(charge(scheduled, "chargeLate")).containsEntry("surchargeRate", 0.1);

        ScenarioRunner.Result unscheduled = ScenarioReplay.replay(App.class, rates("0.1", false));
        assertThat(charge(unscheduled, "chargeA")).containsEntry("surchargeRate", 0.1);
        assertThat(charge(unscheduled, "chargeB")).containsEntry("surchargeRate", 0.1)
            .containsEntry("surchargeAmount", 3000);
    }

    /**
     * Acceptance 3: changing the January rate from 10 % to 12 % changes exactly the January charges and the January
     * statement; the February charge, the waybills and the parameter's current value stay as they were.
     */
    @Test
    void theSnapshotDiffShowsExactlyWhatAParameterChangeAffects() {
        Map<String, Object> base = SnapshotStore.document(ScenarioReplay.replay(App.class, rates("0.1", true)));
        Map<String, Object> changed = SnapshotStore.document(ScenarioReplay.replay(App.class, rates("0.12", true)));

        List<String> diff = SnapshotStore.differences(SnapshotStore.parse(SnapshotStore.render(base)),
            SnapshotStore.parse(SnapshotStore.render(changed)));
        String chargeA = keyOf(base, "FreightCharge", "waybillId", "WB-A");
        String chargeLate = keyOf(base, "FreightCharge", "waybillId", "WB-L");
        String january = keyOf(base, "FreightStatement", "statementMonth", "2026-01");
        assertThat(diff).containsExactlyInAnyOrder(
            "~ FreightCharge[" + chargeA + "].surchargeRate: 0.1000 → 0.1200",
            "~ FreightCharge[" + chargeA + "].surchargeAmount: 2000 → 2400",
            "~ FreightCharge[" + chargeA + "].totalAmount: 22000 → 22400",
            "~ FreightCharge[" + chargeLate + "].surchargeRate: 0.1000 → 0.1200",
            "~ FreightCharge[" + chargeLate + "].surchargeAmount: 1000 → 1200",
            "~ FreightCharge[" + chargeLate + "].totalAmount: 11000 → 11200",
            "~ FreightStatement[" + january + "].totalAmount: 33000 → 33600");
    }

    /**
     * Waybill A ships on 10 January, B on 2 February, L on 31 January but is charged only in February; January is
     * closed on 3 February. The rate starts at {@code initialRate}; optionally 15 % is scheduled for 1 February
     * (Japan time).
     */
    private static Scenario rates(String initialRate, boolean schedule) {
        String yaml = """
            name: Rates around a scheduled change
            clock: 2026-01-05T00:00:00Z
            actor: { id: billing-admin, permissions: ["*"] }
            steps:
              - process: PARAM_CREATE@latest
                input:
                  key: logistics.fuel-surcharge-rate
                  valueKind: { type: numeric, precision: 5, scale: 4 }
                  value: "%s"
            %s
              - setClock: 2026-01-10T00:00:00Z
              - process: ADD_ENTITY@latest
                input: { entityType: WaybillTracking, attributes: { waybillId: WB-A, freightCharge: 20000,
                         shippedTime: 2026-01-10T00:00:00Z, status: CREATED } }
              - process: FREIGHT_CHARGE@latest
                input: { waybillId: WB-A }
                save: { chargeA: $ }
              - setClock: 2026-01-31T10:00:00Z
              - process: ADD_ENTITY@latest
                input: { entityType: WaybillTracking, attributes: { waybillId: WB-L, freightCharge: 10000,
                         shippedTime: 2026-01-31T10:00:00Z, status: CREATED } }
              - setClock: 2026-02-02T00:00:00Z
              - process: ADD_ENTITY@latest
                input: { entityType: WaybillTracking, attributes: { waybillId: WB-B, freightCharge: 30000,
                         shippedTime: 2026-02-02T00:00:00Z, status: CREATED } }
              - process: FREIGHT_CHARGE@latest
                input: { waybillId: WB-B }
                save: { chargeB: $ }
              - process: FREIGHT_CHARGE@latest
                input: { waybillId: WB-L }
                save: { chargeLate: $ }
              - advanceClock: P1D
              - process: FREIGHT_MONTH_CLOSE@latest
                input: { month: 2026-01 }
            snapshot:
              entities: [SysParam, WaybillTracking, FreightCharge, FreightStatement]
            """.formatted(initialRate, schedule ? """
              - process: PARAM_SCHEDULE@latest
                input: { key: logistics.fuel-surcharge-rate, value: "0.15", effectiveTime: 2026-01-31T15:00:00Z }
            """ : "");
        return Scenario.parse(yaml, "inline:rates-" + initialRate + "-" + schedule);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> charge(ScenarioRunner.Result result, String variable) {
        Map<String, Object> output = (Map<String, Object>) result.variables().get(variable);
        return Map.of("surchargeRate", ((Number) output.get("surchargeRate")).doubleValue(),
            "surchargeAmount", ((Number) output.get("surchargeAmount")).intValue());
    }

    @SuppressWarnings("unchecked")
    private static String keyOf(Map<String, Object> document, String entity, String field, String value) {
        List<Map<String, Object>> rows = (List<Map<String, Object>>) ((Map<String, Object>) document.get("entities"))
            .get(entity);
        return rows.stream().filter(row -> value.equals(row.get(field))).map(row -> String.valueOf(
            row.values().iterator().next())).findFirst().orElseThrow();
    }
}
