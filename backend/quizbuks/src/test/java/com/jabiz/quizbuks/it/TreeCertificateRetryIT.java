package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the lighting's event missed (F-S5a, docs/quizbuks/plans/impl/F-S5.md §2.2 and §2.4): the daily job
 * {@code qb.tree-certificate-retry} writes the certificate of a lit record that froze names and has none, once; a
 * revoked lighting needs none; the daily check of the maps ({@code CERTIFICATE_MISSING}) counts what is missing until then.
 */
class TreeCertificateRetryIT extends NodeCertificateItSupport {

    private List<Map<String, Object>> health() {
        return items("qb.admin.tree-health", TestTokens.bearer(tokens, "fs5a-health", QbPermissions.TREES_READ),
            Map.of("params", Map.of("at", clock.instant().toString())));
    }

    private long missing() {
        return health().stream().filter(row -> "CERTIFICATE_MISSING".equals(row.get("checkCode")))
            .mapToLong(row -> ((Number) row.get("found")).longValue()).sum();
    }

    @Test
    void aLostEventIsMadeUpByTheDailyJobAndTheDailyCheckCountsWhatIsMissingMeanwhile() {
        Gate gate = gate(names("Retried"));
        String ann = newTaker("fs5a-retry-ann", "JP");
        String bob = newTaker("fs5a-retry-bob", "JP");
        light(gate, ann, bob);
        assertThat(missing()).isZero();
        String annsKey = (String) only(certificateRows(ann)).get("verify_key");

        // The event was lost: Ann's certificate is not there, and the check says so.
        execute("DELETE FROM qb_certificate WHERE user_id = ?", ann);
        assertThat(certificateRows(ann)).isEmpty();
        assertThat(missing()).isEqualTo(1);
        // Bob's lighting was revoked: he needs none (nothing missing for it).
        Object bobsLit = only(certificateRows(bob)).get("lit_id");
        revoke(bobsLit);
        execute("DELETE FROM qb_certificate WHERE user_id = ?", bob);
        assertThat(missing()).isEqualTo(1);

        // The daily job writes Ann's, once, and nothing for Bob; the check finds all well.
        runRetry();
        assertThat(certificateRows(ann)).hasSize(1);
        assertThat(only(certificateRows(ann)).get("verify_key")).isNotEqualTo(annsKey);
        assertThat(certificateRows(bob)).isEmpty();
        assertThat(missing()).isZero();
        runRetry();
        assertThat(certificateRows(ann)).hasSize(1);
        assertThat(certificateRows(bob)).isEmpty();
        assertThat(health().stream().map(row -> row.get("checkCode"))).doesNotContain("CERTIFICATE_MISSING");
    }

    @Test
    void theJobsItemsAreThePeopleWithACertificateToWriteAndNobodyElse() {
        Gate gate = gate(names("Items"));
        String ann = newTaker("fs5a-items-ann", "JP");
        light(gate, ann);
        String token = TestTokens.bearer(tokens, "fs5a-items", QbPermissions.CERTIFICATE_SYSTEM);
        assertThat(items("qb.system.tree-certificates-due", token, Map.of("limit", 500)))
            .extracting(row -> row.get("subjectId")).doesNotContain(ann);
        execute("DELETE FROM qb_certificate WHERE user_id = ?", ann);
        assertThat(items("qb.system.tree-certificates-due", token, Map.of("limit", 500)))
            .extracting(row -> row.get("subjectId")).contains(ann);
        settle(ann);
        assertThat(items("qb.system.tree-certificates-due", token, Map.of("limit", 500)))
            .extracting(row -> row.get("subjectId")).doesNotContain(ann);
        assertThat(certificateRows(ann)).hasSize(1);
    }
}
