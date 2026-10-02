package com.jabiz.finance.calc;

import com.jabiz.entity.MaskStyle;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Taxpayer identification numbers in written form (FIN-AP-002). */
class TaxIdsTest {

    @Test
    void numbersAreKeptInTheFormPeopleWriteThem() {
        assertThat(TaxIds.normalize("SSN", "123456789")).contains("123-45-6789");
        assertThat(TaxIds.normalize("SSN", "123-45-6789")).contains("123-45-6789");
        assertThat(TaxIds.normalize("SSN", "123 45 6789")).contains("123-45-6789");
        assertThat(TaxIds.normalize("EIN", "12-3456789")).contains("12-3456789");
        assertThat(TaxIds.normalize("EIN", "123456789")).contains("12-3456789");
        assertThat(TaxIds.normalize("ITIN", "912-70-1234")).contains("912-70-1234");
    }

    @Test
    void numbersThatCannotBeOfTheTypeAreRefused() {
        assertThat(TaxIds.normalize("SSN", "12345678")).isEmpty();
        assertThat(TaxIds.normalize("SSN", "1234567890")).isEmpty();
        assertThat(TaxIds.normalize("SSN", "12345678A")).isEmpty();
        assertThat(TaxIds.normalize("SSN", "000-12-3456")).isEmpty();
        assertThat(TaxIds.normalize("SSN", "666-12-3456")).isEmpty();
        assertThat(TaxIds.normalize("SSN", "123-00-4567")).isEmpty();
        assertThat(TaxIds.normalize("SSN", "123-45-0000")).isEmpty();
        // An SSN never starts with 9: that is an ITIN.
        assertThat(TaxIds.normalize("SSN", "912-70-1234")).isEmpty();
        assertThat(TaxIds.normalize("ITIN", "123-45-6789")).isEmpty();
        assertThat(TaxIds.normalize("EIN", "00-1234567")).isEmpty();
        assertThat(TaxIds.normalize("VAT", "123456789")).isEmpty();
        assertThat(TaxIds.normalize(null, "123456789")).isEmpty();
        assertThat(TaxIds.normalize("SSN", null)).isEmpty();
        // A masked number is no number.
        assertThat(TaxIds.normalize("SSN", "***-**-6789")).isEmpty();
    }

    @Property
    void theWrittenFormIsWhatTheTaxIdMaskShowsTheLastFourOf(@ForAll @IntRange(min = 100_000_000,
        max = 899_999_999) int digits) {
        String value = String.valueOf(digits);
        TaxIds.normalize("EIN", value).ifPresent(ein -> {
            assertThat(MaskStyle.TAX_ID.apply(ein)).isEqualTo("**-***" + value.substring(5));
            assertThat(TaxIds.normalize("EIN", ein)).contains(ein);
        });
        TaxIds.normalize("SSN", value).ifPresent(ssn -> {
            assertThat(MaskStyle.TAX_ID.apply(ssn)).isEqualTo("***-**-" + value.substring(5));
            assertThat(TaxIds.normalize("SSN", ssn)).contains(ssn);
        });
    }
}
