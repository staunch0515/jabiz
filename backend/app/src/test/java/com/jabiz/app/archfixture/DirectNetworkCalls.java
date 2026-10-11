package com.jabiz.app.archfixture;

import com.jabiz.runtime.external.ExternalCallScope;
import io.netty.bootstrap.Bootstrap;
import org.springframework.core.io.UrlResource;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Business code that bypasses the declared external services, one way per class: the counter-examples of
 * {@code ArchitectureTest}'s network rules. Test sources only, so the real check never sees them.
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

    public static final class UrlContent {
        Object content(URL url) throws IOException {
            return url.getContent();
        }
    }

    public static final class SpringClients {
        Object clients() {
            return java.util.List.of(WebClient.create(), RestClient.create());
        }
    }

    public static final class SpringRequestFactory {
        Object factory() {
            return new SimpleClientHttpRequestFactory();
        }
    }

    public static final class SpringUrlResource {
        long length() throws IOException {
            return new UrlResource("https://example.com/feed.xml").contentLength();
        }
    }

    public static final class Netty {
        Object bootstrap() {
            return new Bootstrap();
        }
    }

    public static final class DomFromAddress {
        Document parse() throws ParserConfigurationException, IOException, SAXException {
            return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse("https://example.com/a.xml");
        }
    }

    public static final class SaxFromAddress {
        void parse() throws ParserConfigurationException, IOException, SAXException {
            SAXParserFactory.newInstance().newSAXParser().parse("https://example.com/a.xml", new DefaultHandler());
        }
    }

    public static final class ReaderFromAddress {
        void parse(XMLReader reader) throws IOException, SAXException {
            reader.parse("https://example.com/a.xml");
        }
    }

    /** Parsing text the code already has is fine: the network rule leaves it alone. */
    public static final class DomFromText {
        Document parse(String xml) throws ParserConfigurationException, IOException, SAXException {
            return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new InputSource(
                new StringReader(xml)));
        }
    }

    /** Reaches into the platform's machinery to fake a blocking step. */
    public static final class ForgedScope {
        Object forge() throws Exception {
            return ExternalCallScope.open(1L, "forged", false, () -> "called");
        }
    }
}
