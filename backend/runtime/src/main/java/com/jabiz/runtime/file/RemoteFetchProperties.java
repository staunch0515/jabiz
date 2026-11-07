package com.jabiz.runtime.file;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * Settings of downloading images from links ({@code jabiz.files.fetch.*}, docs/design/14-files.md section 12,
 * decision D75). Values out of range are reported by the startup check ({@code FILE} category).
 *
 * @param maxRedirects   redirects followed at most (default 3; 0 to 10); every hop is checked as the link was
 * @param connectTimeout per connection (default 5 s; at most 30 s)
 * @param readTimeout    longest wait for the next bytes of an answer (default 20 s; at most 2 min)
 * @param deadline       the whole download, every hop and the name lookups included (default 60 s; at most 5 min,
 *                       and at least the read timeout); the process's transaction stays open meanwhile
 * @param allowedPorts   ports a link may name (default 443 only); other ports refuse the link
 */
@ConfigurationProperties("jabiz.files.fetch")
public record RemoteFetchProperties(Integer maxRedirects, Duration connectTimeout, Duration readTimeout,
    Duration deadline, List<Integer> allowedPorts) {

    public static final int MAX_REDIRECTS = 10;
    public static final Duration MAX_CONNECT_TIMEOUT = Duration.ofSeconds(30);
    public static final Duration MAX_READ_TIMEOUT = Duration.ofMinutes(2);
    public static final Duration MAX_DEADLINE = Duration.ofMinutes(5);

    public RemoteFetchProperties {
        maxRedirects = maxRedirects == null ? 3 : maxRedirects;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(20) : readTimeout;
        deadline = deadline == null ? Duration.ofSeconds(60) : deadline;
        allowedPorts = allowedPorts == null || allowedPorts.isEmpty() ? List.of(443) : List.copyOf(allowedPorts);
    }

    public Set<Integer> ports() {
        return Set.copyOf(allowedPorts);
    }
}
