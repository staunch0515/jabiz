package com.jabiz.quizbuks.messaging;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The first drafts {@code QB_SETUP} creates, one per kind of legal text (Q7b): marked test placeholders, never the
 * real texts. The owner or their counsel supplies those (Q7-4; Japanese governs, English is supplied too); a content
 * administrator pastes them into the drafts in the administration and publishes them before going live. Nothing is
 * published by setup: live mode warns at startup while no terms or privacy policy is in effect.
 */
public final class LegalDrafts {

    /** The marker every placeholder starts with, in every language: a page showing it shows a test text. */
    public static final String MARKER = "[TEST PLACEHOLDER]";

    /** A draft's title and text, by language. */
    public record Draft(Map<String, String> title, Map<String, String> body) {
        public Draft {
            title = Map.copyOf(title);
            body = Map.copyOf(body);
        }
    }

    private static final String NOTE_EN = """
        > **%s This is not a legal text.** It exists so that development and tests have something to publish. \
        The operator's counsel supplies the real text, in Japanese (which governs) and in English; an administrator \
        replaces this draft with it before going live.
        """.formatted(MARKER);
    private static final String NOTE_JA = """
        > **%s これは法的な文書ではありません。** 開発とテストのための仮の文章です。正式な文章は運営者（または弁護士）が\
        日本語（正文）と英語で用意し、公開前に管理者がこの下書きと差し替えます。
        """.formatted(MARKER);
    private static final String NOTE_ZH = """
        > **%s 这不是法律文本。** 仅供开发与测试发布使用。正式文本由运营方（或律师）以日文（准据文本）和英文提供，\
        上线前由管理员替换此草稿。
        """.formatted(MARKER);

    private static Draft draft(String en, String ja, String zh, String sectionEn, String sectionJa, String sectionZh) {
        Map<String, String> title = new LinkedHashMap<>();
        title.put("en", MARKER + " " + en);
        title.put("ja", MARKER + " " + ja);
        title.put("zh", MARKER + " " + zh);
        Map<String, String> body = new LinkedHashMap<>();
        body.put("en", NOTE_EN + "\n## 1. " + sectionEn + "\n\nPlaceholder text.\n");
        body.put("ja", NOTE_JA + "\n## 第1条 " + sectionJa + "\n\n仮の文章です。\n");
        body.put("zh", NOTE_ZH + "\n## 第一条 " + sectionZh + "\n\n占位文本。\n");
        return new Draft(title, body);
    }

    /** The placeholder of each kind. */
    public static Draft of(String kind) {
        return switch (kind) {
            case QbLegal.TERMS_APP -> draft("Terms of use", "利用規約", "用户条款",
                "Scope", "適用範囲", "适用范围");
            case QbLegal.TERMS_SPONSOR -> draft("Sponsor terms", "スポンサー利用規約", "商家条款",
                "Scope", "適用範囲", "适用范围");
            case QbLegal.PRIVACY -> draft("Privacy policy", "プライバシーポリシー", "隐私政策",
                "Information we collect", "取得する情報", "我们收集的信息");
            case QbLegal.COMMERCE_NOTICE -> draft("Notice under the Act on Specified Commercial Transactions",
                "特定商取引法に基づく表記", "特定商取引法标示", "Seller", "販売事業者", "销售方");
            case QbLegal.AI_NOTICE -> draft("AI notice", "AI 利用に関する説明", "AI 使用说明",
                "What is sent to the AI provider", "AI 提供者に送信される情報", "发送给 AI 提供方的内容");
            case QbLegal.CONTEST_RULES -> draft("Official rules of reward campaigns", "懸賞キャンペーンの規約",
                "有奖活动规则", "Who runs a campaign", "主催者", "主办方");
            default -> throw new IllegalArgumentException("No legal text " + kind);
        };
    }

    private LegalDrafts() {}
}
