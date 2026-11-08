package com.jabiz.quizbuks.sharedvideo;

import com.jabiz.entity.Violation;
import com.jabiz.quizbuks.content.ContentLimits;
import com.jabiz.quizbuks.content.QbContent;
import com.jabiz.quizbuks.content.VideoEmbeds;
import com.jabiz.quizbuks.topic.TopicRules;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The rules of sharing a video (F-S12, docs/quizbuks/plans/impl/F-S12.md), in one place, plain Java: what an asset
 * must be to be shared, what the share must say, and when a publication may use a shared video.
 */
public final class SharedVideoRules {

    /** The library's kind of a video link ({@code AssetKind.VIDEO_LINK}). */
    public static final String VIDEO_LINK = "VIDEO_LINK";

    private SharedVideoRules() {
    }

    /** What is shared: the asset's link and duration, and the provider's titles, topics and orientation. */
    public record Share(String assetKind, String url, Integer durationSec, Map<String, String> titles,
        List<String> topics, boolean vertical) {}

    /**
     * Every problem of a share at once: only a video link QuizBuks plays, with its duration in range (the asset's: the
     * provider fixes the asset first); a title in every language of the platform; at least one topic, each one offered
     * by the word list (a word deactivated or merged is not chosen anew), no more than {@code maxTopics}.
     */
    public static List<Violation> problems(Share share, Map<String, TopicRules.Word> words, int maxTopics) {
        List<Violation> problems = new ArrayList<>();
        if (!VIDEO_LINK.equals(share.assetKind())) {
            problems.add(new Violation("assetId", SharedVideoCodes.NOT_VIDEO, "Only a video link is shared",
                Map.of("kind", String.valueOf(share.assetKind()))));
            return problems;
        }
        Integer duration = share.durationSec();
        if (VideoEmbeds.player(share.url()).isEmpty() || duration == null
            || duration < ContentLimits.MIN_VIDEO_SECONDS || duration > ContentLimits.MAX_VIDEO_SECONDS) {
            problems.add(new Violation("assetId", SharedVideoCodes.ASSET_INCOMPLETE, "The asset needs a link QuizBuks "
                + "plays and its duration", Map.of()));
        }
        for (String language : QbContent.LANGUAGES) {
            String title = share.titles() == null ? null : share.titles().get(language);
            if (title == null || title.isBlank()) {
                problems.add(new Violation("title." + language, SharedVideoCodes.TITLE_REQUIRED, "The title in "
                    + language + " is missing", Map.of("language", language)));
            }
        }
        List<String> topics = topics(share.topics());
        if (topics.isEmpty()) {
            problems.add(new Violation("topics", SharedVideoCodes.TOPIC_REQUIRED, "Choose at least one topic",
                Map.of()));
        }
        problems.addAll(TopicRules.choiceProblems("topics", topics, List.of(), words));
        problems.addAll(TopicRules.countProblems("topics", new TopicRules.Topics(topics, List.of()), maxTopics));
        return problems;
    }

    /** The topics given, blanks around each dropped, each once, in the order given. */
    public static List<String> topics(List<String> given) {
        Set<String> out = new LinkedHashSet<>();
        if (given != null) {
            for (String code : given) {
                if (code != null && !code.isBlank()) {
                    out.add(code.strip());
                }
            }
        }
        return List.copyOf(out);
    }

    /** The titles in the platform's languages, blanks around each dropped. */
    public static Map<String, String> titles(String zh, String ja, String en) {
        Map<String, String> titles = new LinkedHashMap<>();
        titles.put("zh", zh == null ? null : zh.strip());
        titles.put("ja", ja == null ? null : ja.strip());
        titles.put("en", en == null ? null : en.strip());
        return titles;
    }

    /** Whether a shared video is vertical: the provider says so, or it is a YouTube Shorts link (as a material's). */
    public static boolean vertical(boolean marked, String url) {
        return marked || VideoEmbeds.shorts(url);
    }

    /** Whether other sponsors may pick it for a publication now (and submit one with it): only an approved one. */
    public static boolean available(String status) {
        return QbSharedVideos.APPROVED.equals(status);
    }

    /** Whether the provider may withdraw it: while it stands (in review or approved). */
    public static boolean withdrawable(String status) {
        return QbSharedVideos.STANDING.contains(status);
    }

    /**
     * Whether it may be taken down: once approved, also after a withdrawal (publications submitted with it still use
     * it); one in review is rejected in its review instead, and one taken down stays so.
     */
    public static boolean takeable(String status) {
        return QbSharedVideos.APPROVED.equals(status) || QbSharedVideos.WITHDRAWN.equals(status);
    }
}
