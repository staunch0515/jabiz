package com.jabiz.quizbuks.sharedvideo;

import com.jabiz.entity.Violation;
import com.jabiz.quizbuks.topic.TopicRules;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SharedVideoRulesTest {

    static final String YOUTUBE = "https://www.youtube.com/watch?v=dQw4w9WgXcQ";
    static final Map<String, TopicRules.Word> WORDS = Map.of(
        "pets", new TopicRules.Word("pets", true, null),
        "old", new TopicRules.Word("old", false, null));

    static List<String> codes(List<Violation> problems) {
        return problems.stream().map(Violation::ruleCode).toList();
    }

    static SharedVideoRules.Share share(String kind, String url, Integer duration, Map<String, String> titles,
        List<String> topics) {
        return new SharedVideoRules.Share(kind, url, duration, titles, topics, false);
    }

    static final Map<String, String> TITLES = SharedVideoRules.titles(" 猫 ", "猫", "Cats");

    @Test
    void aCompleteShareHasNoProblem() {
        assertThat(SharedVideoRules.problems(share("VIDEO_LINK", YOUTUBE, 95, TITLES, List.of("pets")), WORDS, 10))
            .isEmpty();
        assertThat(TITLES).containsEntry("zh", "猫");
    }

    @Test
    void onlyAVideoLinkWithItsDurationIsShared() {
        assertThat(codes(SharedVideoRules.problems(share("LINK", YOUTUBE, 95, TITLES, List.of("pets")), WORDS, 10)))
            .containsExactly(SharedVideoCodes.NOT_VIDEO);
        assertThat(codes(SharedVideoRules.problems(share("VIDEO_LINK", YOUTUBE, null, TITLES, List.of("pets")), WORDS,
            10))).containsExactly(SharedVideoCodes.ASSET_INCOMPLETE);
        // Not a link QuizBuks plays: plain http, or an address of a known site that is not one video (a channel).
        assertThat(codes(SharedVideoRules.problems(share("VIDEO_LINK", "http://video.example/x", 95, TITLES,
            List.of("pets")), WORDS, 10))).containsExactly(SharedVideoCodes.ASSET_INCOMPLETE);
        assertThat(codes(SharedVideoRules.problems(share("VIDEO_LINK", "https://www.youtube.com/@channel", 95,
            TITLES, List.of("pets")), WORDS, 10))).containsExactly(SharedVideoCodes.ASSET_INCOMPLETE);
        assertThat(codes(SharedVideoRules.problems(share("VIDEO_LINK", YOUTUBE, 7_201, TITLES, List.of("pets")), WORDS,
            10))).containsExactly(SharedVideoCodes.ASSET_INCOMPLETE);
    }

    @Test
    void aVideoFileOrAnotherPageIsSharedToo() {
        // F-S15: any link QuizBuks plays, a video file's or another https page's as well as the seven sites'.
        assertThat(SharedVideoRules.problems(share("VIDEO_LINK", "https://videos.example.com/cats.mp4", 95, TITLES,
            List.of("pets")), WORDS, 10)).isEmpty();
        assertThat(SharedVideoRules.problems(share("VIDEO_LINK", "https://video.example/x", 95, TITLES,
            List.of("pets")), WORDS, 10)).isEmpty();
    }

    @Test
    void everyLanguagesTitleAndATopicOfTheListAreRequired() {
        List<Violation> problems = SharedVideoRules.problems(share("VIDEO_LINK", YOUTUBE, 95,
            SharedVideoRules.titles("猫", " ", null), List.of()), WORDS, 10);
        assertThat(problems).extracting(Violation::field).containsExactly("title.ja", "title.en", "topics");
        assertThat(codes(problems)).containsExactly(SharedVideoCodes.TITLE_REQUIRED, SharedVideoCodes.TITLE_REQUIRED,
            SharedVideoCodes.TOPIC_REQUIRED);
        assertThat(codes(SharedVideoRules.problems(share("VIDEO_LINK", YOUTUBE, 95, TITLES, List.of("old", "nope")),
            WORDS, 10))).containsExactly(TopicCodesOf.NOT_SELECTABLE, TopicCodesOf.NOT_SELECTABLE);
        assertThat(codes(SharedVideoRules.problems(share("VIDEO_LINK", YOUTUBE, 95, TITLES, List.of("pets")), WORDS,
            0))).containsExactly(TopicCodesOf.TOO_MANY);
        assertThat(SharedVideoRules.topics(java.util.Arrays.asList(" pets", "pets ", "", null, "old")))
            .containsExactly("pets", "old");
    }

    @Test
    void verticalByTheProviderOrAShortsLink() {
        assertThat(SharedVideoRules.vertical(true, YOUTUBE)).isTrue();
        assertThat(SharedVideoRules.vertical(false, "https://www.youtube.com/shorts/dQw4w9WgXcQ")).isTrue();
        assertThat(SharedVideoRules.vertical(false, YOUTUBE)).isFalse();
    }

    @Test
    void whatEachStateAllows() {
        assertThat(QbSharedVideos.STATUSES).filteredOn(SharedVideoRules::available).containsExactly("APPROVED");
        assertThat(QbSharedVideos.STATUSES).filteredOn(SharedVideoRules::withdrawable)
            .containsExactly("PENDING", "APPROVED");
        assertThat(QbSharedVideos.STATUSES).filteredOn(SharedVideoRules::takeable)
            .containsExactly("APPROVED", "WITHDRAWN");
    }

    /** The topics' codes the rules report. */
    static final class TopicCodesOf {
        static final String NOT_SELECTABLE = com.jabiz.quizbuks.topic.TopicCodes.NOT_SELECTABLE;
        static final String TOO_MANY = com.jabiz.quizbuks.topic.TopicCodes.TOO_MANY;
    }
}
