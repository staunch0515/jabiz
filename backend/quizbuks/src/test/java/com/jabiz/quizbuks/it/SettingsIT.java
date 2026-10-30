package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.quizbuks.settings.QbSettings;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One's own settings (docs/quizbuks/plans/Q57-sound.md): takers and sponsors save the sound switch for themselves,
 * read only their own, and nobody writes them through a dataset.
 */
class SettingsIT extends AccountItSupport {

    @Test
    void takersAndSponsorsTurnTheSoundOffAndOnForThemselves() {
        String ann = userWithRole(unique("ann"), QbRoles.TAKER);
        String bob = userWithRole(unique("bob"), QbRoles.TAKER);
        String annToken = tokenOf(ann, QbRoles.TAKER);
        // No row yet: the defaults (sound on), so nothing to read.
        assertThat(rows(QbSettings.MY_DATASET, annToken)).isEmpty();

        assertThat(run("QB_SETTINGS_SAVE", annToken, body("soundOn", false))).containsEntry("soundOn", false);
        assertThat(rows(QbSettings.MY_DATASET, annToken)).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("userId", ann).containsEntry("soundOff", true));
        // The same again changes nothing; on again.
        assertThat(run("QB_SETTINGS_SAVE", annToken, body("soundOn", false))).containsEntry("soundOn", false);
        assertThat(run("QB_SETTINGS_SAVE", annToken, body("soundOn", true))).containsEntry("soundOn", true);
        assertThat(rows(QbSettings.MY_DATASET, annToken)).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("soundOff", false));
        // Another taker has none of it.
        assertThat(rows(QbSettings.MY_DATASET, tokenOf(bob, QbRoles.TAKER))).isEmpty();

        // A sponsor too, for the console; an unverified address does not stop it.
        String sara = userWithRole(unique("sara"), QbRoles.SPONSOR);
        assertThat(run("QB_SETTINGS_SAVE", unverifiedTokenOf(sara, QbRoles.SPONSOR), body("soundOn", false)))
            .containsEntry("soundOn", false);
        assertThat(rows(QbSettings.MY_DATASET, tokenOf(sara, QbRoles.SPONSOR))).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("userId", sara));

        // Those who read users read everyone's; a taker does not read the administrators' dataset.
        assertThat(rows(QbSettings.DEFAULT_DATASET, tokenOf("admin-reader", QbRoles.ADMIN_CONTENT)))
            .extracting(row -> row.get("userId")).contains(ann, sara);
        queryDataset(QbSettings.DEFAULT_DATASET, annToken).expectStatus().isForbidden();
    }

    @Test
    void theInputIsRequiredAndNobodyWritesSettingsThroughADataset() {
        String ann = userWithRole(unique("ann"), QbRoles.TAKER);
        String token = tokenOf(ann, QbRoles.TAKER);
        refused("QB_SETTINGS_SAVE", token, body(), 400);
        run("QB_SETTINGS_SAVE", token, body("soundOn", false));
        for (String dataset : List.of(QbSettings.DEFAULT_DATASET, QbSettings.MY_DATASET)) {
            for (String writer : List.of(admin(), token)) {
                int status = client.post().uri("/api/datasets/" + dataset + "/commit")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON).header("Authorization", writer)
                    .bodyValue(Map.of("changes", List.of(Map.of("action", "UPDATE", "id", ann, "version", 0,
                        "attributes", Map.of("soundOff", false)))))
                    .exchange().returnResult(String.class).getStatus().value();
                assertThat(status).as(dataset).isIn(403, 422);
            }
        }
        assertThat(rows(QbSettings.MY_DATASET, token)).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("soundOff", true));
    }
}
