package com.jabiz.quizbuks.conversation;

import com.jabiz.entity.Violation;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The conversations' rules (Q82): the text, the daily cap, who opens, when a side writes, unread and due. */
class ConversationRulesTest {

    private static final Instant NOW = Instant.parse("2026-11-02T12:00:00Z");

    private static List<String> codes(String body) {
        return ConversationRules.bodyProblems(body).stream().map(Violation::ruleCode).toList();
    }

    @Test
    void aMessageIsPlainTextWithoutAddressesWithinItsLength() {
        assertThat(codes("Where do I collect my prize?")).isEmpty();
        assertThat(codes(null)).containsExactly(ConversationCodes.BODY_REQUIRED);
        assertThat(codes(" \n ")).containsExactly(ConversationCodes.BODY_REQUIRED);
        assertThat(codes("x".repeat(ConversationRules.BODY_LENGTH))).isEmpty();
        assertThat(codes("x".repeat(ConversationRules.BODY_LENGTH + 1)))
            .containsExactly(ConversationCodes.BODY_TOO_LONG);
        // Counted in characters, not UTF-16 units.
        assertThat(codes("😀".repeat(ConversationRules.BODY_LENGTH))).isEmpty();
        // The notices' address rule (Q7c): written-out dots and full-width letters too.
        assertThat(codes("mail me: me@example.com")).containsExactly(ConversationCodes.ADDRESS);
        assertThat(codes("see example[.]com")).containsExactly(ConversationCodes.ADDRESS);
        assertThat(codes("ｅｘａｍｐｌｅ．ｃｏｍ")).containsExactly(ConversationCodes.ADDRESS);
        assertThat(codes("call 0312345678")).containsExactly(ConversationCodes.ADDRESS);
        assertThat(ConversationRules.stored("  hi  ")).isEqualTo("hi");
    }

    @Test
    void aSideSendsSoManyIn24Hours() {
        List<Instant> two = List.of(NOW.minus(Duration.ofHours(23)), NOW.minus(Duration.ofMinutes(1)));
        assertThat(ConversationRules.overDailyCap(two, NOW, 3)).isFalse();
        assertThat(ConversationRules.overDailyCap(two, NOW, 2)).isTrue();
        // Older than a day: not counted.
        assertThat(ConversationRules.overDailyCap(List.of(NOW.minus(Duration.ofHours(25)), NOW.minus(Duration
            .ofHours(24))), NOW, 1)).isFalse();
        assertThat(ConversationRules.overDailyCap(List.of(), NOW, 0)).isTrue();
    }

    @Test
    void onlyATakerWhoCompletedOrReferredOpensWithAnUnblockedSponsor() {
        assertThat(ConversationRules.openRefusal(new ConversationRules.Opening(true, true, false, false, false,
            false))).isNull();
        assertThat(ConversationRules.openRefusal(new ConversationRules.Opening(true, false, true, false, false,
            false))).isNull();
        assertThat(ConversationRules.openRefusal(new ConversationRules.Opening(true, false, false, false, false,
            false))).isEqualTo(ConversationCodes.NOT_ELIGIBLE);
        // A shop's storefront quiz, and a sponsor's own quiz.
        assertThat(ConversationRules.openRefusal(new ConversationRules.Opening(false, true, false, false, false,
            false))).isEqualTo(ConversationCodes.NOT_ELIGIBLE);
        assertThat(ConversationRules.openRefusal(new ConversationRules.Opening(true, true, false, true, false,
            false))).isEqualTo(ConversationCodes.NOT_ELIGIBLE);
        assertThat(ConversationRules.openRefusal(new ConversationRules.Opening(true, true, false, false, true,
            false))).isEqualTo(ConversationCodes.BLOCKED);
        assertThat(ConversationRules.openRefusal(new ConversationRules.Opening(true, true, false, false, false,
            true))).isEqualTo(ConversationCodes.UNAVAILABLE);
    }

    @Test
    void aSideWritesOnlyInAnOpenUnblockedConversation() {
        assertThat(ConversationRules.sendRefusal(QbConversations.OPEN, false, false)).isNull();
        assertThat(ConversationRules.sendRefusal(QbConversations.CLOSED, false, false))
            .isEqualTo(ConversationCodes.CLOSED);
        assertThat(ConversationRules.sendRefusal(QbConversations.OPEN, true, false))
            .isEqualTo(ConversationCodes.BLOCKED);
        assertThat(ConversationRules.sendRefusal(QbConversations.OPEN, false, true))
            .isEqualTo(ConversationCodes.UNAVAILABLE);
    }

    @Test
    void unreadIsTheOtherSidesMessageAfterTheLastRead() {
        Instant last = NOW;
        assertThat(ConversationRules.unread(QbConversations.TAKER, QbConversations.SPONSOR, last, null)).isTrue();
        assertThat(ConversationRules.unread(QbConversations.TAKER, QbConversations.SPONSOR, last,
            last.minusSeconds(1))).isTrue();
        assertThat(ConversationRules.unread(QbConversations.TAKER, QbConversations.SPONSOR, last, last)).isFalse();
        assertThat(ConversationRules.unread(QbConversations.TAKER, QbConversations.TAKER, last, null)).isFalse();
        assertThat(ConversationRules.unread(QbConversations.SPONSOR, null, last, null)).isFalse();
    }

    @Test
    void aConversationIsDueAfterItsQuietDays() {
        Instant last = NOW.minus(Duration.ofDays(90));
        assertThat(ConversationRules.due(QbConversations.OPEN, last, NOW, 90)).isTrue();
        assertThat(ConversationRules.due(QbConversations.OPEN, last.plusSeconds(1), NOW, 90)).isFalse();
        assertThat(ConversationRules.due(QbConversations.CLOSED, last, NOW, 90)).isFalse();
        assertThat(ConversationRules.due(QbConversations.OPEN, last, NOW, 0)).isFalse();
        assertThat(ConversationRules.due(QbConversations.OPEN, null, NOW, 90)).isFalse();
    }

    @Test
    void theCallersSideIsTheTakersOrTheSponsorsOfASponsorsPublication() {
        assertThat(ConversationRules.sideOf("t", "t", "SPONSOR", "s")).isEqualTo(QbConversations.TAKER);
        assertThat(ConversationRules.sideOf("s", "t", "SPONSOR", "s")).isEqualTo(QbConversations.SPONSOR);
        assertThat(ConversationRules.sideOf("s", "t", "SHOPIFY_SHOP", "s")).isNull();
        assertThat(ConversationRules.sideOf("x", "t", "SPONSOR", "s")).isNull();
        assertThat(ConversationRules.sideOf(null, "t", "SPONSOR", "s")).isNull();
    }
}
