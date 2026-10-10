package com.jabiz.runtime.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

import java.net.InetSocketAddress;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Client addresses behind trusted proxies only (docs/design/10-security.md section 15; decision D36 item 7). */
class ClientAddressesTest {

    private static MockServerHttpRequest request(String connection, String... forwardedFor) {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.get("/api/auth/login")
            .remoteAddress(new InetSocketAddress(connection, 40000));
        for (String header : forwardedFor) {
            builder.header(ClientAddresses.FORWARDED_FOR, header);
        }
        return builder.build();
    }

    private final ClientAddresses proxied = new ClientAddresses(List.of("10.0.0.0/8", "192.168.1.5", "fd00::/8"),
        null);

    @Test
    void withoutTrustedProxiesTheConnectionIsTheClient() {
        ClientAddresses none = new ClientAddresses(List.of(), null);
        assertThat(none.clientOf(request("203.0.113.9", "198.51.100.1"))).isEqualTo("203.0.113.9");
        assertThat(none.trustsProxies()).isFalse();
        assertThat(none.check()).isEmpty();
    }

    @Test
    void anUntrustedConnectionCannotForgeTheHeader() {
        assertThat(proxied.clientOf(request("203.0.113.9", "198.51.100.1"))).isEqualTo("203.0.113.9");
    }

    @Test
    void aTrustedChainYieldsTheRightmostUntrustedAddress() {
        // client -> attacker-chosen value -> real client -> proxy 10.1.2.3 -> proxy 192.168.1.5 (connection)
        assertThat(proxied.clientOf(request("192.168.1.5", "1.1.1.1, 198.51.100.7, 10.1.2.3")))
            .isEqualTo("198.51.100.7");
        // Several headers count as one list, in order.
        assertThat(proxied.clientOf(request("192.168.1.5", "1.1.1.1, 198.51.100.7", "10.1.2.3")))
            .isEqualTo("198.51.100.7");
    }

    @Test
    void anAllTrustedChainYieldsTheLeftmost() {
        assertThat(proxied.clientOf(request("10.0.0.1", "10.9.9.9, 10.0.0.2"))).isEqualTo("10.9.9.9");
    }

    @Test
    void malformedOrMissingHeadersAreIgnored() {
        assertThat(proxied.clientOf(request("10.0.0.1"))).isEqualTo("10.0.0.1");
        assertThat(proxied.clientOf(request("10.0.0.1", "198.51.100.7, evil.example"))).isEqualTo("10.0.0.1");
        assertThat(proxied.clientOf(request("10.0.0.1", "198.51.100.7,"))).isEqualTo("10.0.0.1");
        assertThat(proxied.clientOf(request("10.0.0.1", "256.1.1.1"))).isEqualTo("10.0.0.1");
        assertThat(proxied.clientOf(request("10.0.0.1", "198.51.100.7:443"))).isEqualTo("10.0.0.1");
        assertThat(proxied.clientOf(request("10.0.0.1", "1.2.3.4, ".repeat(30) + "1.2.3.4"))).isEqualTo("10.0.0.1");
    }

    @Test
    void ipv6AddressesAndBlocks() {
        assertThat(proxied.clientOf(request("fd12::1", "2001:db8::7, fd00::2"))).isEqualTo("2001:db8:0:0:0:0:0:7");
        assertThat(proxied.clientOf(request("fd12::1", "[2001:db8::8]"))).isEqualTo("2001:db8:0:0:0:0:0:8");
        assertThat(proxied.clientOf(request("2001:db8::1", "198.51.100.7"))).isEqualTo("2001:db8:0:0:0:0:0:1");
    }

    @Test
    void cidrBoundaries() {
        ClientAddresses narrow = new ClientAddresses(List.of("198.51.100.0/25"), null);
        assertThat(narrow.clientOf(request("198.51.100.127", "203.0.113.1"))).isEqualTo("203.0.113.1");
        assertThat(narrow.clientOf(request("198.51.100.128", "203.0.113.1"))).isEqualTo("198.51.100.128");
        ClientAddresses odd = new ClientAddresses(List.of("198.51.100.64/27"), null);
        assertThat(odd.clientOf(request("198.51.100.95", "203.0.113.1"))).isEqualTo("203.0.113.1");
        assertThat(odd.clientOf(request("198.51.100.96", "203.0.113.1"))).isEqualTo("198.51.100.96");
        ClientAddresses all = new ClientAddresses(List.of("0.0.0.0/0"), null);
        assertThat(all.clientOf(request("198.51.100.96", "10.0.0.1, 203.0.113.1"))).isEqualTo("10.0.0.1");
    }

    @Test
    void configurationProblemsAreReported() {
        ClientAddresses bad = new ClientAddresses(List.of("10.0.0.0/33", "proxy.example", "10.0.0.0/x", " "), null);
        assertThat(bad.check()).hasSize(4).allSatisfy(p -> assertThat(p.isError()).isTrue());

        // Spring's forwarded-header handling and trusted proxies together: the headers would count twice.
        assertThat(new ClientAddresses(List.of("10.0.0.1"), "native").check()).singleElement()
            .satisfies(p -> assertThat(p.message()).contains("server.forward-headers-strategy"));
        assertThat(new ClientAddresses(List.of("10.0.0.1"), "none").check()).isEmpty();
        assertThat(new ClientAddresses(List.of(), "framework").check()).isEmpty();
        assertThatThrownBy(() -> ClientAddresses.block("::1/129")).isInstanceOf(IllegalArgumentException.class);
        assertThat(ClientAddresses.literal("not an address")).isNull();
        assertThat(ClientAddresses.literal("fe80::1%eth0")).isNull();
    }
}
