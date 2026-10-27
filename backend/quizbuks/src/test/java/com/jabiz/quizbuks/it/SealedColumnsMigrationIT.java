package com.jabiz.quizbuks.it;

import com.jabiz.runtime.test.PostgresTestDatabase;
import com.jabiz.runtime.test.SealedColumnsHistory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The application's migrations keep every sealed column (platform decision D58, docs/design/21-audit-retention.md
 * section 2.1): whatever version an installation was at when its rows were sealed (1.2 was at V28), each column an
 * append-only table had then must still be there, of a type that digests the same, once every migration ran.
 */
class SealedColumnsMigrationIT {

    @Test
    void noMigrationRenamesDropsOrRetypesASealedColumn() {
        SealedColumnsHistory.Result result = SealedColumnsHistory
            .application(PostgresTestDatabase.get(), "classpath:db/migration")
            .neverReleased("29", "V29 renamed the sealed sponsor_id and V30 named it back; both ship in 1.3 and run in"
                + " one upgrade with the application stopped, so no installation sealed at V29 (S0b)")
            .check();
        assertThat(result.lost()).as("sealed columns that verifying would no longer find").isEmpty();
        // It saw the tables S0b is about.
        assertThat(result.tables()).contains("qb_publication_version", "qb_reward_tier_version",
            "qb_cash_reward_version", "qb_redemption_version", "qb_digital_prize_version");
    }
}
