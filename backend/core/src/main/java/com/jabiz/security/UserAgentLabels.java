package com.jabiz.security;

/**
 * What a session list shows of a client's user agent (docs/design/10-security.md section 15.9): the browser, the
 * operating system and the kind of device, as a few fixed codes the frontends translate. Deliberately coarse: it names
 * the families people recognise ("Chrome on Windows"), not versions, and anything it does not recognise is
 * {@code OTHER}. The user agent is the client's own claim, so the labels only help people tell their sessions apart;
 * nothing is decided by them.
 */
public final class UserAgentLabels {

    /** Browser families, in the order they are told apart (several claim to be others as well). */
    public enum Browser { EDGE, OPERA, SAMSUNG, WECHAT, FIREFOX, CHROME, SAFARI, OTHER }

    /** Operating systems; iPadOS counts as {@code IOS}. */
    public enum Os { WINDOWS, IOS, ANDROID, CHROME_OS, MACOS, LINUX, OTHER }

    /** Kinds of device. */
    public enum DeviceType { DESKTOP, MOBILE, TABLET, OTHER }

    /** The labels of one user agent. */
    public record Labels(Browser browser, Os os, DeviceType deviceType) {

        /** Nothing recognised (no user agent, or one of no known browser). */
        public static final Labels UNKNOWN = new Labels(Browser.OTHER, Os.OTHER, DeviceType.OTHER);
    }

    /** Longer user agents are read only this far (the stored ones are cut at 256 characters). */
    static final int MAX_LENGTH = 512;

    private UserAgentLabels() {}

    /** The labels of a user agent; {@link Labels#UNKNOWN} for none. */
    public static Labels of(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return Labels.UNKNOWN;
        }
        String ua = userAgent.length() > MAX_LENGTH ? userAgent.substring(0, MAX_LENGTH) : userAgent;
        Os os = os(ua);
        return new Labels(browser(ua, os), os, deviceType(ua, os));
    }

    private static Os os(String ua) {
        // iOS claims to be "like Mac OS X", Android runs on Linux, Chrome OS names Linux too: the specific first.
        if (ua.contains("Windows")) {
            return Os.WINDOWS;
        }
        if (ua.contains("iPhone") || ua.contains("iPad") || ua.contains("iPod")) {
            return Os.IOS;
        }
        if (ua.contains("Android")) {
            return Os.ANDROID;
        }
        if (ua.contains("CrOS")) {
            return Os.CHROME_OS;
        }
        if (ua.contains("Macintosh") || ua.contains("Mac OS X")) {
            return Os.MACOS;
        }
        if (ua.contains("Linux") || ua.contains("X11")) {
            return Os.LINUX;
        }
        return Os.OTHER;
    }

    private static Browser browser(String ua, Os os) {
        // Most browsers also claim Chrome and Safari; Chrome claims Safari: the most specific token wins.
        if (ua.contains("Edg/") || ua.contains("EdgA/") || ua.contains("EdgiOS/") || ua.contains("Edge/")) {
            return Browser.EDGE;
        }
        if (ua.contains("OPR/") || ua.contains("Opera")) {
            return Browser.OPERA;
        }
        if (ua.contains("SamsungBrowser/")) {
            return Browser.SAMSUNG;
        }
        if (ua.contains("MicroMessenger/")) {
            return Browser.WECHAT;
        }
        if (ua.contains("Firefox/") || ua.contains("FxiOS/")) {
            return Browser.FIREFOX;
        }
        if (ua.contains("Chrome/") || ua.contains("CriOS/") || ua.contains("Chromium/")) {
            return Browser.CHROME;
        }
        if (ua.contains("Safari/") && (ua.contains("Version/") || os == Os.IOS)) {
            return Browser.SAFARI;
        }
        return Browser.OTHER;
    }

    private static DeviceType deviceType(String ua, Os os) {
        if (ua.contains("iPad") || ua.contains("Tablet")) {
            return DeviceType.TABLET;
        }
        if (os == Os.ANDROID) {
            // Android phones say "Mobile"; tablets do not.
            return ua.contains("Mobile") ? DeviceType.MOBILE : DeviceType.TABLET;
        }
        if (os == Os.IOS || ua.contains("Mobile")) {
            return DeviceType.MOBILE;
        }
        return switch (os) {
            case WINDOWS, MACOS, LINUX, CHROME_OS -> DeviceType.DESKTOP;
            default -> DeviceType.OTHER;
        };
    }
}
