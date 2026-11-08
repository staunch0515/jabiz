package com.jabiz.quizbuks.publishing;

import com.jabiz.quizbuks.content.QuizSnapshot;
import com.jabiz.quizbuks.content.VideoEmbeds;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The card's video (Q63, Q75, F-S12, F-S15): the materials' order and the shared video's place in it. */
class CardVideoTest {

    static final String YOUTUBE = "https://www.youtube.com/watch?v=dQw4w9WgXcQ";
    static final String SHORTS = "https://www.youtube.com/shorts/dQw4w9WgXcQ";
    static final String BILIBILI = "https://www.bilibili.com/video/BV1GJ411x7h7";
    static final String FILE = "https://videos.example.com/cats.mp4";
    static final String PAGE = "https://videos.example.com/watch/cats";

    static QuizSnapshot.Material video(String url, boolean watch) {
        return new QuizSnapshot.Material(1, "VIDEO_LINK", "Video", null, null, url, null, null, List.of(), watch, null,
            watch ? 60 : null, false);
    }

    static PublicationSharedVideos.Frozen shared(String url, boolean vertical) {
        return new PublicationSharedVideos.Frozen("v1", url, 30, vertical, "p1");
    }

    static String player(String url) {
        return VideoEmbeds.player(url).orElseThrow();
    }

    @Test
    void theMaterialsGiveTheWatchedVideoThenASiteOrAFileThenAPage() {
        assertThat(PublicationEditing.cardVideo(List.of(video(PAGE, false), video(BILIBILI, false), video(YOUTUBE,
            true)))).isEqualTo(new PublicationEditing.CardVideo(player(YOUTUBE), true, 60, false));
        assertThat(PublicationEditing.cardVideo(List.of(video(PAGE, false), video(FILE, false))).player())
            .isEqualTo(player(FILE));
        assertThat(PublicationEditing.cardVideo(List.of(video(PAGE, false))).player()).isEqualTo(player(PAGE));
        assertThat(PublicationEditing.cardVideo(List.of())).isEqualTo(PublicationEditing.CardVideo.NONE);
    }

    @Test
    void aSharedVideoTakesTheCardUnlessOneMustBeWatched() {
        // Before the materials' videos, whatever its kind (F-S15: a page too), never one to watch.
        assertThat(PublicationEditing.cardVideo(List.of(video(BILIBILI, false)), shared(PAGE, false)))
            .isEqualTo(new PublicationEditing.CardVideo(player(PAGE), false, null, false));
        assertThat(PublicationEditing.cardVideo(List.of(video(FILE, false)), shared(SHORTS, false)))
            .isEqualTo(new PublicationEditing.CardVideo(player(SHORTS), false, null, true));
        assertThat(PublicationEditing.cardVideo(List.of(), shared(FILE, true)))
            .isEqualTo(new PublicationEditing.CardVideo(player(FILE), false, null, true));
        // A video to watch keeps the card (Q75); the shared one then only counts as foreign.
        assertThat(PublicationEditing.cardVideo(List.of(video(FILE, true)), shared(YOUTUBE, false)))
            .isEqualTo(new PublicationEditing.CardVideo(player(FILE), true, 60, false));
    }
}
