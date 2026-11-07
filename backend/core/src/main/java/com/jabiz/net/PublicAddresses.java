package com.jabiz.net;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.List;

/**
 * Which addresses on the internet the server may connect to on behalf of a user (decision D75): the addresses of
 * public hosts, never those of this machine, of the networks it sits in, or of anything the address registries reserve.
 * The server downloads images from links people give it; a link whose host resolves to {@code 10.0.0.5},
 * {@code 169.254.169.254} (the cloud metadata service) or {@code ::1} must not be fetched.
 *
 * <p>IPv4: everything except the special-purpose blocks of the IANA registry (RFC 6890 and successors), also the
 * globally reachable ones among them (they are not where images live). IPv6: only global unicast {@code 2000::/3},
 * less its special-purpose blocks; and the well-known NAT64 prefix {@code 64:ff9b::/96} when the IPv4 address it
 * carries is itself public. IPv4 addresses written in IPv6 forms (mapped, compatible, 6to4, NAT64, Teredo) are judged
 * by the IPv4 address they carry, or refused where that address is hidden (Teredo). Pure computation on the address's
 * bytes: nothing is resolved.
 */
public final class PublicAddresses {

    /** An IPv4 block: the address as an unsigned int and the prefix length. */
    private record V4(int network, int prefix) {

        static V4 of(int a, int b, int c, int d, int prefix) {
            return new V4((a << 24) | (b << 16) | (c << 8) | d, prefix);
        }

        boolean contains(int address) {
            int mask = prefix == 0 ? 0 : -1 << (32 - prefix);
            return (address & mask) == (network & mask);
        }
    }

    /** An IPv6 block: the first 64 bits, the next 64 bits and the prefix length. */
    private record V6(long high, long low, int prefix) {

        boolean contains(long addressHigh, long addressLow) {
            if (prefix <= 64) {
                long mask = prefix == 0 ? 0 : -1L << (64 - prefix);
                return (addressHigh & mask) == (high & mask);
            }
            long mask = -1L << (128 - prefix);
            return addressHigh == high && (addressLow & mask) == (low & mask);
        }
    }

    /** IPv4 blocks that are not public (IANA IPv4 Special-Purpose Address Registry, multicast and reserved). */
    private static final List<V4> NOT_PUBLIC_V4 = List.of(
        V4.of(0, 0, 0, 0, 8),          // "this network", 0.0.0.0
        V4.of(10, 0, 0, 0, 8),         // private
        V4.of(100, 64, 0, 0, 10),      // shared address space (carrier-grade NAT)
        V4.of(127, 0, 0, 0, 8),        // loopback
        V4.of(169, 254, 0, 0, 16),     // link-local, the cloud metadata services among them
        V4.of(172, 16, 0, 0, 12),      // private
        V4.of(192, 0, 0, 0, 24),       // IETF protocol assignments
        V4.of(192, 0, 2, 0, 24),       // documentation (TEST-NET-1)
        V4.of(192, 31, 196, 0, 24),    // AS112
        V4.of(192, 52, 193, 0, 24),    // AMT
        V4.of(192, 88, 99, 0, 24),     // deprecated 6to4 relay anycast
        V4.of(192, 168, 0, 0, 16),     // private
        V4.of(198, 18, 0, 0, 15),      // benchmarking
        V4.of(198, 51, 100, 0, 24),    // documentation (TEST-NET-2)
        V4.of(203, 0, 113, 0, 24),     // documentation (TEST-NET-3)
        V4.of(224, 0, 0, 0, 4),        // multicast
        V4.of(240, 0, 0, 0, 4));       // reserved, 255.255.255.255 (limited broadcast) included

    /** Blocks within {@code 2000::/3} that are not public. */
    private static final List<V6> NOT_PUBLIC_V6 = List.of(
        new V6(0x2001_0000_0000_0000L, 0, 23),   // IETF protocol assignments: Teredo, benchmarking, ORCHID ...
        new V6(0x2001_0db8_0000_0000L, 0, 32),   // documentation
        new V6(0x3fff_0000_0000_0000L, 0, 20));  // documentation (RFC 9637)

    private static final V6 GLOBAL_UNICAST = new V6(0x2000_0000_0000_0000L, 0, 3);
    /** The well-known NAT64 prefix (RFC 6052): the last 32 bits are an IPv4 address. */
    private static final V6 NAT64 = new V6(0x0064_ff9b_0000_0000L, 0, 96);
    /** 6to4 (RFC 3056): bits 16 to 47 are an IPv4 address. */
    private static final V6 SIX_TO_FOUR = new V6(0x2002_0000_0000_0000L, 0, 16);
    /** IPv4-mapped {@code ::ffff:0:0/96} and IPv4-compatible {@code ::/96}. */
    private static final V6 MAPPED = new V6(0, 0x0000_ffff_0000_0000L, 96);
    private static final V6 COMPATIBLE = new V6(0, 0, 96);

    private PublicAddresses() {}

    /** Whether {@code address} belongs to a public host on the internet; false for null. */
    public static boolean isPublic(InetAddress address) {
        if (address instanceof Inet4Address v4) {
            return isPublicV4(toInt(v4.getAddress(), 0));
        }
        if (address instanceof Inet6Address v6) {
            // A scoped address (fe80::1%eth0) is link-local anyway; the scope is not looked at.
            return isPublicV6(v6.getAddress());
        }
        return false;
    }

    private static boolean isPublicV4(int address) {
        for (V4 block : NOT_PUBLIC_V4) {
            if (block.contains(address)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isPublicV6(byte[] bytes) {
        long high = toLong(bytes, 0);
        long low = toLong(bytes, 8);
        if (MAPPED.contains(high, low)) {
            return isPublicV4((int) low);
        }
        if (COMPATIBLE.contains(high, low)) {
            // ::, ::1 and the deprecated IPv4-compatible form: never a public host's address.
            return false;
        }
        if (NAT64.contains(high, low)) {
            return isPublicV4((int) low);
        }
        if (!GLOBAL_UNICAST.contains(high, low)) {
            // Link-local, unique local (fc00::/7, fd00:ec2::254 included), site-local, multicast, discard-only,
            // local-use NAT64 and everything else not allocated for global unicast.
            return false;
        }
        for (V6 block : NOT_PUBLIC_V6) {
            if (block.contains(high, low)) {
                return false;
            }
        }
        if (SIX_TO_FOUR.contains(high, low)) {
            return isPublicV4((int) (high >>> 16));
        }
        return true;
    }

    private static int toInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 24) | ((bytes[offset + 1] & 0xff) << 16) | ((bytes[offset + 2] & 0xff) << 8)
            | (bytes[offset + 3] & 0xff);
    }

    private static long toLong(byte[] bytes, int offset) {
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (bytes[offset + i] & 0xff);
        }
        return value;
    }
}
