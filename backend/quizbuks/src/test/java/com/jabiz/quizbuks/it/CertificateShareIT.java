package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.certification.CertificateProcesses;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sharing a node's certificate (F-S5a, docs/quizbuks/plans/impl/F-S5.md §2.3): its verify page shows by default (the key
 * is random and listed nowhere); the holder turns the sharing off and on again, the key staying the same; off, the page
 * shows nothing but that and the public certification page does not list it; the public page lists a Taker's shared,
 * unrevoked certificates of nodes once its holder turned it on, never a Sponsor's; only the holder changes it.
 */
class CertificateShareIT extends NodeCertificateItSupport {

    private List<Map<String, Object>> page(String handle) {
        return publicItems("qb.public.taker-page", "p.handle=" + handle);
    }

    @Test
    void theHolderTurnsTheSharingOffAndOnAndTheKeyStaysTheSame() {
        Gate gate = gate(names("Shared"));
        String ann = newTaker("fs5a-share-ann", "JP");
        light(gate, ann);
        Map<String, Object> row = only(certificateRows(ann));
        String id = (String) row.get("certificate_id");
        String key = (String) row.get("verify_key");
        assertThat(verify(key)).containsEntry("shared", true).containsEntry("holderName", "Taker JP");

        // Off: the page shows nothing but that; the list says so.
        assertThat(run(CertificateProcesses.SHARE, taker(ann), body("certificateId", id, "shared", false)))
            .containsEntry("shared", false);
        assertThat(only(certificateRows(ann))).containsEntry("shared", false).containsEntry("verify_key", key);
        Map<String, Object> off = verify(key);
        assertThat(off).containsEntry("shared", false).containsEntry("source", "NODE");
        off.forEach((column, value) -> {
            if (!List.of("shared", "source").contains(column)) {
                assertThat(value).as(column).isNull();
            }
        });
        assertThat(mine(taker(ann))).singleElement().satisfies(c -> assertThat(c).containsEntry("shared", false));
        // Off again changes nothing (no second write); on restores it, the key the same.
        run(CertificateProcesses.SHARE, taker(ann), body("certificateId", id, "shared", false));
        run(CertificateProcesses.SHARE, taker(ann), body("certificateId", id, "shared", true));
        assertThat(verify(key)).containsEntry("shared", true).containsEntry("holderName", "Taker JP");
        assertThat(only(certificateRows(ann))).containsEntry("verify_key", key);

        // Only the holder: another person finds nothing (404), a Sponsor's call is not the Taker's.
        String bob = newTaker("fs5a-share-bob", "JP");
        refused(CertificateProcesses.SHARE, taker(bob), body("certificateId", id, "shared", false), 404);
        assertThat(verify(key)).containsEntry("shared", true);
    }

    @Test
    void thePublicPageListsOnlyTheSharedUnrevokedCertificatesOfNodesOfThePageOwner() {
        Gate first = gate(names("First"));
        Gate second = gate(names("Second"));
        String cat = newTaker("fs5a-page-cat", "JP");
        light(first, cat);
        clock.set(clock.instant().plus(java.time.Duration.ofMinutes(1)));
        light(second, cat);
        String handle = "cat-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        run("QB_SETTINGS_SAVE", taker(cat), body("publicHandle", handle, "publicPage", false));
        assertThat(page(handle)).isEmpty();
        run("QB_SETTINGS_SAVE", taker(cat), body("publicHandle", handle, "publicPage", true));

        // Newest first, each with its names, issuer and key; no end and no user.
        List<Map<String, Object>> rows = certificateRows(cat);
        assertThat(page(handle)).hasSize(2).satisfiesExactly(
            row -> assertThat(row).containsEntry("verifyKey", rows.get(1).get("verify_key"))
                .containsEntry("source", "NODE").containsEntry("validUntil", null).containsEntry("certification", null)
                .doesNotContainKey("userId"),
            row -> assertThat(row).containsEntry("verifyKey", rows.get(0).get("verify_key")));
        assertThat(String.valueOf(page(handle).getFirst().get("certificateNames"))).contains("Second");

        // Not shared, and not revoked: off the page; on again, back.
        run(CertificateProcesses.SHARE, taker(cat), body("certificateId", rows.get(1).get("certificate_id"),
            "shared", false));
        assertThat(page(handle)).singleElement().satisfies(row -> assertThat(row)
            .containsEntry("verifyKey", rows.get(0).get("verify_key")));
        revoke(rows.get(0).get("lit_id"));
        assertThat(page(handle)).isEmpty();
        run(CertificateProcesses.SHARE, taker(cat), body("certificateId", rows.get(1).get("certificate_id"),
            "shared", true));
        assertThat(page(handle)).singleElement().satisfies(row -> assertThat(row)
            .containsEntry("verifyKey", rows.get(1).get("verify_key")));
    }

    @Test
    void aSponsorsCertificateIsOnNoPublicPageAndTheyShareItAsASponsor() {
        Map<String, Object> start = node(null, "start", "Getting started", all(passed()));
        start.put("certificateNames", names("Getting started"));
        Copy copy = copy(List.of(start, node(null, "next", "Running a quiz", all(lit("start", true), passed()))));
        String sponsor = sponsorOf(copy.operator(), "s-fs5a-share");
        pass(sponsor, liveBound(copy.operator().leadId(), unique("FS5a share"), copy.treeId(), copy.start(),
            Map.of("tiers", List.of(body("fromScore", 0, "toScore", 5, "reward", 0)))));
        Map<String, Object> row = only(certificateRows(sponsor));
        String id = (String) row.get("certificate_id");

        // The Sponsor's account has a public page of its own as a Taker, and the certificate is on no page.
        String handle = "sp-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        run("QB_SETTINGS_SAVE", sponsorToken(sponsor), body("publicHandle", handle, "publicPage", true));
        assertThat(page(handle)).isEmpty();
        assertThat(verify(row.get("verify_key"))).containsEntry("shared", true);

        // The Taker side cannot set it; the Sponsor side does.
        refused(CertificateProcesses.SHARE, sponsorToken(sponsor), body("certificateId", id, "shared", false), 404);
        assertThat(run(CertificateProcesses.SPONSOR_SHARE, sponsorToken(sponsor), body("certificateId", id, "shared",
            false))).containsEntry("shared", false);
        assertThat(verify(row.get("verify_key"))).containsEntry("shared", false);
        assertThat(sponsorMine(sponsorToken(sponsor))).singleElement().satisfies(c -> assertThat(c)
            .containsEntry("shared", false));
        assertThat(mine(sponsorToken(sponsor))).isEmpty();
        // The Taker permission is not the Sponsor's.
        call(CertificateProcesses.SPONSOR_SHARE, taker(newTaker("fs5a-share-taker", "JP")), body("certificateId", id,
            "shared", false)).expectStatus().isForbidden();
    }
}
