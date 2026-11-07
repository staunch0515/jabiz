package com.jabiz.runtime.geo;

import com.jabiz.context.ClientCountry;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.GZIPInputStream;

/**
 * A local database of address ranges and their countries (decision D74), read in full into memory and searched by
 * binary search: nothing is asked of any outside service, and no address leaves the machine.
 *
 * <p>The file is CSV, one range per line, {@code first,last,country} (the layout of DB-IP's "IP to Country Lite"
 * CSV; values may be quoted; a {@code .gz} file is read uncompressed), IPv4 and IPv6 ranges in any order. Ranges must
 * not overlap. Lines that are empty or start with {@code #} are skipped; any other line that cannot be read makes the
 * whole file unreadable, rather than leaving some addresses silently unknown.
 */
public final class IpCountryDatabase {

    /** A database with no ranges: every address is unknown. */
    public static final IpCountryDatabase EMPTY = new IpCountryDatabase(new long[0], new long[0], new String[0],
        new long[0], new long[0], new long[0], new long[0], new String[0]);

    // IPv4: unsigned 32-bit values in longs. IPv6: unsigned 128-bit values as high and low halves.
    private final long[] v4First;
    private final long[] v4Last;
    private final String[] v4Country;
    private final long[] v6FirstHigh;
    private final long[] v6FirstLow;
    private final long[] v6LastHigh;
    private final long[] v6LastLow;
    private final String[] v6Country;

    private IpCountryDatabase(long[] v4First, long[] v4Last, String[] v4Country, long[] v6FirstHigh,
        long[] v6FirstLow, long[] v6LastHigh, long[] v6LastLow, String[] v6Country) {
        this.v4First = v4First;
        this.v4Last = v4Last;
        this.v4Country = v4Country;
        this.v6FirstHigh = v6FirstHigh;
        this.v6FirstLow = v6FirstLow;
        this.v6LastHigh = v6LastHigh;
        this.v6LastLow = v6LastLow;
        this.v6Country = v6Country;
    }

    /** The file could not be read as a database. */
    public static final class UnreadableDatabaseException extends IOException {
        UnreadableDatabaseException(String message) {
            super(message);
        }
    }

    /** Reads the file at {@code path} (gzip when its name ends in {@code .gz}). */
    public static IpCountryDatabase read(Path path) throws IOException {
        Objects.requireNonNull(path, "path must not be null");
        try (InputStream raw = Files.newInputStream(path)) {
            InputStream in = path.getFileName().toString().endsWith(".gz") ? new GZIPInputStream(raw) : raw;
            return read(in, path.toString());
        }
    }

    /** Reads a database from {@code in}; {@code name} only names it in errors. */
    public static IpCountryDatabase read(InputStream in, String name) throws IOException {
        List<Range> v4 = new ArrayList<>();
        List<Range> v6 = new ArrayList<>();
        Map<String, String> codes = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            int number = 0;
            while ((line = reader.readLine()) != null) {
                number++;
                String trimmed = line.strip();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                String[] parts = trimmed.split(",", -1);
                if (parts.length < 3) {
                    throw new UnreadableDatabaseException(name + " line " + number + ": expected first,last,country");
                }
                byte[] first = address(unquote(parts[0]), name, number);
                byte[] last = address(unquote(parts[1]), name, number);
                if (first.length != last.length) {
                    throw new UnreadableDatabaseException(name + " line " + number + ": a range mixes IPv4 and IPv6");
                }
                ClientCountry country;
                try {
                    country = ClientCountry.of(unquote(parts[2]));
                } catch (IllegalArgumentException e) {
                    throw new UnreadableDatabaseException(name + " line " + number + ": " + e.getMessage());
                }
                if (!country.known()) {
                    continue;
                }
                String code = codes.computeIfAbsent(country.code(), c -> c);
                Range range = new Range(high(first), low(first), high(last), low(last), code);
                if (compare(range.firstHigh, range.firstLow, range.lastHigh, range.lastLow) > 0) {
                    throw new UnreadableDatabaseException(name + " line " + number + ": the range ends before it starts");
                }
                (first.length == 4 ? v4 : v6).add(range);
            }
        }
        return of(v4, v6, name);
    }

    private record Range(long firstHigh, long firstLow, long lastHigh, long lastLow, String country) {}

    private static IpCountryDatabase of(List<Range> v4, List<Range> v6, String name)
        throws UnreadableDatabaseException {
        java.util.Comparator<Range> order = (a, b) -> compare(a.firstHigh, a.firstLow, b.firstHigh, b.firstLow);
        v4.sort(order);
        v6.sort(order);
        for (List<Range> ranges : List.of(v4, v6)) {
            for (int i = 1; i < ranges.size(); i++) {
                Range before = ranges.get(i - 1);
                Range after = ranges.get(i);
                if (compare(before.lastHigh, before.lastLow, after.firstHigh, after.firstLow) >= 0) {
                    throw new UnreadableDatabaseException(name + ": ranges overlap");
                }
            }
        }
        long[] v4First = new long[v4.size()];
        long[] v4Last = new long[v4.size()];
        String[] v4Country = new String[v4.size()];
        for (int i = 0; i < v4.size(); i++) {
            v4First[i] = v4.get(i).firstLow;
            v4Last[i] = v4.get(i).lastLow;
            v4Country[i] = v4.get(i).country;
        }
        int n = v6.size();
        long[] fh = new long[n];
        long[] fl = new long[n];
        long[] lh = new long[n];
        long[] ll = new long[n];
        String[] c6 = new String[n];
        for (int i = 0; i < n; i++) {
            Range r = v6.get(i);
            fh[i] = r.firstHigh;
            fl[i] = r.firstLow;
            lh[i] = r.lastHigh;
            ll[i] = r.lastLow;
            c6[i] = r.country;
        }
        return new IpCountryDatabase(v4First, v4Last, v4Country, fh, fl, lh, ll, c6);
    }

    /** How many ranges it holds. */
    public int size() {
        return v4First.length + v6FirstHigh.length;
    }

    /**
     * The country of the address {@code address} (a literal, IPv4 or IPv6, as the client addresses give it); unknown
     * when it is not listed or is no address literal. Host names are never resolved.
     */
    public ClientCountry countryOf(String address) {
        byte[] bytes = literal(address);
        if (bytes == null) {
            return ClientCountry.UNKNOWN;
        }
        // An IPv4 address mapped into IPv6 (::ffff:a.b.c.d) is looked up as IPv4.
        if (bytes.length == 16 && mappedV4(bytes)) {
            bytes = Arrays.copyOfRange(bytes, 12, 16);
        }
        if (bytes.length == 4) {
            long value = low(bytes);
            int at = Arrays.binarySearch(v4First, value);
            int index = at >= 0 ? at : -at - 2;
            return index >= 0 && value <= v4Last[index] ? ClientCountry.of(v4Country[index]) : ClientCountry.UNKNOWN;
        }
        long high = high(bytes);
        long low = low(bytes);
        int from = 0;
        int to = v6FirstHigh.length - 1;
        int index = -1;
        while (from <= to) {
            int mid = (from + to) >>> 1;
            if (compare(v6FirstHigh[mid], v6FirstLow[mid], high, low) <= 0) {
                index = mid;
                from = mid + 1;
            } else {
                to = mid - 1;
            }
        }
        return index >= 0 && compare(high, low, v6LastHigh[index], v6LastLow[index]) <= 0
            ? ClientCountry.of(v6Country[index]) : ClientCountry.UNKNOWN;
    }

    private static boolean mappedV4(byte[] bytes) {
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff;
    }

    /** The bytes of an address literal, or null; never a name lookup (only digits, dots, colons and hex). */
    static byte[] literal(String address) {
        if (address == null) {
            return null;
        }
        String value = address.strip();
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        int zone = value.indexOf('%');
        if (zone >= 0) {
            value = value.substring(0, zone);
        }
        if (value.isEmpty() || !value.chars().allMatch(c -> Character.digit(c, 16) >= 0 || c == '.' || c == ':')) {
            return null;
        }
        if (value.indexOf(':') < 0 && !value.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
            return null;
        }
        try {
            return InetAddress.getByName(value).getAddress();
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private static byte[] address(String literal, String name, int line) throws UnreadableDatabaseException {
        byte[] bytes = literal(literal);
        if (bytes == null) {
            throw new UnreadableDatabaseException(name + " line " + line + ": not an address: " + literal);
        }
        return bytes;
    }

    private static String unquote(String value) {
        String v = value.strip();
        return v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"") ? v.substring(1, v.length() - 1) : v;
    }

    private static long high(byte[] bytes) {
        if (bytes.length == 4) {
            return 0;
        }
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (bytes[i] & 0xff);
        }
        return value;
    }

    private static long low(byte[] bytes) {
        long value = 0;
        for (int i = bytes.length == 4 ? 0 : 8; i < bytes.length; i++) {
            value = (value << 8) | (bytes[i] & 0xff);
        }
        return value;
    }

    private static int compare(long aHigh, long aLow, long bHigh, long bLow) {
        int high = Long.compareUnsigned(aHigh, bHigh);
        return high != 0 ? high : Long.compareUnsigned(aLow, bLow);
    }
}
