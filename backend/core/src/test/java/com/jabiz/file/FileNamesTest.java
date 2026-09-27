package com.jabiz.file;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FileNamesTest {

    @Test
    void keepsOrdinaryNames() {
        assertThat(FileNames.sanitize("photo (1).jpg")).isEqualTo("photo (1).jpg");
        assertThat(FileNames.sanitize("写真_2026-01.png")).isEqualTo("写真_2026-01.png");
    }

    @Test
    void dropsPathsAndReplacesOtherCharacters() {
        assertThat(FileNames.sanitize("../../etc/passwd")).isEqualTo("passwd");
        assertThat(FileNames.sanitize("C:\\Users\\me\\cv.pdf")).isEqualTo("cv.pdf");
        assertThat(FileNames.sanitize("a<b>\"c\";\u0000\n.pdf")).isEqualTo("a_b__c____.pdf");
        assertThat(FileNames.sanitize(".htaccess")).isEqualTo("_htaccess");
        assertThat(FileNames.sanitize("  spaced  ")).isEqualTo("spaced");
    }

    @Test
    void fallsBackAndShortens() {
        assertThat(FileNames.sanitize(null)).isEqualTo("file");
        assertThat(FileNames.sanitize("")).isEqualTo("file");
        assertThat(FileNames.sanitize("dir/")).isEqualTo("file");
        String longName = "名".repeat(300) + ".pdf";
        String cleaned = FileNames.sanitize(longName);
        assertThat(cleaned.codePointCount(0, cleaned.length())).isEqualTo(FileNames.MAX_LENGTH);
        String emoji = "\uD83D\uDE00".repeat(300);
        String cleanedEmoji = FileNames.sanitize(emoji);
        assertThat(cleanedEmoji).matches("_+");
    }
}
