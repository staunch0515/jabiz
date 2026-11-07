package com.jabiz.runtime.file;

import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Startup check of {@code jabiz.files.fetch.*} (category {@value FileChecks#CATEGORY}, decision D75), every problem at
 * once: redirects 0 to {@value RemoteFetchProperties#MAX_REDIRECTS}; the connect timeout positive and at most 30 s; the
 * read timeout positive and at most 2 min; the deadline at least the read timeout and at most 5 min; ports 1 to 65535.
 * A port other than 443 is a warning: links to unusual ports of public hosts are seldom images.
 */
@Component
public class RemoteFetchChecks implements PlatformCheck {

    private static final String WHERE = "jabiz.files.fetch";

    private final RemoteFetchProperties properties;

    public RemoteFetchChecks(RemoteFetchProperties properties) {
        this.properties = properties;
    }

    @Override
    public List<CheckProblem> check() {
        List<CheckProblem> problems = new ArrayList<>();
        int redirects = properties.maxRedirects();
        if (redirects < 0 || redirects > RemoteFetchProperties.MAX_REDIRECTS) {
            problems.add(error(".max-redirects", "must be 0 to " + RemoteFetchProperties.MAX_REDIRECTS + ", not "
                + redirects));
        }
        within(".connect-timeout", properties.connectTimeout(), RemoteFetchProperties.MAX_CONNECT_TIMEOUT, problems);
        within(".read-timeout", properties.readTimeout(), RemoteFetchProperties.MAX_READ_TIMEOUT, problems);
        Duration deadline = properties.deadline();
        if (deadline.compareTo(properties.readTimeout()) < 0
            || deadline.compareTo(RemoteFetchProperties.MAX_DEADLINE) > 0) {
            problems.add(error(".deadline", "must be at least the read timeout (" + properties.readTimeout()
                + ") and at most " + RemoteFetchProperties.MAX_DEADLINE + ", not " + deadline));
        }
        for (Integer port : properties.allowedPorts()) {
            if (port == null || port < 1 || port > 65535) {
                problems.add(error(".allowed-ports", "must be ports 1 to 65535, not " + port));
            } else if (port != 443) {
                problems.add(CheckProblem.warning(FileChecks.CATEGORY, WHERE + ".allowed-ports",
                    "allows port " + port + ": links to public hosts on ports other than 443 are followed"));
            }
        }
        return problems;
    }

    private static void within(String name, Duration value, Duration max, List<CheckProblem> problems) {
        if (value.isNegative() || value.isZero() || value.compareTo(max) > 0) {
            problems.add(error(name, "must be positive and at most " + max + ", not " + value));
        }
    }

    private static CheckProblem error(String name, String message) {
        return CheckProblem.error(FileChecks.CATEGORY, WHERE + name, message);
    }
}
