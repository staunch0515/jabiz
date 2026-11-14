package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.fee.MoneyClose;
import com.jabiz.quizbuks.setup.QbParams;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M15 b: what the wind-up's tests share: the maintenance connection the real {@code ACCOUNT_ERASE} needs, the switch
 * on, the erasure with its registration, and a run of {@value MoneyClose#CLOSE}.
 */
abstract class MoneyCloseItSupport extends CreditNoteItSupport {

    @DynamicPropertySource
    static void maintenance(DynamicPropertyRegistry registry) {
        registry.add("jabiz.erasure.datasource.url", () -> DB.r2dbcUrl() + "?schema=" + schema());
        registry.add("jabiz.erasure.datasource.username", DB::username);
        registry.add("jabiz.erasure.datasource.password", DB::password);
    }

    @BeforeEach
    void maintenanceRoleAndTheSwitch() {
        execute("DO $$ BEGIN CREATE ROLE jabiz_maintenance; EXCEPTION WHEN duplicate_object THEN NULL; END $$");
        execute("DO $$ BEGIN EXECUTE 'GRANT jabiz_maintenance TO ' || quote_ident(current_user);"
            + " EXCEPTION WHEN OTHERS THEN NULL; END $$");
        param(QbParams.ERASURE_CLOSE_ENABLED, "true");
    }

    /**
     * Review M3: the account is really erased first (ACCOUNT_ERASE: tombstones, the reasons overwritten), then the
     * registration runs as the erasure's event would (the event is left undelivered: a replay registers nothing more).
     */
    void erase(String sponsor) {
        runAsSystem("ACCOUNT_ERASE", Map.of("userId", sponsor, "requestedBy", "it-support"));
        assertThat(((MoneyClose.RegisterOutput) systemRun(MoneyClose.REGISTER, Map.of("userId", sponsor)))
            .registered()).isTrue();
    }

    MoneyClose.CloseOutput close(String sponsor) {
        return (MoneyClose.CloseOutput) systemRun(MoneyClose.CLOSE, Map.of("sponsorId", sponsor));
    }

    static Map<String, Object> closure(String sponsor) {
        return query("SELECT * FROM qb_sponsor_closure WHERE sponsor_id = ?", sponsor).getFirst();
    }

    static Map<String, Object> lotOf(String grantId) {
        return query("SELECT * FROM qb_fee_credit_grant WHERE grant_id = ?", grantId).getFirst();
    }

    /** The postpaid invoice of the month {@code in} falls in, as the monthly job's item issues it. */
    void invoicePeriod(String sponsor, Instant in) {
        systemRun(com.jabiz.quizbuks.fee.FeeProcesses.INVOICE_ITEM, body("sponsorId", sponsor, "period",
            com.jabiz.quizbuks.fee.FeeRules.period(in, ZoneId.of("Asia/Tokyo"))));
    }

    static BigDecimal money(Object value) {
        return new BigDecimal(String.valueOf(value));
    }
}
