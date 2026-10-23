package com.jabiz.quizbuks.content;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Which links the App shows as a player, and the player's address (Q19); the App's {@code video.test.ts} has these. */
class VideoEmbedsTest {

    @ParameterizedTest
    @CsvSource({
        "https://www.youtube.com/watch?v=dQw4w9WgXcQ, https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ",
        "https://youtube.com/watch?feature=share&v=dQw4w9WgXcQ&t=42, https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ",
        "https://m.youtube.com/watch?v=dQw4w9WgXcQ, https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ",
        "http://youtu.be/dQw4w9WgXcQ?si=abc, https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ",
        "https://www.youtube.com/shorts/dQw4w9WgXcQ, https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ",
        "https://www.youtube.com/embed/dQw4w9WgXcQ, https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ",
        "https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ, https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ",
        "https://www.youtube.com/live/dQw4w9WgXcQ, https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ",
        "https://vimeo.com/76979871, https://player.vimeo.com/video/76979871",
        "https://player.vimeo.com/video/76979871, https://player.vimeo.com/video/76979871",
        "'  https://vimeo.com/76979871  ', https://player.vimeo.com/video/76979871",
    })
    void aVideoLinkIsShownAsItsPlayer(String url, String player) {
        assertThat(VideoEmbeds.player(url)).hasValue(player);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "", "  ", "not a link", "ftp://youtube.com/watch?v=dQw4w9WgXcQ", "javascript:alert(1)",
        // Not one video, or not these sites.
        "https://www.youtube.com/", "https://www.youtube.com/watch", "https://www.youtube.com/watch?v=short",
        "https://www.youtube.com/watch?v=dQw4w9WgXcQ%22onload", "https://www.youtube.com/playlist?list=PL123",
        "https://www.youtube.com/@channel", "https://youtu.be/", "https://vimeo.com/channels/staffpicks",
        "https://vimeo.com/76979871/extra", "https://vimeo.com/abc", "https://evil.example/watch?v=dQw4w9WgXcQ",
        "https://youtube.com.evil.example/watch?v=dQw4w9WgXcQ", "https://user@youtube.com/watch?v=dQw4w9WgXcQ",
        "https://youtube.com:8443/watch?v=dQw4w9WgXcQ",
        // Read as the App reads them: a trailing slash, and an escaped id, are not one video.
        "https://vimeo.com/76979871/", "https://www.youtube.com/embed/dQw4w9WgXcQ/",
        "https://www.youtube.com/watch/?v=dQw4w9WgXcQ", "https://www.youtube.com/embed/dQw4w9WgX%63Q",
    })
    void anyOtherAddressIsNoPlayer(String url) {
        assertThat(VideoEmbeds.player(url)).isEmpty();
    }

    @org.junit.jupiter.api.Test
    void nothingIsNoPlayer() {
        assertThat(VideoEmbeds.player(null)).isEmpty();
    }
}
