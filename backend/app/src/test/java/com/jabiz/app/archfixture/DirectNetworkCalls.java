package com.jabiz.app.archfixture;

import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Business code that bypasses the declared external services, one way per class: the counter-examples of
 * {@code ArchitectureTest#businessCodeUsesNoNetworkClient}. Test sources only, so the real check never sees them.
 */
public final class DirectNetworkCalls {

    private DirectNetworkCalls() {}

    public static final class JdkClient {
        String fetch() throws IOException, InterruptedException {
            try (HttpClient client = HttpClient.newHttpClient()) {
                return client.send(HttpRequest.newBuilder(URI.create("https://example.com")).build(),
                    HttpResponse.BodyHandlers.ofString()).body();
            }
        }
    }

    public static final class RawSocket {
        void connect() throws IOException {
            try (Socket socket = new Socket("example.com", 443)) {
                socket.getOutputStream().flush();
            }
        }
    }

    public static final class UrlStream {
        int read() throws IOException {
            try (InputStream in = URI.create("https://example.com").toURL().openStream()) {
                return in.read();
            }
        }
    }

    public static final class UrlConnection {
        Object open(URL url) throws IOException {
            return url.openConnection();
        }
    }

    public static final class SpringClients {
        Object clients() {
            return java.util.List.of(WebClient.create(), RestClient.create());
        }
    }
}
