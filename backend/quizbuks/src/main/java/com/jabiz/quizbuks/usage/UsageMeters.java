package com.jabiz.quizbuks.usage;

import java.util.List;
import java.util.Map;

/**
 * What is metered of a sponsor's use of the platform (F-C2, docs/quizbuks/plans/impl/F-C2.md §1): the only place the
 * meters are listed. The daily snapshot's template ({@value QbUsage#MEASURE_TEMPLATE}) gives one row for each, the
 * dictionary {@value QbUsage#METER_DICTIONARY} names them, and {@link UsageMetersCheck} fails the start when the
 * template and this list differ. Only metered and shown in the first launch: nothing is refused or charged for.
 */
public final class UsageMeters {

    /** What a meter counts in. */
    public enum Unit { BYTES, COUNT, TOKENS }

    /**
     * A meter.
     *
     * @param code  its code, as stored ({@code QbUsageDay.meter}) and in the template's rows
     * @param unit  what it counts in
     * @param names its name in each language (the dictionary's)
     */
    public record Meter(String code, Unit unit, Map<String, String> names) {
        public Meter {
            names = Map.copyOf(names);
        }
    }

    /** The uploaded files' bytes as uploaded (no thumbnails or other variants, A4-23), counted for their uploader. */
    public static final String STORAGE_BYTES = "storage.bytes";
    public static final String STORAGE_FILES = "storage.files";
    /** The assets of the library (links to outside videos count here, as one each, never in bytes). */
    public static final String ASSETS_COUNT = "assets.count";
    public static final String ASSETS_TEXT_BYTES = "assets.text-bytes";
    public static final String CONTENT_QUIZZES = "content.quizzes";
    public static final String CONTENT_VERSIONS = "content.versions";
    public static final String CONTENT_MATERIALS = "content.materials";
    /** The month's AI tokens of the sponsor's jobs ({@code QbAiUsage}); the AI help's are not there (§B). */
    public static final String AI_TOKENS = "ai.tokens";

    public static final List<Meter> ALL = List.of(
        new Meter(STORAGE_BYTES, Unit.BYTES, Map.of("en", "Storage used", "zh", "已用存储",
            "ja", "使用中のストレージ")),
        new Meter(STORAGE_FILES, Unit.COUNT, Map.of("en", "Files", "zh", "文件数", "ja", "ファイル数")),
        new Meter(ASSETS_COUNT, Unit.COUNT, Map.of("en", "Assets", "zh", "资源数", "ja", "リソース数")),
        new Meter(ASSETS_TEXT_BYTES, Unit.BYTES, Map.of("en", "Text of the assets", "zh", "资源的文字",
            "ja", "リソースのテキスト")),
        new Meter(CONTENT_QUIZZES, Unit.COUNT, Map.of("en", "Quizzes", "zh", "Quiz 数", "ja", "クイズ数")),
        new Meter(CONTENT_VERSIONS, Unit.COUNT, Map.of("en", "Quiz versions", "zh", "Quiz 版本数",
            "ja", "クイズのバージョン数")),
        new Meter(CONTENT_MATERIALS, Unit.COUNT, Map.of("en", "Materials", "zh", "资料数", "ja", "資料数")),
        new Meter(AI_TOKENS, Unit.TOKENS, Map.of("en", "AI tokens this month", "zh", "本月 AI 用量（token）",
            "ja", "今月の AI 使用量（トークン）")));

    /** The meters' codes, in the order of {@link #ALL}. */
    public static List<String> codes() {
        return ALL.stream().map(Meter::code).toList();
    }

    private UsageMeters() {}
}
