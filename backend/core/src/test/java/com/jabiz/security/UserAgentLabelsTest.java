package com.jabiz.security;

import com.jabiz.security.UserAgentLabels.Browser;
import com.jabiz.security.UserAgentLabels.DeviceType;
import com.jabiz.security.UserAgentLabels.Labels;
import com.jabiz.security.UserAgentLabels.Os;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class UserAgentLabelsTest {

    static Stream<Arguments> userAgents() {
        return Stream.of(
            Arguments.of("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                + "Chrome/126.0.0.0 Safari/537.36", Browser.CHROME, Os.WINDOWS, DeviceType.DESKTOP),
            Arguments.of("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                + "Chrome/126.0.0.0 Safari/537.36 Edg/126.0.2592.68", Browser.EDGE, Os.WINDOWS, DeviceType.DESKTOP),
            Arguments.of("Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0",
                Browser.FIREFOX, Os.WINDOWS, DeviceType.DESKTOP),
            Arguments.of("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                + "Chrome/126.0.0.0 Safari/537.36 OPR/111.0.0.0", Browser.OPERA, Os.WINDOWS, DeviceType.DESKTOP),
            Arguments.of("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) "
                + "Version/17.5 Safari/605.1.15", Browser.SAFARI, Os.MACOS, DeviceType.DESKTOP),
            Arguments.of("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) "
                + "Chrome/126.0.0.0 Safari/537.36", Browser.CHROME, Os.MACOS, DeviceType.DESKTOP),
            Arguments.of("Mozilla/5.0 (Macintosh; Intel Mac OS X 14.5; rv:128.0) Gecko/20100101 Firefox/128.0",
                Browser.FIREFOX, Os.MACOS, DeviceType.DESKTOP),
            Arguments.of("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 "
                + "Safari/537.36", Browser.CHROME, Os.LINUX, DeviceType.DESKTOP),
            Arguments.of("Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:128.0) Gecko/20100101 Firefox/128.0",
                Browser.FIREFOX, Os.LINUX, DeviceType.DESKTOP),
            Arguments.of("Mozilla/5.0 (X11; CrOS x86_64 14541.0.0) AppleWebKit/537.36 (KHTML, like Gecko) "
                + "Chrome/126.0.0.0 Safari/537.36", Browser.CHROME, Os.CHROME_OS, DeviceType.DESKTOP),
            Arguments.of("Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like "
                + "Gecko) Version/17.5 Mobile/15E148 Safari/604.1", Browser.SAFARI, Os.IOS, DeviceType.MOBILE),
            Arguments.of("Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like "
                + "Gecko) CriOS/126.0.6478.54 Mobile/15E148 Safari/604.1", Browser.CHROME, Os.IOS, DeviceType.MOBILE),
            Arguments.of("Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like "
                + "Gecko) FxiOS/127.0 Mobile/15E148 Safari/605.1.15", Browser.FIREFOX, Os.IOS, DeviceType.MOBILE),
            Arguments.of("Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like "
                + "Gecko) Mobile/15E148 MicroMessenger/8.0.49(0x18003137) NetType/WIFI Language/zh_CN",
                Browser.WECHAT, Os.IOS, DeviceType.MOBILE),
            Arguments.of("Mozilla/5.0 (iPad; CPU OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) "
                + "Version/17.5 Mobile/15E148 Safari/604.1", Browser.SAFARI, Os.IOS, DeviceType.TABLET),
            Arguments.of("Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) "
                + "Chrome/126.0.6478.71 Mobile Safari/537.36", Browser.CHROME, Os.ANDROID, DeviceType.MOBILE),
            Arguments.of("Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) "
                + "SamsungBrowser/25.0 Chrome/121.0.0.0 Mobile Safari/537.36", Browser.SAMSUNG, Os.ANDROID,
                DeviceType.MOBILE),
            Arguments.of("Mozilla/5.0 (Linux; Android 13; SM-X710) AppleWebKit/537.36 (KHTML, like Gecko) "
                + "Chrome/126.0.0.0 Safari/537.36", Browser.CHROME, Os.ANDROID, DeviceType.TABLET),
            Arguments.of("Mozilla/5.0 (Android 14; Mobile; rv:128.0) Gecko/128.0 Firefox/128.0", Browser.FIREFOX,
                Os.ANDROID, DeviceType.MOBILE),
            Arguments.of("Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) "
                + "Chrome/126.0.0.0 Mobile Safari/537.36 EdgA/126.0.2592.80", Browser.EDGE, Os.ANDROID,
                DeviceType.MOBILE),
            Arguments.of("curl/8.8.0", Browser.OTHER, Os.OTHER, DeviceType.OTHER),
            Arguments.of("Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)", Browser.OTHER,
                Os.OTHER, DeviceType.OTHER),
            Arguments.of("Opera/9.80 (X11; Linux x86_64) Presto/2.12.388 Version/12.16", Browser.OPERA, Os.LINUX,
                DeviceType.DESKTOP),
            Arguments.of("SomeTablet Safari/1", Browser.OTHER, Os.OTHER, DeviceType.TABLET));
    }

    @ParameterizedTest
    @MethodSource("userAgents")
    void namesTheBrowserSystemAndDevice(String userAgent, Browser browser, Os os, DeviceType device) {
        assertThat(UserAgentLabels.of(userAgent)).isEqualTo(new Labels(browser, os, device));
    }

    @Test
    void nothingIsUnknown() {
        assertThat(UserAgentLabels.of(null)).isEqualTo(Labels.UNKNOWN);
        assertThat(UserAgentLabels.of("  ")).isEqualTo(Labels.UNKNOWN);
    }

    @Test
    void readsOnlyTheStartOfAnOverlongUserAgent() {
        String padding = "x".repeat(UserAgentLabels.MAX_LENGTH);
        // What lies beyond the limit is not looked at.
        assertThat(UserAgentLabels.of(padding + " (Windows NT 10.0) Chrome/126.0")).isEqualTo(Labels.UNKNOWN);
        assertThat(UserAgentLabels.of("Mozilla/5.0 (Windows NT 10.0) Chrome/126.0 " + padding))
            .isEqualTo(new Labels(Browser.CHROME, Os.WINDOWS, DeviceType.DESKTOP));
    }
}
