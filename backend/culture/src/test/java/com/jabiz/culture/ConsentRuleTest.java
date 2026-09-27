package com.jabiz.culture;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.jabiz.culture.ConsentRule.Media.PHOTO;
import static com.jabiz.culture.ConsentRule.Media.VIDEO;
import static com.jabiz.culture.ConsentRule.Media.VOICE;
import static org.assertj.core.api.Assertions.assertThat;

class ConsentRuleTest {

    private static Map<String, Object> consent(String party, boolean photo, boolean video, boolean voice,
        Instant withdrawn) {
        Map<String, Object> consent = new HashMap<>();
        consent.put("party", party);
        consent.put("coversPhoto", photo);
        consent.put("coversVideo", video);
        consent.put("coversVoice", voice);
        consent.put("withdrawnTime", withdrawn);
        return consent;
    }

    private static Map<String, Object> self(boolean photo, boolean video, boolean voice) {
        return consent("PARTICIPANT", photo, video, voice, null);
    }

    private static Map<String, Object> guardian(boolean photo, boolean video, boolean voice) {
        return consent("GUARDIAN", photo, video, voice, null);
    }

    private static final Set<ConsentRule.Media> NOTHING = EnumSet.noneOf(ConsentRule.Media.class);

    @Test
    void theParticipantsOwnConsentIsAlwaysRequired() {
        assertThat(ConsentRule.missing(true, false, List.of(), NOTHING)).containsExactly("PARTICIPANT");
        assertThat(ConsentRule.missing(true, false, List.of(guardian(true, true, true)), NOTHING))
            .containsExactly("PARTICIPANT");
        assertThat(ConsentRule.missing(true, false, List.of(self(false, false, false)), NOTHING)).isEmpty();
    }

    @Test
    void aGuardianIsRequiredForMinorsWhenTheSwitchIsOn() {
        List<Map<String, Object>> onlySelf = List.of(self(true, true, true));
        assertThat(ConsentRule.missing(false, true, onlySelf, NOTHING)).containsExactly("GUARDIAN");
        assertThat(ConsentRule.missing(false, false, onlySelf, NOTHING)).isEmpty();
        assertThat(ConsentRule.missing(true, true, onlySelf, NOTHING)).isEmpty();
        assertThat(ConsentRule.missing(false, true, List.of(self(true, true, true), guardian(true, true, true)),
            NOTHING)).isEmpty();
    }

    @Test
    void everyRequiredConsentMustCoverEveryKindOfMedia() {
        Set<ConsentRule.Media> all = EnumSet.of(PHOTO, VIDEO, VOICE);
        assertThat(ConsentRule.missing(true, true, List.of(self(true, false, true)), all)).containsExactly("VIDEO");
        // The participant covers everything, the guardian not the voice: both are needed.
        assertThat(ConsentRule.missing(false, true, List.of(self(true, true, true), guardian(true, true, false)), all))
            .containsExactly("VOICE");
        // Two records of one party add up.
        assertThat(ConsentRule.missing(true, false, List.of(self(true, false, false), self(false, true, true)), all))
            .isEmpty();
    }

    @Test
    void withdrawnConsentsDoNotCount() {
        Instant withdrawn = Instant.parse("2026-05-01T00:00:00Z");
        assertThat(ConsentRule.missing(true, false, List.of(consent("PARTICIPANT", true, true, true, withdrawn)),
            NOTHING)).containsExactly("PARTICIPANT");
        assertThat(ConsentRule.missing(true, false, List.of(consent("PARTICIPANT", true, true, true, withdrawn),
            self(false, false, false)), EnumSet.of(PHOTO))).containsExactly("PHOTO");
    }
}
