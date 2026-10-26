package com.jabiz.app.it.integrity;

import com.jabiz.runtime.test.PostgresTestDatabase;
import com.jabiz.runtime.test.SealedColumnsHistory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The migrations keep every sealed column (decision D58; docs/design/21-audit-retention.md section 2.1): the
 * platform's own and the sample application's, as every application checks its own with
 * {@link SealedColumnsHistory}; and what the check finds in migrations made to break the rule.
 */
class SealedColumnsHistoryIT {

    private static final PostgresTestDatabase DB = PostgresTestDatabase.get();

    @Test
    void thePlatformsMigrationsKeepEverySealedColumn() {
        SealedColumnsHistory.Result result = SealedColumnsHistory.platform(DB).check();
        assertThat(result.lost()).isEmpty();
        // It saw the platform's append-only tables, and left the seal's own out.
        assertThat(result.tables()).contains("sys_audit_record", "sys_inbound_event", "sys_inbound_event_body")
            .doesNotContain("sys_integrity_seal", "sys_integrity_item");
    }

    @Test
    void theSampleApplicationsMigrationsKeepEverySealedColumn() {
        SealedColumnsHistory.Result result = SealedColumnsHistory.application(DB, "classpath:db/migration").check();
        assertThat(result.lost()).isEmpty();
        assertThat(result.tables()).contains("warehouse_version");
    }

    @Test
    void addingAColumnOrChangingAnOrdinaryTableIsFine() {
        SealedColumnsHistory.Result result = crafted("kept").check();
        assertThat(result.lost()).isEmpty();
        assertThat(result.tables()).contains("it_sh_sealed").doesNotContain("it_sh_plain");
    }

    @Test
    void aSealedColumnRenamedDroppedOrRetypedIsLost() {
        assertThat(crafted("renamed").check().lost())
            .containsExactly("it_sh_sealed.note (character varying(40)) of V1 is gone");
        assertThat(crafted("dropped").check().lost())
            .containsExactly("it_sh_sealed.note (character varying(40)) of V1 is gone");
        // The scale is part of the digested value: 10.50 becomes 10.500.
        assertThat(crafted("retyped").check().lost())
            .containsExactly("it_sh_sealed.amount (numeric(12,2)) of V1 is numeric(12,3)");
    }

    @Test
    void textOfAnotherLengthAndIntegersOfAnotherWidthReadTheSame() {
        assertThat(crafted("widened").check().lost()).isEmpty();
    }

    @Test
    void aSealedTableDroppedIsLostToo() {
        assertThat(crafted("table-dropped").check().lost())
            .containsExactly("it_sh_sealed of V1 is gone (or no longer append-only)");
    }

    @Test
    void onlyAVersionNoInstallationStoppedAtMayBeLeftOutAndOnlyWithAReason() {
        // V2 renames, V3 renames back: an installation sealed at V2 would lose remark, so V2 must not have shipped.
        assertThat(crafted("undone").check().lost())
            .containsExactly("it_sh_sealed.remark (character varying(40)) of V2 is gone");
        assertThat(crafted("undone").neverReleased("2", "shipped with V3 in one release").check().lost()).isEmpty();
        assertThatThrownBy(() -> crafted("undone").neverReleased("2", " "))
            .isInstanceOf(IllegalArgumentException.class);
        // A version no migration has (written V2, say) is not silently left out.
        assertThatThrownBy(() -> crafted("undone").neverReleased("V2", "a typo").check())
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("[V2]");
    }

    private static SealedColumnsHistory crafted(String set) {
        return SealedColumnsHistory.application(DB, "classpath:it/sealed-history/" + set);
    }
}
