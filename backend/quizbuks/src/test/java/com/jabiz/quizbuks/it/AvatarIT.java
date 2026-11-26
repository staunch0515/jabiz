package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.avatar.AvatarCodes;
import com.jabiz.quizbuks.avatar.QbAvatars;
import com.jabiz.quizbuks.identity.AccountCodes;
import com.jabiz.quizbuks.setup.QbRoles;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A taker's own avatar (Q114a, docs/quizbuks/plans/impl/Q114.md): the initial without a row, a preset once chosen,
 * read only by the taker (and by administrators who read users), written only by {@code QB_AVATAR_SET}.
 */
class AvatarIT extends AccountItSupport {

    @Test
    void aTakerChoosesAPresetAndGoesBackToTheInitial() {
        String ann = userWithRole(unique("ann"), QbRoles.TAKER);
        String bob = userWithRole(unique("bob"), QbRoles.TAKER);
        String annToken = tokenOf(ann, QbRoles.TAKER);
        // No row yet: the initial.
        assertThat(rows(QbAvatars.MY_DATASET, annToken)).isEmpty();

        assertThat(run("QB_AVATAR_SET", annToken, body("kind", "PRESET", "preset", "p09")))
            .containsEntry("kind", "PRESET").containsEntry("preset", "p09");
        assertThat(rows(QbAvatars.MY_DATASET, annToken)).singleElement().satisfies(row -> assertThat(row)
            .containsEntry("userId", ann).containsEntry("kind", "PRESET").containsEntry("preset", "p09"));
        // Another one, the same again (nothing to write), then back to the initial.
        run("QB_AVATAR_SET", annToken, body("kind", "PRESET", "preset", "p02"));
        run("QB_AVATAR_SET", annToken, body("kind", "PRESET", "preset", "p02"));
        assertThat(run("QB_AVATAR_SET", annToken, body("kind", "INITIAL")))
            .containsEntry("kind", "INITIAL").containsEntry("preset", null);
        assertThat(rows(QbAvatars.MY_DATASET, annToken)).singleElement().satisfies(row -> assertThat(row)
            .containsEntry("kind", "INITIAL").containsEntry("preset", null));
        // One row, changed in place: the first choice inserted it, the later ones updated it.
        assertThat(query("SELECT count(*) AS n FROM qb_taker_avatar WHERE user_id = ?", ann)).singleElement()
            .satisfies(row -> assertThat(((Number) row.get("n")).intValue()).isOne());

        // Another taker sees none of it; administrators who read users see it in the data browser, a taker does not.
        assertThat(rows(QbAvatars.MY_DATASET, tokenOf(bob, QbRoles.TAKER))).isEmpty();
        assertThat(rows(QbAvatars.DEFAULT_DATASET, tokenOf("admin-reader", QbRoles.ADMIN_CONTENT)))
            .extracting(row -> row.get("userId")).contains(ann);
        queryDataset(QbAvatars.DEFAULT_DATASET, annToken).expectStatus().isForbidden();
    }

    @Test
    void anUnknownPresetOrKindIsRefused() {
        String ann = userWithRole(unique("ann"), QbRoles.TAKER);
        String token = tokenOf(ann, QbRoles.TAKER);
        assertThat(refusalCode(refused("QB_AVATAR_SET", token, body("kind", "PRESET", "preset", "p99"), 422)))
            .isEqualTo(AvatarCodes.PRESET_UNKNOWN);
        assertThat(refusalCode(refused("QB_AVATAR_SET", token, body("kind", "PRESET"), 422)))
            .isEqualTo(AvatarCodes.PRESET_UNKNOWN);
        // A preset with the initial is not an initial.
        assertThat(refusalCode(refused("QB_AVATAR_SET", token, body("kind", "INITIAL", "preset", "p01"), 422)))
            .isEqualTo(AvatarCodes.PRESET_UNKNOWN);
        refused("QB_AVATAR_SET", token, body("kind", "PHOTO"), 400);
        refused("QB_AVATAR_SET", token, Map.of(), 400);
        assertThat(query("SELECT 1 FROM qb_taker_avatar WHERE user_id = ?", ann)).isEmpty();
    }

    @Test
    void anUnverifiedOrBannedTakerCannotChooseAndNobodyWritesThroughADataset() {
        String ann = userWithRole(unique("ann"), QbRoles.TAKER);
        assertThat(refused("QB_AVATAR_SET", unverifiedTokenOf(ann, QbRoles.TAKER), body("kind", "PRESET", "preset",
            "p01"), 403).toString()).contains("EMAIL_NOT_VERIFIED");

        String token = tokenOf(ann, QbRoles.TAKER);
        run("QB_AVATAR_SET", token, body("kind", "PRESET", "preset", "p01"));
        for (String dataset : List.of(QbAvatars.DEFAULT_DATASET, QbAvatars.MY_DATASET)) {
            for (String writer : List.of(admin(), token)) {
                int status = client.post().uri("/api/datasets/" + dataset + "/commit")
                    .contentType(MediaType.APPLICATION_JSON).header("Authorization", writer)
                    .bodyValue(Map.of("changes", List.of(Map.of("action", "UPDATE", "id", ann, "version", 0,
                        "attributes", Map.of("preset", "p02")))))
                    .exchange().returnResult(String.class).getStatus().value();
                // Refused either way: the own dataset is read-only (400), the default one process-only (403 / 422).
                assertThat(status).as(dataset).isIn(400, 403, 422);
            }
        }

        String boss = userWithRole(unique("boss"), QbRoles.ADMIN_SUPER);
        run("QB_USER_BAN", tokenOf(boss, QbRoles.ADMIN_SUPER), body("userId", ann, "reason", "Spam"));
        assertThat(refusalCode(refused("QB_AVATAR_SET", token, body("kind", "PRESET", "preset", "p03"), 422)))
            .isEqualTo(AccountCodes.USER_BANNED);
        assertThat(rows(QbAvatars.MY_DATASET, token)).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("preset", "p01"));
    }
}
