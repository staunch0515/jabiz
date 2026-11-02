package com.jabiz.quizbuks.it;

import com.jabiz.runtime.test.TestPushService;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The taker's push notification of a prize won (platform 17k, decision D69): with the profile "push" (its VAPID keys
 * from the environment) the application starts, and the message of a reward
 * queues one notification of the template {@code qb.prize-won} to the taker, leading to the attempt's result; a
 * redelivered event queues none, and a pending reward paid later is not announced a second time.
 */
@TestPropertySource(properties = {"spring.profiles.active=push", "JABIZ_PUSH_SUBJECT=mailto:push@example.com"})
class PrizePushIT extends InboxItSupport {

    static final String[] VAPID = TestPushService.vapidKeys();

    @DynamicPropertySource
    static void keys(DynamicPropertyRegistry registry) {
        registry.add("JABIZ_PUSH_PUBLIC_KEY", () -> VAPID[0]);
        registry.add("JABIZ_PUSH_PRIVATE_KEY", () -> VAPID[1]);
    }

    /** The push notifications queued for a user: their template and link. */
    private static List<String> pushesOf(String userId) {
        return query("""
            SELECT payload->>'template' AS template, payload->>'link' AS link FROM sys_outbox_event
            WHERE event_type = 'jabiz.push.queued' AND payload->>'userId' = ? ORDER BY event_seq""", userId).stream()
            .map(row -> row.get("template") + " " + row.get("link")).toList();
    }

    @Test
    void aRewardWonPushesOnceToTheTakerLeadingToTheResult() {
        String sponsor = newSponsor(unique("s-push-paid"));
        credit(sponsor, 1_000);
        String publicationId = livePublication(sponsor, "Capitals", Map.of("regions", List.of("GLOBAL")));
        String taker = newTaker(unique("t-push-paid"), "JP");
        Map<String, Object> submitted = play(taker, publicationId, true, true);
        deliverEvents();
        assertThat(pushesOf(taker)).containsExactly("qb.prize-won result/" + submitted.get("attemptId"));

        // Nothing more for the same event, nor for a quiz without a reward.
        deliverEvents();
        assertThat(pushesOf(taker)).hasSize(1);
        String failed = newTaker(unique("t-push-none"), "JP");
        play(failed, publicationId, false, false);
        deliverEvents();
        assertThat(pushesOf(failed)).isEmpty();
    }

    @Test
    void aPendingRewardIsAnnouncedOnceNotAgainWhenItIsPaid() {
        String sponsor = newSponsor(unique("s-push-pending"));
        credit(sponsor, 100);
        String publicationId = livePublication(sponsor, "Pending", Map.of("regions", List.of("GLOBAL")));
        String first = newTaker(unique("t-push-p1"), "JP");
        String second = newTaker(unique("t-push-p2"), "JP");
        String a = (String) start(taker(first), publicationId).get("attemptId");
        String b = (String) start(taker(second), publicationId).get("attemptId");
        clock.advance(Duration.ofSeconds(1));
        submit(taker(first), a, answers(true, true));
        clock.advance(Duration.ofSeconds(1));
        submit(taker(second), b, answers(true, true));
        deliverEvents();
        assertThat(kindsOf(second)).containsExactly("REWARD_PENDING");
        assertThat(pushesOf(second)).containsExactly("qb.prize-won result/" + b);

        credit(sponsor, 1_000);
        deliverEvents();
        assertThat(kindsOf(second)).containsExactlyInAnyOrder("REWARD_PENDING", "REWARD_PAID");
        assertThat(pushesOf(second)).hasSize(1);
    }
}
