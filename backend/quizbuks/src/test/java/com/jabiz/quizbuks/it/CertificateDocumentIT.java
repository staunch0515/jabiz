package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.certification.CertificateProcesses;
import com.jabiz.quizbuks.certification.QbCertificates;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The PDF of a node's certificate (F-S5a, docs/quizbuks/plans/impl/F-S5.md §2.2): the holder issues it by their first
 * download (a document only its issuer reads), every download after is the stored one whatever the language asked; it
 * has the certificate's name in the language, the holder, the issuer, when the node was lit and the verify address, and no
 * end; another person finds nothing; a revoked certificate is issued none (one issued before stays); a Sponsor's is issued
 * by the Sponsor as a Sponsor.
 */
class CertificateDocumentIT extends NodeCertificateItSupport {

    private static String text(byte[] pdf) throws Exception {
        try (org.apache.pdfbox.pdmodel.PDDocument document = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            return new org.apache.pdfbox.text.PDFTextStripper().getText(document).replaceAll("\\s+", " ");
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> runsOf(Object certificateId, String token, String layout) {
        return client.get().uri("/api/documents/runs?layout={layout}&subject={subject}", layout, certificateId)
            .header(HttpHeaders.AUTHORIZATION, token).exchange().expectStatus().isOk()
            .expectBodyList(Map.class).returnResult().getResponseBody().stream()
            .map(row -> (Map<String, Object>) row).toList();
    }

    @Test
    void theHolderIssuesItByTheFirstDownloadAndEveryOneAfterIsTheStoredDocument() throws Exception {
        Gate gate = gate(names("Fair dealing"));
        String ann = newTaker("fs5a-doc-ann", "JP");
        light(gate, ann);
        Map<String, Object> row = only(certificateRows(ann));
        String certificateId = (String) row.get("certificate_id");
        String key = (String) row.get("verify_key");

        // The first download issues it, in the language asked; the row keeps the run.
        Map<String, Object> first = issue(CertificateProcesses.ISSUE, taker(ann), certificateId, "en");
        assertThat(first).containsEntry("issued", true).containsEntry("certificateId", certificateId)
            .containsEntry("verifyKey", key);
        String runId = (String) first.get("runId");
        assertThat(only(certificateRows(ann))).containsEntry("run_id", runId);
        byte[] pdf = pdf(taker(ann), runId);
        String text = text(pdf);
        assertThat(text).contains("Fair dealing").contains("Taker JP").contains(key).contains("Lit on")
            .doesNotContain("Valid until").doesNotContain("Held from");
        assertThat(text).contains("QuizBuks");
        assertThat(text.replace(" ", "")).contains("/taker/verify/" + key);

        // Again, even in another language: the stored document, the same bytes, no second run.
        Map<String, Object> again = issue(CertificateProcesses.ISSUE, taker(ann), certificateId, "ja");
        assertThat(again).containsEntry("issued", false).containsEntry("runId", runId);
        assertThat(pdf(taker(ann), runId)).isEqualTo(pdf);
        assertThat(runsOf(certificateId, taker(ann), QbCertificates.NODE_LAYOUT_ID)).hasSize(1);
        assertThat(mine(taker(ann))).singleElement().satisfies(c -> assertThat(c).containsEntry("runId", runId));

        // Another person finds neither the certificate nor its document.
        String bob = newTaker("fs5a-doc-bob", "JP");
        assertThat(runsOf(certificateId, taker(bob), QbCertificates.NODE_LAYOUT_ID)).isEmpty();
        client.get().uri("/api/documents/runs/{id}/pdf", runId).header(HttpHeaders.AUTHORIZATION, taker(bob))
            .exchange().expectStatus().isNotFound();
        refused(CertificateProcesses.ISSUE, taker(bob), body("certificateId", certificateId, "language", "en"), 404);
        refused(CertificateProcesses.SHARE, taker(bob), body("certificateId", certificateId, "shared", false), 404);

        // Revoked afterwards: the document issued stays the holder's to download.
        revoke(row.get("lit_id"));
        assertThat(pdf(taker(ann), runId)).isEqualTo(pdf);
        assertThat(issue(CertificateProcesses.ISSUE, taker(ann), certificateId, "en"))
            .containsEntry("issued", false).containsEntry("runId", runId);
    }

    @Test
    void aRevokedCertificateIsIssuedNoDocument() {
        Gate gate = gate(names("Revoked early"));
        String ann = newTaker("fs5a-doc-rev", "JP");
        light(gate, ann);
        Map<String, Object> row = only(certificateRows(ann));
        revoke(row.get("lit_id"));
        assertThat(codes(refused(CertificateProcesses.ISSUE, taker(ann), body("certificateId", row.get("certificate_id"),
            "language", "en"), 422))).containsExactly(CertificateProcesses.REVOKED);
        assertThat(only(certificateRows(ann)).get("run_id")).isNull();
    }

    @Test
    void aSponsorsCertificateIsIssuedByTheSponsorAsASponsorAndNotByTheirTakerSide() throws Exception {
        Map<String, Object> start = node(null, "start", "Getting started", all(passed()));
        start.put("certificateNames", names("Getting started"));
        Copy copy = copy(List.of(start, node(null, "next", "Running a quiz", all(lit("start", true), passed()))));
        String sponsor = sponsorOf(copy.operator(), "s-fs5a-doc");
        String publication = liveBound(copy.operator().leadId(), unique("FS5a doc"), copy.treeId(), copy.start(),
            Map.of("tiers", List.of(body("fromScore", 0, "toScore", 5, "reward", 0))));
        pass(sponsor, publication);
        Map<String, Object> row = only(certificateRows(sponsor));
        String certificateId = (String) row.get("certificate_id");

        // The Taker side finds nothing of it; the Sponsor side issues it.
        refused(CertificateProcesses.ISSUE, sponsorToken(sponsor), body("certificateId", certificateId, "language",
            "en"), 404);
        Map<String, Object> issued = issue(CertificateProcesses.SPONSOR_ISSUE, sponsorToken(sponsor), certificateId,
            "en");
        assertThat(issued).containsEntry("issued", true);
        String text = text(pdf(sponsorToken(sponsor), issued.get("runId")));
        // (a long name wraps in its column)
        assertThat(text).contains("Getting started").doesNotContain("Valid until");
        assertThat(text.replace(" ", "")).contains(String.valueOf(row.get("issuer_name")).replace(" ", ""))
            .contains(String.valueOf(row.get("holder_name")).replace(" ", ""));
        assertThat(issue(CertificateProcesses.SPONSOR_ISSUE, sponsorToken(sponsor), certificateId, "zh"))
            .containsEntry("issued", false).containsEntry("runId", issued.get("runId"));
        // A Taker has not the Sponsor's permission to issue it.
        String bob = newTaker("fs5a-doc-taker", "JP");
        call(CertificateProcesses.SPONSOR_ISSUE, taker(bob), body("certificateId", certificateId, "language", "en"))
            .expectStatus().isForbidden();
    }
}
