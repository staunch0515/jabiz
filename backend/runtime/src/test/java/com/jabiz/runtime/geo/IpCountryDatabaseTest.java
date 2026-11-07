package com.jabiz.runtime.geo;

import com.jabiz.context.ClientCountry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The local address database (decision D74): ranges of IPv4 and IPv6, binary search, nothing resolved. */
class IpCountryDatabaseTest {

    static final String CSV = """
        # comment
        "192.0.2.0","192.0.2.255","CN"
        198.51.100.0,198.51.100.255,JP
        203.0.113.0,203.0.113.127,HK
        203.0.113.200,203.0.113.200,ZZ

        2001:db8::,2001:db8:0:ffff:ffff:ffff:ffff:ffff,CN
        2001:db8:1::,2001:db8:1:ffff:ffff:ffff:ffff:ffff,US
        1.0.0.0,1.0.0.255,AU
        """;

    static IpCountryDatabase database(String csv) throws IOException {
        return IpCountryDatabase.read(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), "test");
    }

    @Test
    void findsIpv4RangesTheirEdgesAndNothingBetween() throws IOException {
        IpCountryDatabase db = database(CSV);
        assertThat(db.size()).isEqualTo(6);
        assertThat(db.countryOf("192.0.2.0").code()).isEqualTo("CN");
        assertThat(db.countryOf("192.0.2.255").code()).isEqualTo("CN");
        assertThat(db.countryOf("198.51.100.7").code()).isEqualTo("JP");
        assertThat(db.countryOf("1.0.0.1").code()).isEqualTo("AU");
        assertThat(db.countryOf("203.0.113.127").code()).isEqualTo("HK");
        assertThat(db.countryOf("203.0.113.128")).isEqualTo(ClientCountry.UNKNOWN);
        // The placeholder ZZ is no country.
        assertThat(db.countryOf("203.0.113.200")).isEqualTo(ClientCountry.UNKNOWN);
        assertThat(db.countryOf("0.0.0.1")).isEqualTo(ClientCountry.UNKNOWN);
        assertThat(db.countryOf("255.255.255.255")).isEqualTo(ClientCountry.UNKNOWN);
    }

    @Test
    void findsIpv6RangesAndMappedIpv4() throws IOException {
        IpCountryDatabase db = database(CSV);
        assertThat(db.countryOf("2001:db8::1").code()).isEqualTo("CN");
        assertThat(db.countryOf("2001:db8:0:ffff:ffff:ffff:ffff:ffff").code()).isEqualTo("CN");
        assertThat(db.countryOf("2001:db8:1::42").code()).isEqualTo("US");
        assertThat(db.countryOf("[2001:db8:1::42]").code()).isEqualTo("US");
        assertThat(db.countryOf("2001:db8:2::1")).isEqualTo(ClientCountry.UNKNOWN);
        assertThat(db.countryOf("::ffff:192.0.2.9").code()).isEqualTo("CN");
        assertThat(db.countryOf("fe80::1%eth0")).isEqualTo(ClientCountry.UNKNOWN);
    }

    @Test
    void unknownForPrivateAddressesNamesAndNothing() throws IOException {
        IpCountryDatabase db = database(CSV);
        for (String address : new String[] {"10.0.0.1", "127.0.0.1", "::1", null, "", "localhost", "example.com",
            "deadbeef", "1.2.3", "unknown"}) {
            assertThat(db.countryOf(address)).as(String.valueOf(address)).isEqualTo(ClientCountry.UNKNOWN);
        }
        assertThat(IpCountryDatabase.EMPTY.countryOf("192.0.2.1")).isEqualTo(ClientCountry.UNKNOWN);
    }

    @Test
    void readsAGzippedFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("countries.csv.gz");
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(file))) {
            out.write(CSV.getBytes(StandardCharsets.UTF_8));
        }
        assertThat(IpCountryDatabase.read(file).countryOf("198.51.100.1").code()).isEqualTo("JP");
    }

    @Test
    void refusesAFileItCannotReadWhole() {
        assertThatThrownBy(() -> database("192.0.2.0,192.0.2.9\n")).isInstanceOf(IOException.class)
            .hasMessageContaining("line 1");
        assertThatThrownBy(() -> database("192.0.2.0,2001:db8::,CN\n")).hasMessageContaining("mixes");
        assertThatThrownBy(() -> database("192.0.2.9,192.0.2.0,CN\n")).hasMessageContaining("ends before");
        assertThatThrownBy(() -> database("example.com,192.0.2.0,CN\n")).hasMessageContaining("not an address");
        assertThatThrownBy(() -> database("192.0.2.0,192.0.2.9,CHN\n")).hasMessageContaining("two letters");
        assertThatThrownBy(() -> database("192.0.2.0,192.0.2.9,CN\n192.0.2.5,192.0.2.20,JP\n"))
            .hasMessageContaining("overlap");
    }
}
