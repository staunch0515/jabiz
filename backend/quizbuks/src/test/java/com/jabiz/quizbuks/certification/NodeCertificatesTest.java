package com.jabiz.quizbuks.certification;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The verify keys of a node's certificates and the name a document shows (F-S5a). */
class NodeCertificatesTest {

    @Test
    void aKeyIs32HexDigitsAndSaysNothingOfItsLitRecord() {
        byte[] seed = new byte[32];
        seed[0] = 7;
        String lit = UUID.randomUUID().toString();
        String key = NodeCertificates.verifyKey(seed, lit);
        assertThat(key).matches("[0-9a-f]{32}").doesNotContain(lit.replace("-", "")).isNotEqualTo(lit);
        assertThat(NodeCertificates.verifyKey(seed, lit)).isEqualTo(key);
    }

    @Test
    void theKeysOfOneRunDifferAndTheSeedOfAnotherRunChangesThem() {
        byte[] seed = new byte[32];
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            keys.add(NodeCertificates.verifyKey(seed, UUID.randomUUID().toString()));
        }
        assertThat(keys).hasSize(500);
        String lit = UUID.randomUUID().toString();
        byte[] other = new byte[32];
        other[31] = 1;
        assertThat(NodeCertificates.verifyKey(other, lit)).isNotEqualTo(NodeCertificates.verifyKey(seed, lit));
    }

    @Test
    void aDocumentShowsTheNameInItsLanguageThenEnglishThenAnyOther() {
        Map<String, String> names = new LinkedHashMap<>();
        names.put("zh", "诚信交易");
        names.put("en", "Fair dealing");
        assertThat(CertificateProcesses.nameIn(names, "zh")).isEqualTo("诚信交易");
        assertThat(CertificateProcesses.nameIn(names, "ja")).isEqualTo("Fair dealing");
        assertThat(CertificateProcesses.nameIn(Map.of("ja", "公正な取引"), "en")).isEqualTo("公正な取引");
        assertThat(CertificateProcesses.nameIn(Map.of("en", " "), "en")).isEmpty();
        assertThat(CertificateProcesses.nameIn(null, "en")).isEmpty();
    }

    @Test
    void theVerifyAddressIsTheSitesVerifyPageOfTheKey() {
        assertThat(CertificateProcesses.verifyAddress("https://quizbuks.example/", "abc"))
            .isEqualTo("https://quizbuks.example/taker/verify/abc");
        assertThat(CertificateProcesses.verifyAddress("", "abc")).isEqualTo("/taker/verify/abc");
    }
}
