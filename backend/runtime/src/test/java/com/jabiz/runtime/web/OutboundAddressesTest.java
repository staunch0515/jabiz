package com.jabiz.runtime.web;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OutboundAddressesTest {

    private static final List<String> HOSTS = List.of("api.example.com", "localhost", "[::1]");

    private static String refusal(String url) {
        return OutboundAddresses.refusal(url == null ? null : URI.create(url), HOSTS, "the list");
    }

    @Test
    void httpsToAnAllowedHostAndHttpToThisMachinePass() {
        assertThat(refusal("https://API.example.com/v1?key=x")).isNull();
        assertThat(refusal("http://localhost:8091/v1")).isNull();
        assertThat(refusal("http://[::1]:8091/v1")).isNull();
    }

    @Test
    void everythingElseIsRefusedWithoutRepeatingThePath() {
        assertThat(refusal(null)).isEqualTo("missing or not an absolute address");
        assertThat(refusal("/v1/relative")).isEqualTo("missing or not an absolute address");
        assertThat(refusal("http://api.example.com/v1?token=secret"))
            .isEqualTo("not https (http only to this machine): http://api.example.com");
        assertThat(refusal("ftp://api.example.com/x")).startsWith("not https");
        assertThat(refusal("https://user:secret@api.example.com/")).isEqualTo("carries credentials");
        assertThat(refusal("https://evil.example.com/")).isEqualTo("host evil.example.com is not in the list");
        assertThat(refusal("https://api.example.com.evil.example/")).startsWith("host api.example.com.evil.example");
    }

    @Test
    void originsHaveNoPath() {
        assertThat(OutboundAddresses.origin(URI.create("https://h.example.com:8443/a?b=c"))).isEqualTo(
            "https://h.example.com:8443");
        assertThat(OutboundAddresses.origin(URI.create("/a"))).isEqualTo("(not an absolute address)");
        assertThat(OutboundAddresses.origin(null)).isEqualTo("null");
    }
}
