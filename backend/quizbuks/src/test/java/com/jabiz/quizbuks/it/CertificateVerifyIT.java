package com.jabiz.quizbuks.it;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The verify page of a certificate is a public read (F-S5a, docs/quizbuks/plans/impl/F-S5.md §2.4 and its review): it
 * returns the whitelisted columns and no others - no user, no lit record, no map, no node, no certificate id, no key
 * of the record it came from - and shows the right state: valid with no end, revoked once the lighting is, nothing but
 * that when not shared, no such certificate for an unknown key.
 */
class CertificateVerifyIT extends NodeCertificateItSupport {

    /** The columns of {@code qb.public.certificate}, all the page may show. */
    static final Set<String> COLUMNS = Set.of("source", "shared", "certification", "certificationVersion",
        "certificationNames", "certificateNames", "issuerName", "holderName", "heldFrom", "validUntil", "issuedAt",
        "revokedAt");

    /** What must never leave the template: who it is, how the platform keys it. */
    static final List<String> NEVER = List.of("userId", "litId", "treeId", "nodeId", "certificateId", "verifyKey",
        "subjectKind", "runId", "language", "certificateNo", "email");

    private void assertOnlyWhitelisted(Map<String, Object> row) {
        assertThat(row.keySet()).isSubsetOf(COLUMNS);
        assertThat(row.keySet()).doesNotContainAnyElementsOf(NEVER);
        // And no value carries the holder's account: neither their id nor the lit record's.
        assertThat(String.valueOf(row.values())).doesNotContainPattern(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    void theVerifyPageReturnsTheWhitelistedColumnsInEveryState() {
        Gate gate = gate(names("Verified"));
        String ann = newTaker("fs5a-verify-ann", "JP");
        light(gate, ann);
        Map<String, Object> row = only(certificateRows(ann));
        String key = (String) row.get("verify_key");

        // Valid: the whole whitelist, no end, not revoked.
        Map<String, Object> valid = verify(key);
        assertOnlyWhitelisted(valid);
        assertThat(valid.keySet()).containsExactlyInAnyOrderElementsOf(COLUMNS);
        assertThat(valid).containsEntry("source", "NODE").containsEntry("shared", true)
            .containsEntry("holderName", "Taker JP").containsEntry("validUntil", null)
            .containsEntry("revokedAt", null).containsEntry("certification", null);
        assertThat(String.valueOf(valid.get("certificateNames"))).contains("Verified");
        assertThat(valid.get("issuerName")).isEqualTo(row.get("issuer_name"));

        // Not shared: the same columns, empty but for the two that say so.
        run("QB_CERTIFICATE_SHARE_SET", taker(ann), body("certificateId", row.get("certificate_id"), "shared", false));
        Map<String, Object> off = verify(key);
        assertOnlyWhitelisted(off);
        assertThat(off).containsEntry("shared", false).containsEntry("holderName", null)
            .containsEntry("certificateNames", null).containsEntry("revokedAt", null);
        run("QB_CERTIFICATE_SHARE_SET", taker(ann), body("certificateId", row.get("certificate_id"), "shared", true));

        // Revoked: the same columns, the time of the revocation, still no reason, requester or subject.
        revoke(row.get("lit_id"));
        Map<String, Object> revoked = verify(key);
        assertOnlyWhitelisted(revoked);
        assertThat(revoked.get("revokedAt")).isNotNull();
        assertThat(revoked).containsEntry("shared", true).containsEntry("holderName", "Taker JP");
        assertThat(String.valueOf(revoked.values())).doesNotContain("Answers shared");

        // Revoked and then not shared: nothing of the state either.
        run("QB_CERTIFICATE_SHARE_SET", taker(ann), body("certificateId", row.get("certificate_id"), "shared", false));
        assertThat(verify(key)).containsEntry("shared", false).containsEntry("revokedAt", null);

        // An unknown key, a key of the wrong shape and a lit record's id find nothing.
        assertThat(verify("0".repeat(32))).isNull();
        assertThat(verify(row.get("lit_id"))).isNull();
        // No other query reaches the table: the dataset itself is closed to anybody.
        client.get().uri("/api/datasets/urn:jabiz:dataset:public:QbCertificate/rows").exchange().expectStatus()
            .is4xxClientError();
    }

    @Test
    void aCertificateOfQ77sKindHasTheSameColumnsAndShowsItsEnd() {
        String old = newTaker("fs5a-verify-old", "JP");
        String key = issuedBefore(old, "SPONSOR_APPLY", "Taker JP", "en");
        Map<String, Object> row = verify(key);
        assertOnlyWhitelisted(row);
        assertThat(row.keySet()).containsExactlyInAnyOrderElementsOf(COLUMNS);
        assertThat(row).containsEntry("source", "CERTIFICATION").containsEntry("shared", true)
            .containsEntry("certification", "SPONSOR_APPLY").containsEntry("certificateNames", null);
        assertThat(row.get("validUntil")).isNotNull();
    }
}
