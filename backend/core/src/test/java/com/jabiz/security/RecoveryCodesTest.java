package com.jabiz.security;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecoveryCodesTest {

    private static byte[] random() {
        byte[] bytes = new byte[RecoveryCodes.COUNT * RecoveryCodes.BYTES_PER_CODE];
        IntStream.range(0, bytes.length).forEach(i -> bytes[i] = (byte) (i * 37 + 11));
        return bytes;
    }

    @Test
    void makesTenDistinctCodesOfTwoGroupsOfFive() {
        List<String> codes = RecoveryCodes.fromRandom(random());
        assertThat(codes).hasSize(10).doesNotHaveDuplicates()
            .allMatch(code -> code.matches("[A-Z2-7]{5}-[A-Z2-7]{5}"));
        assertThatThrownBy(() -> RecoveryCodes.fromRandom(new byte[3])).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void matchesAStoredHashWhateverTheCaseAndHyphens() {
        List<String> codes = RecoveryCodes.fromRandom(random());
        List<String> hashes = codes.stream().map(RecoveryCodes::hash).toList();
        String third = codes.get(2);
        assertThat(RecoveryCodes.match(third, hashes)).hasValue(hashes.get(2));
        assertThat(RecoveryCodes.match(third.toLowerCase().replace("-", ""), hashes)).hasValue(hashes.get(2));
        assertThat(RecoveryCodes.match("AAAAA-AAAAA", hashes)).isEmpty();
        assertThat(RecoveryCodes.match("123456", hashes)).isEmpty();
        assertThat(RecoveryCodes.match(null, hashes)).isEmpty();
        assertThat(RecoveryCodes.hash(third)).hasSize(64).doesNotContain(third.replace("-", ""));
    }

    @Test
    void tellsRecoveryCodesFromTotpCodes() {
        assertThat(RecoveryCodes.looksLikeCode("ABCDE-FGH23")).isTrue();
        assertThat(RecoveryCodes.looksLikeCode("123456")).isFalse();
        assertThat(RecoveryCodes.looksLikeCode("ABCDE-FGH21")).isFalse();
    }
}
