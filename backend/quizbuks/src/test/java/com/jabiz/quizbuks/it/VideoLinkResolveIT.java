package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.content.ContentCodes;
import com.jabiz.runtime.test.StubHttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Q99b (docs/quizbuks/plans/Q99-more-video-sites.md): {@code QB_VIDEO_LINK_RESOLVE} asks b23.tv (a stub here) where a
 * short link leads and gives the plain address of the Bilibili video, or refuses the link.
 */
class VideoLinkResolveIT extends PlayItSupport {

    static final StubHttpServer B23 = StubHttpServer.start(request -> switch (request.path()) {
        case "/bMyOKXv" -> StubHttpServer.Answer.of(302, "")
            .header("Location", "https://www.bilibili.com/video/BV1GJ411x7h7?share_source=copy_web");
        case "/space01" -> StubHttpServer.Answer.of(302, "").header("Location", "https://space.bilibili.com/12345");
        default -> StubHttpServer.Answer.of(404, "");
    });

    @DynamicPropertySource
    static void b23(DynamicPropertyRegistry registry) {
        registry.add("jabiz.external-services.video-short-links.base-url", () -> B23.url(""));
        registry.add("jabiz.external-services.video-short-links.allowed-hosts", () -> "127.0.0.1");
    }

    @Test
    void aShortLinkBecomesTheVideosAddress() {
        String token = sponsorToken(newSponsor("s-short-link"));
        assertThat(run("QB_VIDEO_LINK_RESOLVE", token, body("url", "https://b23.tv/bMyOKXv")))
            .containsEntry("url", "https://www.bilibili.com/video/BV1GJ411x7h7");
        // Not to one video, not found, or not a short link at all: refused, with the link's field.
        for (String url : new String[] {"https://b23.tv/space01", "https://b23.tv/gone123", "https://example.com/x"}) {
            assertThat(codes(refused("QB_VIDEO_LINK_RESOLVE", token, body("url", url), 422))).as(url)
                .containsExactly(ContentCodes.VIDEO_LINK_NOT_RESOLVED);
        }
        // Only the code is asked for (the stub saw no other path), and only by a sponsor.
        assertThat(B23.requests()).extracting(StubHttpServer.Received::path)
            .containsExactly("/bMyOKXv", "/space01", "/gone123");
        refused("QB_VIDEO_LINK_RESOLVE", taker(u1()), body("url", "https://b23.tv/bMyOKXv"), 403);
    }
}
