package com.jabiz.quizbuks.content;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Whether a version of a quiz has a video mainland China cannot play (F-S11, docs/quizbuks/plans/impl/F-S11.md;
 * Q106 §2.1): a video link whose site ({@link VideoEmbeds#site}) is not one of the sites the controlled parameter
 * {@code qb.video.mainland-accessible-sites} lists, and any video link of a site QuizBuks does not recognise (a
 * material's link may be any address: rather show it to fewer takers than one they cannot open). Every language counts:
 * the base's materials and each other language's own (Q73). A blank link is no video.
 *
 * <p>Only the links: the shared videos of F-S12 join through {@link #foreign(Stream, Set)} once they exist.
 * The App's and the console's {@code qb/video.ts} ({@code foreignVideo}) read links the same way, with the same cases.
 */
public final class ForeignVideos {

    /** The parameter's first value: the sites mainland China plays, as {@link VideoEmbeds.Site#key} names them. */
    public static final String DEFAULT_SITES = "bilibili,youku,tencent";

    private ForeignVideos() {
    }

    /**
     * The sites of a parameter's value (comma-separated keys; unknown keys are ignored); the default's when there is
     * no value yet (before the setup's proposal was published).
     */
    public static Set<VideoEmbeds.Site> sites(String value) {
        String given = value == null ? DEFAULT_SITES : value;
        Set<VideoEmbeds.Site> sites = EnumSet.noneOf(VideoEmbeds.Site.class);
        for (String key : given.split(",")) {
            VideoEmbeds.Site.ofKey(key).ifPresent(sites::add);
        }
        return sites;
    }

    /** Whether a version's snapshot has a foreign video, in any of its languages. */
    public static boolean foreign(QuizSnapshot snapshot, Set<VideoEmbeds.Site> accessible) {
        return snapshot != null && foreign(videoLinks(snapshot).stream(), accessible);
    }

    /** Whether any of these video links is foreign: of a site not in {@code accessible}, or of none recognised. */
    public static boolean foreign(Stream<String> videoLinks, Set<VideoEmbeds.Site> accessible) {
        return videoLinks.filter(url -> url != null && !url.isBlank())
            .anyMatch(url -> !VideoEmbeds.site(url).map(accessible::contains).orElse(false));
    }

    /** The links of a snapshot's video materials, the base's and every other language's own. */
    public static List<String> videoLinks(QuizSnapshot snapshot) {
        Stream<QuizSnapshot.Material> base = snapshot.materials().stream();
        Stream<QuizSnapshot.Material> others = snapshot.translations().values().stream()
            .flatMap(language -> language.materials().stream());
        return Stream.concat(base, others).filter(material -> MaterialKind.VIDEO_LINK.name().equals(material.kind()))
            .map(QuizSnapshot.Material::url).filter(url -> url != null && !url.isBlank()).toList();
    }

    /** {@link #foreign(Stream, Set)} of a collection. */
    public static boolean foreign(Collection<String> videoLinks, Set<VideoEmbeds.Site> accessible) {
        return foreign(videoLinks.stream(), accessible);
    }
}
