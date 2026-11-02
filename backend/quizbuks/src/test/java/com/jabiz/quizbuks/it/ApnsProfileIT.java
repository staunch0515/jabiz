package com.jabiz.quizbuks.it;

import com.jabiz.runtime.push.PushProperties;
import com.jabiz.runtime.test.PostgresIntegrationTest;
import com.jabiz.runtime.test.TestPushService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Spring profile "apns" beside "push" (application-apns.yml; platform 17k-b): with the team's key from the
 * environment, the application starts (the startup check takes the team and key ids, the .p8 key and the bundle id)
 * and pushes to the iOS app's bundle on Apple's production service; the key never shows in the settings' text.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "spring.profiles.active=push,apns",
    "JABIZ_PUSH_SUBJECT=mailto:push@example.com",
    "JABIZ_APNS_TEAM_ID=TEAM123456",
    "JABIZ_APNS_KEY_ID=KEY1234567"})
class ApnsProfileIT extends PostgresIntegrationTest {

    static final String[] VAPID = TestPushService.vapidKeys();

    /** The team's key: an EC P-256 key made for the run (a .p8 file's Base64 body); none is kept in the code. */
    @DynamicPropertySource
    static void keys(DynamicPropertyRegistry registry) throws Exception {
        registry.add("JABIZ_PUSH_PUBLIC_KEY", () -> VAPID[0]);
        registry.add("JABIZ_PUSH_PRIVATE_KEY", () -> VAPID[1]);
        java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("EC");
        generator.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
        String key = java.util.Base64.getEncoder().encodeToString(generator.generateKeyPair().getPrivate().getEncoded());
        registry.add("JABIZ_APNS_PRIVATE_KEY", () -> key);
    }

    @Autowired
    PushProperties push;

    @Test
    void theIosAppsPushesGoToItsBundleOnApplesProductionService() {
        assertThat(push.enabled()).isTrue();
        assertThat(push.problems()).isEmpty();
        PushProperties.Apns apns = push.apns();
        assertThat(apns.configured()).isTrue();
        assertThat(apns.teamId()).isEqualTo("TEAM123456");
        assertThat(apns.keyId()).isEqualTo("KEY1234567");
        assertThat(apns.topic()).isEqualTo("com.quizbuks.app");
        assertThat(apns.environment()).isEqualTo(PushProperties.APNS_PRODUCTION);
        assertThat(apns.baseUrl()).isEqualTo("https://api.push.apple.com");
        assertThat(apns.toString()).doesNotContain(apns.privateKey());
    }
}
