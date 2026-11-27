package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.avatar.AvatarCodes;
import com.jabiz.quizbuks.avatar.QbAvatars;
import com.jabiz.quizbuks.identity.AccountCodes;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.runtime.test.FileSamples;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A taker's own avatar (Q114, docs/quizbuks/plans/impl/Q114.md): the initial without a row, a preset or (Q114b) a
 * photo once chosen, read only by the taker (and by administrators who read users), written only by
 * {@code QB_AVATAR_SET}; a photo replaced or removed is deleted at once.
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
        assertThat(refusalCode(refused("QB_AVATAR_SET", token, body("kind", "PHOTO"), 422)))
            .isEqualTo(AvatarCodes.PHOTO_INVALID);
        refused("QB_AVATAR_SET", token, body("kind", "SELFIE"), 400);
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

    @Test
    void aTakerUsesAPhotoWhileTheSwitchIsOnAndAReplacedPhotoIsDeleted() {
        String ann = userWithRole(unique("ann"), QbRoles.TAKER);
        String token = tokenOf(ann, QbRoles.TAKER);
        String first = photo(token);
        // Off (as declared; the class's other tests turn it on): a photo cannot be chosen, and the page says so.
        setControlledParam(QbParams.AVATAR_PHOTO_ENABLED, "false");
        assertThat(refusalCode(refused("QB_AVATAR_SET", token, body("kind", "PHOTO", "photo", first), 422)))
            .isEqualTo(AvatarCodes.PHOTO_DISABLED);
        assertThat(items("qb.app.avatar", token, Map.of())).singleElement().satisfies(row -> assertThat(row)
            .containsEntry("photoEnabled", false).containsEntry("kind", null));

        setControlledParam(QbParams.AVATAR_PHOTO_ENABLED, "true");
        assertThat(items("qb.app.avatar", token, Map.of())).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("photoEnabled", true));
        assertThat(run("QB_AVATAR_SET", token, body("kind", "PHOTO", "photo", first)))
            .containsEntry("kind", "PHOTO").containsEntry("photo", first);
        assertThat(items("qb.app.avatar", token, Map.of())).singleElement().satisfies(row -> assertThat(row)
            .containsEntry("kind", "PHOTO").containsEntry("photo", first));
        // The photo was re-encoded without its metadata (the GPS marker of the sample).
        byte[] stored = client.get().uri("/api/files/" + first + "/content").header(HttpHeaders.AUTHORIZATION, token)
            .exchange().expectStatus().isOk().expectBody(byte[].class).returnResult().getResponseBody();
        assertThat(FileSamples.contains(stored, FileSamples.SECRET_MARKER.getBytes(StandardCharsets.US_ASCII)))
            .as("metadata removed").isFalse();

        // Another photo: the first one is gone at once.
        String second = photo(token);
        run("QB_AVATAR_SET", token, body("kind", "PHOTO", "photo", second));
        assertThat(fileExists(first)).isFalse();
        assertThat(fileExists(second)).isTrue();
        // Turned off again: the photo chosen still shows and is saved again as it is; a new one is refused.
        setControlledParam(QbParams.AVATAR_PHOTO_ENABLED, "false");
        run("QB_AVATAR_SET", token, body("kind", "PHOTO", "photo", second));
        String third = photo(token);
        assertThat(refusalCode(refused("QB_AVATAR_SET", token, body("kind", "PHOTO", "photo", third), 422)))
            .isEqualTo(AvatarCodes.PHOTO_DISABLED);
        // A preset instead removes the photo, the switch notwithstanding.
        run("QB_AVATAR_SET", token, body("kind", "PRESET", "preset", "p04"));
        assertThat(fileExists(second)).isFalse();
        assertThat(rows(QbAvatars.MY_DATASET, token)).singleElement().satisfies(row -> assertThat(row)
            .containsEntry("kind", "PRESET").containsEntry("photo", null));
    }

    @Test
    void onlyOnesOwnPhotoOfThePhotosPolicyIsAccepted() {
        setControlledParam(QbParams.AVATAR_PHOTO_ENABLED, "true");
        String ann = userWithRole(unique("ann"), QbRoles.TAKER);
        String bob = userWithRole(unique("bob"), QbRoles.TAKER);
        String token = tokenOf(ann, QbRoles.TAKER);
        String bobs = photo(tokenOf(bob, QbRoles.TAKER));
        assertThat(refusalCode(refused("QB_AVATAR_SET", token, body("kind", "PHOTO", "photo", bobs), 422)))
            .isEqualTo(AccountCodes.FILE_NOT_OWN);
        assertThat(refusalCode(refused("QB_AVATAR_SET", token, body("kind", "PHOTO", "photo",
            java.util.UUID.randomUUID().toString()), 422))).isEqualTo(AvatarCodes.PHOTO_INVALID);
        // A file of another policy (the sponsors' avatars), uploaded by the caller.
        String sue = userWithRole(unique("sue"), QbRoles.TAKER);
        String sponsorToken = TestTokens.bearer(tokens, sue, QbPermissions.ME, QbPermissions.SPONSOR_ME);
        String other = (String) upload(com.jabiz.quizbuks.identity.QbAccounts.AVATAR_POLICY, FileSamples.jpeg(64, 64),
            "avatar.jpg", "image/jpeg", sponsorToken).expectStatus().isCreated().expectBody(MAP).returnResult()
            .getResponseBody().get("fileId");
        assertThat(refusalCode(refused("QB_AVATAR_SET", sponsorToken, body("kind", "PHOTO", "photo", other), 422)))
            .isEqualTo(AvatarCodes.PHOTO_INVALID);
        // A photo with another kind.
        String mine = photo(token);
        assertThat(refusalCode(refused("QB_AVATAR_SET", token, body("kind", "INITIAL", "photo", mine), 422)))
            .isEqualTo(AvatarCodes.PHOTO_INVALID);
        // Another taker cannot read the photo's record (its name and uploader stay the uploader's).
        run("QB_AVATAR_SET", token, body("kind", "PHOTO", "photo", mine));
        assertThat(rows(QbAvatars.MY_DATASET, tokenOf(bob, QbRoles.TAKER))).isEmpty();
        assertThat(query("SELECT 1 FROM qb_taker_avatar WHERE user_id = ?", bob)).isEmpty();
    }

    /** Uploads a photo with a GPS marker in its metadata; answers its id. */
    private String photo(String token) {
        return (String) upload(QbAvatars.PHOTO_POLICY, FileSamples.jpegWithExif(640, 480, 1), "me.jpg", "image/jpeg",
            token).expectStatus().isCreated().expectBody(MAP).returnResult().getResponseBody().get("fileId");
    }

    private static boolean fileExists(String fileId) {
        return !query("SELECT 1 FROM sys_file WHERE file_id = CAST(? AS uuid)", fileId).isEmpty();
    }
}
