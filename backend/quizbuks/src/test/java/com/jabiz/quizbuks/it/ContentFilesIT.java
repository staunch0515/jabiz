package com.jabiz.quizbuks.it;

import com.jabiz.job.JobDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.content.QbContent;
import com.jabiz.runtime.file.FileProcesses;
import com.jabiz.runtime.job.JobRunner;
import com.jabiz.runtime.test.FileSamples;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The files of quiz content (M-23, M-24; docs/quizbuks/plans/Q3-content.md, D-Q3-6): each kind through its policy,
 * images re-encoded; the platform's sweep keeps the files of drafts and versions and clears the rest.
 */
class ContentFilesIT extends ContentItSupport {

    @Autowired
    JobRunner jobs;

    @Autowired
    @Qualifier("fileSweepJob")
    JobDefinition<FileProcesses.PurgeInput> sweep;

    @AfterEach
    void onlyInserted() {
        assertOnlyInserted();
    }

    /** The storage half of a sweep runs after its commit; wait for it, as the platform's own sweep tests do. */
    @AfterEach
    void awaitTheStorageSweeps() {
        await().atMost(Duration.ofSeconds(30)).until(() -> query("SELECT 1 FROM op_process p "
            + "WHERE p.process_name = 'FILE_PURGE_ORPHANS' AND NOT EXISTS (SELECT 1 FROM op_process_after_commit a "
            + "WHERE a.process_seq_id = p.process_seq_id AND a.succeeded)").isEmpty());
    }

    private String image() {
        return fileId(QbContent.IMAGE_POLICY, FileSamples.jpeg(400, 300), "picture.jpg", "image/jpeg");
    }

    private String pdf() {
        return fileId(QbContent.PDF_POLICY, FileSamples.pdf(), "paper.pdf", "application/pdf");
    }

    private static boolean exists(String fileId) {
        return !query("SELECT 1 FROM sys_file WHERE file_id = ?", UUID.fromString(fileId)).isEmpty();
    }

    @Test
    void eachKindOfFileGoesThroughItsPolicy() {
        Map<String, Object> picture = uploaded(QbContent.IMAGE_POLICY, FileSamples.jpegWithExif(1600, 900, 1),
            "photo.jpg", "image/jpeg");
        assertThat((List<?>) picture.get("variants")).hasSize(3);
        String pictureId = (String) picture.get("fileId");
        byte[] stored = client.get().uri("/api/files/" + pictureId + "/content")
            .header(HttpHeaders.AUTHORIZATION, sponsorA()).exchange().expectStatus().isOk()
            .expectBody(byte[].class).returnResult().getResponseBody();
        assertThat(FileSamples.contains(stored, FileSamples.SECRET_MARKER.getBytes(StandardCharsets.US_ASCII)))
            .as("metadata removed").isFalse();
        String paper = pdf();
        String sound = fileId(QbContent.AUDIO_POLICY, FileSamples.mp3(), "sound.mp3", "audio/mpeg");
        fileId(QbContent.AUDIO_POLICY, FileSamples.m4a(), "sound.m4a", "audio/mp4");
        fileId(QbContent.AUDIO_POLICY, FileSamples.ogg(), "sound.ogg", "audio/ogg");

        // Wrong kinds of file are refused by the policy.
        assertThat(upload(QbContent.IMAGE_POLICY, FileSamples.pdf(), "x.jpg", "image/jpeg", sponsorA())
            .expectBody(MAP).returnResult().getStatus().is4xxClientError()).isTrue();
        assertThat(upload(QbContent.PDF_POLICY, FileSamples.mp3(), "x.pdf", "application/pdf", sponsorA())
            .expectBody(MAP).returnResult().getStatus().is4xxClientError()).isTrue();
        // Uploading needs qb.content.write.
        upload(QbContent.IMAGE_POLICY, FileSamples.jpeg(10, 10), "x.jpg", "image/jpeg",
            TestTokens.bearer(tokens, "reader", QbPermissions.CONTENT_FILE_READ)).expectStatus().isForbidden();

        String quizId = newQuiz(sponsorA(), "Files");
        run("QB_QUIZ_SAVE", sponsorA(), body("quizId", quizId, "title", "Files", "cover", pictureId));
        run("QB_MATERIAL_SAVE", sponsorA(), body("quizId", quizId, "kind", "PDF", "title", "Paper", "pdf", paper));
        run("QB_MATERIAL_SAVE", sponsorA(), body("quizId", quizId, "kind", "AUDIO", "title", "Sound",
            "audio", sound));
        run("QB_MATERIAL_SAVE", sponsorA(), body("quizId", quizId, "kind", "IMAGES", "title", "Pictures",
            "images", List.of(body("image", pictureId, "caption", "A photo"), body("image", image()))));
        run("QB_QUESTION_SAVE", sponsorA(), body("quizId", quizId, "stem", "Which?", "image", pictureId,
            "options", List.of(body("image", image(), "correct", true), body("text", "none"))));
        // A file of another policy does not fit the field.
        refused("QB_MATERIAL_SAVE", sponsorA(), body("quizId", quizId, "kind", "PDF", "title", "Not a PDF",
            "pdf", pictureId), 400);
        Map<String, Object> version = run("QB_QUIZ_PUBLISH_VERSION", sponsorA(), body("quizId", quizId));
        // The picture (cover, image group, question), two more images, the PDF and the sound: each once.
        assertThat(latest("qb_version_file_version", "version_file_id", "quiz_id = '" + quizId + "'")).hasSize(5);
        assertThat(version).containsEntry("materialCount", 3);

        // Reading needs qb.content.file.read.
        client.get().uri("/api/files/" + pictureId + "/content")
            .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(tokens, "sponsor-a", QbPermissions.CONTENT_WRITE))
            .exchange().expectStatus().value(status -> assertThat(status).isIn(403, 404));
        client.get().uri("/api/files/" + paper + "/content").header(HttpHeaders.AUTHORIZATION, contentAdmin())
            .exchange().expectStatus().isOk();
    }

    @Test
    void theSweepKeepsWhatDraftsAndVersionsUseAndClearsTheRest() {
        // Only in a draft.
        String drafted = image();
        newQuizWithCover("Draft", drafted);
        // In a version, then replaced in the draft.
        String versioned = image();
        String replacement = image();
        String quizId = newQuiz(sponsorA(), "Versioned");
        String questionId = (String) run("QB_QUESTION_SAVE", sponsorA(), body("quizId", quizId, "stem", "Q",
            "image", versioned, "options", List.of(body("text", "a", "correct", true), body("text", "b"))))
            .get("itemId");
        run("QB_QUIZ_PUBLISH_VERSION", sponsorA(), body("quizId", quizId));
        run("QB_QUESTION_SAVE", sponsorA(), body("quizId", quizId, "questionId", questionId, "stem", "Q",
            "image", replacement, "options", List.of(body("text", "a", "correct", true), body("text", "b"))));
        // Never used.
        String unused = image();
        // Shared by a copy whose original is removed.
        String shared = image();
        String original = newQuizWithCover("Original", shared);
        run("QB_QUIZ_CLONE", sponsorA(), body("quizId", original));
        run("QB_QUIZ_DELETE", sponsorA(), body("quizId", original));
        // In a removed quiz, versioned (no publication refers to the version).
        String removedPdf = pdf();
        String removed = newQuiz(sponsorA(), "Removed");
        run("QB_MATERIAL_SAVE", sponsorA(), body("quizId", removed, "kind", "PDF", "title", "P", "pdf", removedPdf));
        question(sponsorA(), removed, "Q");
        run("QB_QUIZ_PUBLISH_VERSION", sponsorA(), body("quizId", removed));
        run("QB_QUIZ_DELETE", sponsorA(), body("quizId", removed));

        clock.advance(Duration.ofDays(2));
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        assertThat(jobs.run(sweep, clock.instant())).isEqualTo(JobRunner.Outcome.SUCCEEDED);

        for (String kept : List.of(drafted, versioned, replacement, shared)) {
            assertThat(exists(kept)).as("kept " + kept).isTrue();
        }
        for (String cleared : List.of(unused, removedPdf)) {
            assertThat(exists(cleared)).as("cleared " + cleared).isFalse();
        }
    }

    private String newQuizWithCover(String title, String cover) {
        String quizId = newQuiz(sponsorA(), title);
        run("QB_QUIZ_SAVE", sponsorA(), body("quizId", quizId, "title", title, "cover", cover));
        return quizId;
    }
}
