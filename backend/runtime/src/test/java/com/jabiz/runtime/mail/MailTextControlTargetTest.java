package com.jabiz.runtime.mail;

import com.jabiz.mail.MailTextRules;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MailTextControlTargetTest {

    @Test
    void theLongestTextsOfTheCharactersThatGrowMostFitTheValuesOfAChange() {
        for (MailTextRules.Limits limits : new MailTextRules.Limits[] {MailTextRules.Limits.DEFAULT,
            new MailTextRules.Limits(1_000, 200_000, Set.of(), Set.of())}) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put(MailTextEntities.TEMPLATE, "x".repeat(100));
            values.put(MailTextEntities.LOCALE, "zh-Hant-TW");
            values.put(MailTextEntities.SUBJECT, "\u0001".repeat(limits.maxSubjectLength()));
            values.put(MailTextEntities.BODY, "\u0001".repeat(limits.maxBodyLength()));
            values.put(MailTextControlTarget.BASED_ON, Integer.MAX_VALUE);
            // As ControlChanges records them: Jackson's defaults.
            String json = JsonMapper.builder().build().writeValueAsString(values);
            assertThat(json.length()).isGreaterThan(6 * limits.maxBodyLength())
                .isLessThanOrEqualTo(MailTextControlTarget.maxValuesLength(limits));
        }
    }

    @Test
    void otherCharactersGrowLess() {
        JsonMapper json = JsonMapper.builder().build();
        for (String character : new String[] {"\"", "\\", "\n", "\t", "\u007f", " ", "語", "😀"}) {
            String written = json.writeValueAsString(character);
            assertThat(written.codePointCount(0, written.length()) - 2).as(character).isLessThanOrEqualTo(6);
        }
    }
}
