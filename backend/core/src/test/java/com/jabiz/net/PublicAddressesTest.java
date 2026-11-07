package com.jabiz.net;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;

class PublicAddressesTest {

    /** Literals only: {@code getByName} of a literal never asks a resolver. */
    private static InetAddress literal(String text) {
        try {
            return InetAddress.getByName(text);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(text, e);
        }
    }

    /** Sixteen bytes as given, without the JDK turning a mapped address into an IPv4 one. */
    private static InetAddress v6(int... words) {
        byte[] bytes = new byte[16];
        for (int i = 0; i < 8; i++) {
            bytes[2 * i] = (byte) (words[i] >>> 8);
            bytes[2 * i + 1] = (byte) words[i];
        }
        try {
            return java.net.Inet6Address.getByAddress(null, bytes, -1);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "0.0.0.0", "0.255.255.255", "10.0.0.1", "10.255.255.255", "100.64.0.1", "100.127.255.255", "127.0.0.1",
        "127.255.255.254", "169.254.169.254", "169.254.0.1", "172.16.0.1", "172.31.255.255", "192.0.0.1",
        "192.0.0.170", "192.0.2.1", "192.31.196.1", "192.52.193.1", "192.88.99.1", "192.168.0.1", "192.168.255.255",
        "198.18.0.1", "198.19.255.255", "198.51.100.7", "203.0.113.9", "224.0.0.1", "239.255.255.250",
        "240.0.0.1", "255.255.255.255"})
    void reservedIpv4AddressesAreNotPublic(String address) {
        assertThat(PublicAddresses.isPublic(literal(address))).as(address).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "1.1.1.1", "8.8.8.8", "9.255.255.255", "11.0.0.0", "100.63.255.255", "100.128.0.0", "126.255.255.255",
        "128.0.0.0", "169.253.255.255", "169.255.0.0", "172.15.255.255", "172.32.0.0", "192.0.1.1", "192.0.3.0",
        "192.167.255.255", "192.169.0.0", "198.17.255.255", "198.20.0.0", "198.51.99.255", "198.51.101.0",
        "203.0.112.255", "203.0.114.0", "223.255.255.255", "93.184.216.34"})
    void otherIpv4AddressesArePublic(String address) {
        assertThat(PublicAddresses.isPublic(literal(address))).as(address).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "::", "::1", "fe80::1", "febf:ffff::1", "fec0::1", "fc00::1", "fd00::1", "fd00:ec2::254", "ff02::1",
        "ff0e::1", "100::1", "64:ff9b:1::1", "2001::1", "2001:0:4136:e378:8000:63bf:3fff:fdd2", "2001:2::1",
        "2001:10::1", "2001:20::1", "2001:1ff::1", "2001:db8::1", "2001:db8:ffff::1", "3fff::1", "3fff:fff::1",
        "5f00::1", "4000::1", "1000::1", "::7f00:1", "::a00:1", "::808:808", "::ffff:0:7f00:1",
        "64:ff9b::7f00:1", "64:ff9b::a9fe:a9fe", "64:ff9b::c0a8:1", "2002:7f00:1::1", "2002:a00:1::1",
        "2002:c0a8:101::1", "2002:a9fe:a9fe::1"})
    void reservedIpv6AddressesAndPrivateOnesTheyCarryAreNotPublic(String address) {
        assertThat(PublicAddresses.isPublic(literal(address))).as(address).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "2606:4700:4700::1111", "2001:4860:4860::8888", "2001:200::1", "2400:cb00::1", "2a00:1450::1",
        "3ffe::1", "3fff:1000::1", "2001:dba::1", "64:ff9b::808:808", "2002:808:808::1", "2002:5db8:d822::1"})
    void globalUnicastIpv6AndPublicOnesCarriedArePublic(String address) {
        assertThat(PublicAddresses.isPublic(literal(address))).as(address).isTrue();
    }

    @Test
    void ipv4MappedAddressesAreJudgedByTheIpv4AddressTheyCarry() {
        // ::ffff:127.0.0.1 and ::ffff:169.254.169.254 kept as IPv6 (a JDK literal would become IPv4).
        assertThat(PublicAddresses.isPublic(v6(0, 0, 0, 0, 0, 0xffff, 0x7f00, 0x0001))).isFalse();
        assertThat(PublicAddresses.isPublic(v6(0, 0, 0, 0, 0, 0xffff, 0xa9fe, 0xa9fe))).isFalse();
        assertThat(PublicAddresses.isPublic(v6(0, 0, 0, 0, 0, 0xffff, 0x0a00, 0x0001))).isFalse();
        assertThat(PublicAddresses.isPublic(v6(0, 0, 0, 0, 0, 0xffff, 0x0808, 0x0808))).isTrue();
        // As the JDK parses them.
        assertThat(PublicAddresses.isPublic(literal("::ffff:127.0.0.1"))).isFalse();
        assertThat(PublicAddresses.isPublic(literal("::ffff:192.168.1.1"))).isFalse();
        assertThat(PublicAddresses.isPublic(literal("::ffff:8.8.8.8"))).isTrue();
        // IPv4-compatible (deprecated), even of a public address.
        assertThat(PublicAddresses.isPublic(v6(0, 0, 0, 0, 0, 0, 0x0808, 0x0808))).isFalse();
    }

    @Test
    void scopedLinkLocalAddressesAreNotPublicAndNullIsNot() throws UnknownHostException {
        assertThat(PublicAddresses.isPublic(java.net.Inet6Address.getByAddress(null,
            literal("fe80::1").getAddress(), 3))).isFalse();
        assertThat(PublicAddresses.isPublic(null)).isFalse();
    }
}
