package com.jabiz.runtime.push;

import com.jabiz.push.PushTemplate;
import com.jabiz.runtime.test.TestPushService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The settings, the allowed push services, the push services' answers and the templates (decision D69). */
class PushPropertiesTest {

    private static PushProperties enabled(String publicKey, String privateKey, String subject, List<String> hosts) {
        return new PushProperties(true, new PushProperties.WebPush(publicKey, privateKey, subject, hosts));
    }

    @Test
    void onlyTheAllowedPushServicesOverHttpsAreAddressed() {
        PushProperties defaults = enabled(null, null, null, null);
        assertThat(defaults.webPush().allowedHosts()).isEqualTo(PushProperties.DEFAULT_HOSTS);
        assertThat(defaults.allowedEndpoint("https://fcm.googleapis.com/fcm/send/abc")).isTrue();
        assertThat(defaults.allowedEndpoint("https://updates.push.services.mozilla.com/wpush/v2/x")).isTrue();
        assertThat(defaults.allowedEndpoint("https://web.push.apple.com/QK")).isTrue();
        assertThat(defaults.allowedEndpoint("https://wns2-par02p.notify.windows.com/w/?token=x")).isTrue();
        for (String refused : List.of("http://fcm.googleapis.com/fcm/send/abc", "https://evil.example.com/push",
            "https://fcm.googleapis.com.evil.example/x", "https://push.services.mozilla.com/x",
            "https://user@fcm.googleapis.com/x", "https://fcm.googleapis.com/x#frag", "not a url",
            "https://fcm.googleapis.com/" + "a".repeat(1000))) {
            assertThat(defaults.allowedEndpoint(refused)).as(refused).isFalse();
        }
        assertThat(defaults.allowedEndpoint(null)).isFalse();
        PushProperties local = enabled(null, null, null, List.of("localhost"));
        assertThat(local.allowedEndpoint("http://localhost:8123/push/1")).isTrue();
        assertThat(local.allowedEndpoint("http://example.com/push/1")).isFalse();
    }

    @Test
    void settingsAreCheckedOnlyWhenOn() {
        assertThat(new PushProperties(false, null).problems()).isEmpty();
        String[] keys = TestPushService.vapidKeys();
        assertThat(enabled(keys[0], keys[1], "mailto:ops@example.com", null).problems()).isEmpty();
        assertThat(enabled("nope", "AAAA", "ops@example.com", List.of("bad host!")).problems()).containsExactly(
            "jabiz.push.web-push.public-key: must be the VAPID public key (an uncompressed P-256 point, Base64url)",
            "jabiz.push.web-push.private-key: must be the VAPID private key (32 bytes, Base64url), from the environment",
            "jabiz.push.web-push.subject: must be a mailto: or https: contact for the push services",
            "jabiz.push.web-push.allowed-hosts: \"bad host!\" is not a host or *.domain");
        assertThat(enabled(keys[0], keys[1], "x", null).toString()).doesNotContain(keys[1]);
        // A private key rotated without its public key: every push would be refused.
        String[] other = TestPushService.vapidKeys();
        assertThat(enabled(keys[0], other[1], "mailto:ops@example.com", null).problems()).containsExactly(
            "jabiz.push.web-push.public-key: is not the public key of the configured private key");
    }

    @Test
    void pushServicesAnswersMeanDeliveredGoneOrTryAgain() {
        assertThat(WebPushSender.outcome(201)).isEqualTo(WebPushSender.Outcome.DELIVERED);
        assertThat(WebPushSender.outcome(404)).isEqualTo(WebPushSender.Outcome.GONE);
        assertThat(WebPushSender.outcome(410)).isEqualTo(WebPushSender.Outcome.GONE);
        assertThat(WebPushSender.outcome(429)).isEqualTo(WebPushSender.Outcome.FAILED);
        assertThat(WebPushSender.outcome(500)).isEqualTo(WebPushSender.Outcome.FAILED);
        // An endpoint of no allowed push service is never posted to: forgotten.
        WebPushSender sender = new WebPushSender(enabled(null, null, null, null),
            Clock.fixed(Instant.parse("2026-11-02T00:00:00Z"), ZoneOffset.UTC));
        assertThat(sender.send("https://evil.example.com/x", "p", "a", new byte[0], null).block())
            .isEqualTo(WebPushSender.Outcome.GONE);
    }

    @Test
    void templatesHaveNamesParametersAndEntries() {
        PushTemplate template = PushTemplate.define("shop.shipped", t -> t.param("orderNo").entries("portal"));
        assertThat(template.appliesTo("portal")).isTrue();
        assertThat(template.appliesTo("admin")).isFalse();
        assertThat(PushTemplate.define("shop.news", t -> { }).appliesTo("admin")).isTrue();
        assertThat(template.titleKey()).isEqualTo("push.shop.shipped.title");
        assertThatThrownBy(() -> PushTemplate.define("bad name", t -> { })).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PushTemplate.define("x", t -> t.param("Bad"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PushTemplate.define("x", t -> t.param("a").param("a")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PushTemplate.define("x", t -> t.entries("Portal")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(SendPush.LINK.matcher("orders/42").matches()).isTrue();
        assertThat(SendPush.LINK.matcher("orders?tab=open").matches()).isTrue();
        assertThat(SendPush.LINK.matcher("https://evil.example.com").matches()).isFalse();
        assertThat(SendPush.LINK.matcher("//evil.example.com").matches()).isFalse();
    }
}
