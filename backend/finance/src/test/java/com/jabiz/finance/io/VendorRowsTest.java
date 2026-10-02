package com.jabiz.finance.io;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** How the sample company's vendor file reads (FIN-AP-001, FIN-DI-001). */
class VendorRowsTest {

    @Test
    void theSamplesEntityTypesRead() {
        assertThat(VendorRows.entityType("C corporation")).isEqualTo("C_CORPORATION");
        assertThat(VendorRows.entityType("single-member LLC")).isEqualTo("SINGLE_MEMBER_LLC");
        assertThat(VendorRows.entityType("partnership")).isEqualTo("PARTNERSHIP");
        assertThat(VendorRows.entityType("municipal utility")).isEqualTo("GOVERNMENT");
        assertThat(VendorRows.entityType("individual")).isEqualTo("INDIVIDUAL");
        assertThat(VendorRows.entityType("S corporation")).isEqualTo("S_CORPORATION");
        assertThat(VendorRows.entityType("TAX_EXEMPT")).isEqualTo("TAX_EXEMPT");
        assertThat(VendorRows.entityType(" ")).isNull();
        assertThatThrownBy(() -> VendorRows.entityType("cooperative")).hasMessageContaining("cooperative");
    }

    @Test
    void theSamples1099SettingsRead() {
        assertThat(VendorRows.form1099("1099-NEC box 1")).isEqualTo(new VendorRows.Form1099("NEC", "1"));
        assertThat(VendorRows.form1099("1099-MISC box 1 (rents)")).isEqualTo(new VendorRows.Form1099("MISC", "1"));
        assertThat(VendorRows.form1099("1099-MISC, box 10")).isEqualTo(new VendorRows.Form1099("MISC", "10"));
        assertThat(VendorRows.form1099("1099-nec")).isEqualTo(new VendorRows.Form1099("NEC", "1"));
        assertThat(VendorRows.form1099("")).isEqualTo(new VendorRows.Form1099(null, null));
        assertThat(VendorRows.form1099("none")).isEqualTo(new VendorRows.Form1099(null, null));
        assertThatThrownBy(() -> VendorRows.form1099("1099-K")).hasMessageContaining("1099-K");
    }

    @Test
    void yesAndNo() {
        assertThat(VendorRows.yes("yes")).isTrue();
        assertThat(VendorRows.yes("Y")).isTrue();
        assertThat(VendorRows.yes("no")).isFalse();
        assertThat(VendorRows.yes(null)).isFalse();
        assertThatThrownBy(() -> VendorRows.yes("maybe")).hasMessageContaining("maybe");
    }
}
