package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.certification.CertificateProcesses;
import com.jabiz.quizbuks.certification.QbCertificates;
import com.jabiz.quizbuks.settings.PublicHandles;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Certificates of Q77's kind (docs/quizbuks/plans/Q77-certificates.md): F-S2c (D8) stopped issuing them and F-S5a
 * (docs/quizbuks/plans/impl/F-S5.md) put the certificates of lit nodes in their place; one issued before stays a
 * platform document only its holder reads, valid until its end, with no PDF to issue again and no sharing to set; anybody
 * with its key verifies it, revoked or not; the public certification page lists the issued, unrevoked ones once its
 * holder turned it on under a name of their own. {@code NodeCertificateIT} has the node's.
 */
class CertificateIT extends LegalItSupport {

    private static final String CERT = "SPONSOR_APPLY";

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> held(String userId) {
        return certificates(userId).stream().filter(c -> "CERTIFICATION".equals(c.get("source"))).toList();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> certificates(String userId) {
        return (List<Map<String, Object>>) run(CertificateProcesses.MINE, taker(userId), body()).get("certificates");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> runsOf(String certificateId, String token) {
        return client.get().uri("/api/documents/runs?layout={layout}&subject={subject}", QbCertificates.LAYOUT_ID,
                certificateId).header(HttpHeaders.AUTHORIZATION, token).exchange().expectStatus().isOk()
            .expectBodyList(Map.class).returnResult().getResponseBody().stream()
            .map(row -> (Map<String, Object>) row).toList();
    }

    static String text(byte[] pdf) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    private Map<String, Object> saveSettings(String userId, Object handle, Object page) {
        return run("QB_SETTINGS_SAVE", taker(userId), body("publicHandle", handle, "publicPage", page));
    }

    private List<Object> settingsRefused(String userId, Object handle, Object page) {
        return codes(refused("QB_SETTINGS_SAVE", taker(userId), body("publicHandle", handle, "publicPage", page),
            422));
    }

    @AfterEach
    void certificatesAreOnlyInserted() {
        assertThat(query("SELECT coalesce(n_tup_upd, 0) + coalesce(n_tup_del, 0) AS changed FROM pg_stat_user_tables "
            + "WHERE schemaname = current_schema() AND relname = 'qb_certificate'").getFirst().get("changed"))
            .isEqualTo(0L);
    }

    @Test
    void nothingIsIssuedAnyMoreAndACertificateIssuedBeforeIsTheHoldersToRead() throws Exception {
        String ann = newTaker("q77-ann", "JP");
        // F-S2c (D8): no certificate is issued of a certification any more, held or not.
        assertThat(held(ann)).isEmpty();
        assertThat(query("SELECT 1 FROM qb_certificate WHERE user_id = ?", ann)).isEmpty();

        // One issued before: the holder's list shows it until its end (F-S2c2b: no certification is held any more,
        // the certificates are what is left), and its PDF stays the holder's.
        String key = issuedBefore(ann, CERT, "Taker JP", "en");
        String certificateId = (String) query("SELECT certificate_id FROM qb_certificate WHERE verify_key = ?", key)
            .getFirst().get("certificate_id");
        assertThat(held(ann)).singleElement().satisfies(h -> {
            assertThat(h).containsEntry("certification", CERT).containsEntry("certificateId", certificateId)
                .containsEntry("verifyKey", key).containsEntry("source", "CERTIFICATION");
            assertThat(h.get("heldUntil")).isNotNull();
        });
        // The certificates list has it too, as Q77's, with its end; there is no PDF to issue and no sharing to set.
        assertThat(certificates(ann)).singleElement().satisfies(c -> assertThat(c)
            .containsEntry("source", "CERTIFICATION").containsEntry("certificateId", certificateId)
            .containsEntry("shared", true).containsEntry("revokedAt", null).containsEntry("certification", CERT)
            .containsEntry("runId", null).extracting(m -> m.get("heldUntil")).isNotNull());
        assertThat(codes(refused(CertificateProcesses.ISSUE, taker(ann), body("certificateId", certificateId,
            "language", "en"), 422))).containsExactly(CertificateProcesses.NOT_NODE);
        assertThat(codes(refused(CertificateProcesses.SHARE, taker(ann), body("certificateId", certificateId,
            "shared", false), 422))).containsExactly(CertificateProcesses.NOT_NODE);

        // The PDF: one run of the layout, the holder's own.
        List<Map<String, Object>> runs = runsOf(certificateId, taker(ann));
        assertThat(runs).hasSize(1);
        byte[] pdf = client.get().uri("/api/documents/runs/{id}/pdf", runs.getFirst().get("runId"))
            .header(HttpHeaders.AUTHORIZATION, taker(ann)).exchange().expectStatus().isOk()
            .expectBody(byte[].class).returnResult().getResponseBody();
        String text = text(pdf).replaceAll("\\s+", " ");
        assertThat(text).contains("Taker JP").contains("Sponsor Certification").contains(key);
        // The address wraps in its column.
        assertThat(text.replace(" ", "")).contains("/taker/verify/" + key);

        // Another taker sees none of it.
        String bob = newTaker("q77-bob", "JP");
        assertThat(runsOf(certificateId, taker(bob))).isEmpty();
        client.get().uri("/api/documents/runs/{id}/pdf", runs.getFirst().get("runId"))
            .header(HttpHeaders.AUTHORIZATION, taker(bob)).exchange().expectStatus().isNotFound();
    }

    @Test
    void anybodyWithTheKeyVerifiesItAndSeesARevocation() {
        String cid = newTaker("q77-cid", "JP");
        String key = issuedBefore(cid, CERT, "Taker JP", "zh");

        assertThat(publicItems("qb.public.certificate", "p.key=" + key)).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("certification", CERT).containsEntry("holderName", "Taker JP")
                .containsEntry("revokedAt", null).doesNotContainKeys("userId", "verifyKey", "certificateId");
            assertThat(row.get("validUntil")).isNotNull();
        });
        assertThat(publicItems("qb.public.certificate", "p.key=" + "0".repeat(32))).isEmpty();

        clock.advance(Duration.ofDays(1));
        assertThat(held(cid)).hasSize(1);
        // A revocation recorded before D1 ends it: the verify page says so, the holder's list drops it.
        revokedBefore(cid, CERT);
        assertThat(publicItems("qb.public.certificate", "p.key=" + key)).singleElement()
            .satisfies(row -> assertThat(row.get("revokedAt")).isNotNull());
        assertThat(held(cid)).isEmpty();

        // Past its end: no longer in the holder's list (the verify page still shows it, with its end).
        String fay = newTaker("q77-fay", "JP");
        String faysKey = issuedBefore(fay, CERT, "Taker JP", "en");
        clock.advance(Duration.ofDays(366));
        assertThat(held(fay)).isEmpty();
        assertThat(publicItems("qb.public.certificate", "p.key=" + faysKey)).hasSize(1);
    }

    @Test
    void thePublicPageListsTheIssuedUnrevokedCertificatesOnceTurnedOn() {
        String dee = newTaker("q77-dee", "JP");
        String key = issuedBefore(dee, CERT, "Taker JP", "en");

        // Off by default: the name alone shows nothing.
        assertThat(saveSettings(dee, "  Dee-Q77 ", null)).containsEntry("publicHandle", "dee-q77")
            .containsEntry("publicPage", false);
        assertThat(publicItems("qb.public.taker-page", "p.handle=dee-q77")).isEmpty();

        assertThat(saveSettings(dee, null, true)).containsEntry("publicHandle", "dee-q77")
            .containsEntry("publicPage", true);
        assertThat(publicItems("qb.public.taker-page", "p.handle=dee-q77")).singleElement().satisfies(row ->
            assertThat(row).containsEntry("handle", "dee-q77").containsEntry("certification", CERT)
                .containsEntry("holderName", "Taker JP").containsEntry("verifyKey", key)
                .doesNotContainKey("userId"));
        // The other settings stay as they were.
        assertThat(run("QB_SETTINGS_SAVE", taker(dee), body("soundOn", false))).containsEntry("soundOn", false)
            .containsEntry("publicHandle", "dee-q77").containsEntry("publicPage", true);

        // Names: the format, the reserved words, another's name, and a page needs one.
        String eve = newTaker("q77-eve", "JP");
        assertThat(settingsRefused(eve, "ab", null)).containsExactly(PublicHandles.FORMAT);
        assertThat(settingsRefused(eve, "a--b", null)).containsExactly(PublicHandles.FORMAT);
        assertThat(settingsRefused(eve, "-abc", null)).containsExactly(PublicHandles.FORMAT);
        assertThat(settingsRefused(eve, "verify", null)).containsExactly(PublicHandles.RESERVED);
        assertThat(settingsRefused(eve, "DEE-q77", null)).containsExactly(PublicHandles.TAKEN);
        assertThat(settingsRefused(eve, null, true)).containsExactly(PublicHandles.NO_HANDLE);
        // Their own name again is no clash.
        assertThat(saveSettings(dee, "dee-q77", true)).containsEntry("publicHandle", "dee-q77");

        // A revocation takes it off the page; a new name drops the old one at once; off is as unknown.
        saveSettings(dee, "dee-renamed", null);
        assertThat(publicItems("qb.public.taker-page", "p.handle=dee-q77")).isEmpty();
        assertThat(publicItems("qb.public.taker-page", "p.handle=dee-renamed")).hasSize(1);
        clock.advance(Duration.ofDays(1));
        revokedBefore(dee, CERT);
        assertThat(publicItems("qb.public.taker-page", "p.handle=dee-renamed")).isEmpty();
        saveSettings(dee, null, false);
        assertThat(publicItems("qb.public.taker-page", "p.handle=dee-renamed")).isEmpty();
        // A blank name clears it (with the page off).
        assertThat(saveSettings(dee, "", null)).containsEntry("publicHandle", null);
    }
}
