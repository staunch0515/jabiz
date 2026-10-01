package com.jabiz.finance.io;

import com.jabiz.finance.ar.CustomerProcesses;
import com.jabiz.finance.tax.TaxProcesses;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** How the sample company's customer and tax code files read (FIN-DI-001; ROADMAP F3a). */
class ReceivableRowsTest {

    private static final LocalDate FROM = LocalDate.of(2025, 1, 1);

    @Test
    void locationsReadAsStateAndCityOrAsTheCountry() {
        assertThat(CustomerRows.location("TX (Austin)")).isEqualTo(new CustomerRows.Location("Austin", "TX", "US"));
        assertThat(CustomerRows.location("Portland, OR")).isEqualTo(new CustomerRows.Location("Portland", "OR", "US"));
        assertThat(CustomerRows.location("Germany")).isEqualTo(new CustomerRows.Location(null, null, "Germany"));
        assertThat(CustomerRows.location(" ")).isEqualTo(new CustomerRows.Location(null, null, null));
        // Only US states read as states.
        assertThat(CustomerRows.location("Toronto, ON")).isEqualTo(new CustomerRows.Location(null, null,
            "Toronto, ON"));
        assertThat(CustomerRows.location("tx (Dallas)")).isEqualTo(new CustomerRows.Location("Dallas", "TX", "US"));
    }

    @Test
    void theSamplesCertificateReadsAsAResaleCertificateForTexas() {
        CustomerProcesses.CertificateInput certificate =
            CustomerRows.certificate("TX 01-339 resale certificate RC-3301, valid to 2027-12-31");
        assertThat(certificate.state()).isEqualTo("TX");
        assertThat(certificate.certificateNo()).isEqualTo("RC-3301");
        assertThat(certificate.certificateType()).isEqualTo("RESALE");
        assertThat(certificate.expiryDate()).isEqualTo(LocalDate.of(2027, 12, 31));
        assertThat(certificate.issueDate()).isNull();
        assertThat(CustomerRows.certificate("")).isNull();
        assertThat(CustomerRows.certificate("CA exempt organization certificate EO-7, issued 2025-05-01").issueDate())
            .isEqualTo(LocalDate.of(2025, 5, 1));
        assertThat(CustomerRows.certificate("TX resale certificate no. RC-1, valid to 2027-12-31").certificateNo())
            .isEqualTo("RC-1");
        assertThatThrownBy(() -> CustomerRows.certificate("ON resale certificate RC-1"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CustomerRows.certificate("resale, valid to 2027-12-31"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static TaxProcesses.TaxCodeInput sample(String code, String description, String rate, String note) {
        return TaxCodeRows.read(new TaxCodeRows.Row(code, description, new BigDecimal(rate), note, null, null, null,
            null, null, null), FROM);
    }

    @Test
    void theAustinRateSplitsIntoTheStateAndTheLocalJurisdiction() {
        TaxProcesses.TaxCodeInput austin = sample("TX-AUSTIN", "Texas, City of Austin combined rate", "8.25",
            "state 6.25% + local 2.00%");
        assertThat(austin.kind()).isEqualTo("TAXABLE");
        assertThat(austin.state()).isEqualTo("TX");
        assertThat(austin.ratesFrom()).isEqualTo(FROM);
        assertThat(austin.jurisdictions()).extracting(p -> p.jurisdictionCode() + " " + p.level() + " "
            + p.ratePercent()).containsExactly("TX STATE 6.25", "TX-AUSTIN-LOCAL CITY 2.00");
        assertThatThrownBy(() -> sample("TX-AUSTIN", "Austin", "8.00", "state 6.25% + local 2.00%"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("add up to 8.25");
    }

    @Test
    void zeroRatesReadAsCertificateExemptionsOrNonTaxableSales() {
        TaxProcesses.TaxCodeInput resale = sample("TX-RESALE", "Texas sale for resale (exempt with certificate)",
            "0.00", "exemption certificate required");
        assertThat(resale).extracting(TaxProcesses.TaxCodeInput::kind, TaxProcesses.TaxCodeInput::reason,
            TaxProcesses.TaxCodeInput::state, TaxProcesses.TaxCodeInput::certificateRequired)
            .containsExactly("EXEMPT", "RESALE", "TX", true);
        assertThat(resale.jurisdictions()).isEmpty();
        assertThat(resale.ratesFrom()).isNull();
        assertThat(sample("OR-NONE", "Oregon (no general sales tax)", "0.00", "no sales tax"))
            .extracting(TaxProcesses.TaxCodeInput::kind, TaxProcesses.TaxCodeInput::reason,
                TaxProcesses.TaxCodeInput::state).containsExactly("NON_TAXABLE", "NO_SALES_TAX", "OR");
        assertThat(sample("EXPORT", "Export outside the United States", "0.00", "not subject to US sales tax"))
            .extracting(TaxProcesses.TaxCodeInput::reason, TaxProcesses.TaxCodeInput::state)
            .containsExactly("EXPORT", null);
        assertThat(sample("NT", "Non-taxable service line", "0.00", "line-level override").reason())
            .isEqualTo("NON_TAXABLE_SERVICE");
    }

    @Test
    void explicitColumnsWin() {
        TaxProcesses.TaxCodeInput code = TaxCodeRows.read(new TaxCodeRows.Row("TX-DALLAS", "Dallas", null, null,
            "taxable", null, "tx", "TX:6.25;TX-DALLAS-LOCAL:2.00", null, null), FROM);
        assertThat(code.state()).isEqualTo("TX");
        assertThat(code.jurisdictions()).extracting(TaxProcesses.JurisdictionPart::jurisdictionCode)
            .containsExactly("TX", "TX-DALLAS-LOCAL");
        assertThatThrownBy(() -> TaxCodeRows.read(new TaxCodeRows.Row("X", "X", new BigDecimal("1"), null, null, null,
            null, "TX=1", null, null), FROM)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sample("CITY", "A city", "1.00", null)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("names no state");
    }
}
