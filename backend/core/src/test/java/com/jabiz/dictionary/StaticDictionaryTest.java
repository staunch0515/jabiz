package com.jabiz.dictionary;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StaticDictionaryTest {

    private static final StaticDictionary STATUS = StaticDictionary.define("urn:dict:status", d -> d
        .item("OPEN", "zh", "打开", "ja", "オープン", "en", "Open")
        .item("DONE", "en", "Done")
        .disabledItem("LEGACY", "en", "Legacy"));

    @Test
    void labelsFollowTheLocaleAndFallBackToEnglish() {
        assertThat(STATUS.items("urn:dict:status", Locale.JAPANESE)).containsExactly(
            new DictItem("OPEN", "オープン", 0, true),
            new DictItem("DONE", "Done", 1, true),
            new DictItem("LEGACY", "Legacy", 2, false));
        assertThat(STATUS.items("urn:dict:status", Locale.forLanguageTag("zh-CN")).getFirst().label()).isEqualTo("打开");
    }

    @Test
    void onlyItsOwnUrnIsSupported() {
        assertThat(STATUS.supports("urn:dict:status")).isTrue();
        assertThat(STATUS.supports("urn:dict:other")).isFalse();
        assertThat(STATUS.items("urn:dict:other", Locale.ENGLISH)).isEmpty();
        assertThat(STATUS.urn()).isEqualTo("urn:dict:status");
        assertThat(STATUS.entries()).hasSize(3);
    }

    @Test
    void codesOnlyDictionaryUsesTheCodesAsLabels() {
        StaticDictionary codes = StaticDictionary.ofCodes("urn:dict:codes", List.of("A", "B"));
        assertThat(codes.items("urn:dict:codes", Locale.CHINESE)).extracting(DictItem::label).containsExactly("A", "B");
    }

    @Test
    void invalidDeclarationsAreRejected() {
        assertThatThrownBy(() -> StaticDictionary.define(" ", d -> {})).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StaticDictionary.define("u", d -> d.item("A", "en")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StaticDictionary.define("u", d -> d.item("A").item("A")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DictItem(null, "x", 0, true)).isInstanceOf(NullPointerException.class);
        assertThat(new DictItem("A", null, 0, true).label()).isEqualTo("A");
        assertThat(DictionaryLookup.NONE.enabledCodes("x")).isEmpty();
    }
}
