package com.jabiz.runtime.test;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A local HTTPS server for tests of downloads from links (decision D75), on a plain {@link SSLServerSocket} so that
 * answers can be anything a hostile server sends: binary bodies, a length that differs from the body, no length at all
 * (chunked, or until the connection closes), delays before the head and between bytes. Every connection and request
 * is recorded with the address the client connected to and the request's headers, as sent. One answer per path from a
 * script ({@link #script}), in order, else the fallback.
 *
 * <p>The certificate is made by {@code keytool} for {@code fetch.test}, {@code *.fetch.test} and the loopback
 * addresses ({@link #certificate()}): clients trust exactly it.
 */
public final class HttpsStubServer implements AutoCloseable {

    /** A request as received; header names in lower case, values as sent (repeated headers joined by ", "). */
    public record Received(String method, String target, Map<String, String> headers, InetAddress local) {

        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }

        public String path() {
            int query = target.indexOf('?');
            return query < 0 ? target : target.substring(0, query);
        }
    }

    /**
     * One answer.
     *
     * @param length    the {@code Content-Length} sent: null for the body's own, -1 for none (the body then ends
     *                  with the connection), any other value as given, true or not
     * @param inChunks  whether to send the body chunked (no length)
     * @param delay     how long to wait before the head; null for none
     * @param drip      how long to wait between the body's bytes, sent one by one; null to send it at once
     */
    public record Answer(int status, Map<String, String> headers, byte[] body, Long length, boolean inChunks,
        Duration delay, Duration drip) {

        public Answer {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
            body = body == null ? new byte[0] : body;
        }

        public static Answer of(int status, byte[] body) {
            return new Answer(status, Map.of(), body, null, false, null, null);
        }

        public static Answer redirect(int status, String location) {
            return new Answer(status, Map.of("Location", location), new byte[0], null, false, null, null);
        }

        public Answer header(String name, String value) {
            Map<String, String> more = new LinkedHashMap<>(headers);
            more.put(name, value);
            return new Answer(status, more, body, length, inChunks, delay, drip);
        }

        public Answer length(long declared) {
            return new Answer(status, headers, body, declared, inChunks, delay, drip);
        }

        public Answer chunked() {
            return new Answer(status, headers, body, null, true, delay, drip);
        }

        public Answer after(Duration wait) {
            return new Answer(status, headers, body, length, inChunks, wait, drip);
        }

        public Answer dripping(Duration pause) {
            return new Answer(status, headers, body, length, inChunks, delay, pause);
        }
    }

    private static volatile KeyStore keyStore;

    private final SSLServerSocket server;
    private final ExecutorService threads = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "https-stub");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, Queue<Answer>> scripts = new ConcurrentHashMap<>();
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final AtomicInteger connections = new AtomicInteger();
    private volatile Answer fallback = Answer.of(404, new byte[0]);

    private HttpsStubServer(SSLServerSocket server) {
        this.server = server;
        threads.execute(this::accept);
    }

    /** Starts a stub on {@code address} (a loopback address), on a free port. */
    public static HttpsStubServer start(InetAddress address) {
        try {
            KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keys.init(keyStore(), PASSWORD);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keys.getKeyManagers(), null, null);
            SSLServerSocket socket = (SSLServerSocket) context.getServerSocketFactory().createServerSocket();
            socket.bind(new InetSocketAddress(address, 0), 50);
            return new HttpsStubServer(socket);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot start the HTTPS stub", e);
        }
    }

    /** The stub's certificate, for the client's trust. */
    public static X509Certificate certificate() {
        try {
            return (X509Certificate) keyStore().getCertificate("stub");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public int port() {
        return server.getLocalPort();
    }

    /** Answers the next requests of {@code path} (without the query) with {@code answers}, in order. */
    public void script(String path, Answer... answers) {
        scripts.computeIfAbsent(path, p -> new ConcurrentLinkedQueue<>()).addAll(List.of(answers));
    }

    public void fallback(Answer answer) {
        this.fallback = answer;
    }

    public List<Received> received() {
        return List.copyOf(received);
    }

    public List<Received> received(String path) {
        return received.stream().filter(r -> r.path().equals(path)).toList();
    }

    /** Connections accepted, whether or not a request followed. */
    public int connections() {
        return connections.get();
    }

    @Override
    public void close() {
        try {
            server.close();
        } catch (IOException ignored) {
            // closing anyway
        }
        threads.shutdownNow();
        try {
            threads.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void accept() {
        while (!server.isClosed()) {
            try {
                SSLSocket socket = (SSLSocket) server.accept();
                connections.incrementAndGet();
                threads.execute(() -> serve(socket));
            } catch (IOException e) {
                return;
            }
        }
    }

    private void serve(SSLSocket socket) {
        try (socket) {
            socket.setSoTimeout(30_000);
            InputStream in = socket.getInputStream();
            String head = readHead(in);
            if (head == null) {
                return;
            }
            String[] lines = head.split("\r\n");
            String[] requestLine = lines[0].split(" ");
            Map<String, String> headers = new LinkedHashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                if (colon > 0) {
                    headers.merge(lines[i].substring(0, colon).strip().toLowerCase(Locale.ROOT),
                        lines[i].substring(colon + 1).strip(), (a, b) -> a + ", " + b);
                }
            }
            Received request = new Received(requestLine[0], requestLine.length > 1 ? requestLine[1] : "",
                headers, socket.getLocalAddress());
            received.add(request);
            Queue<Answer> script = scripts.get(request.path());
            Answer answer = script == null ? null : script.poll();
            write(socket.getOutputStream(), answer == null ? fallback : answer);
        } catch (SocketException e) {
            // the client went away (a refused download, a timeout)
        } catch (IOException e) {
            // the same, seen differently
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int matched = 0;
        byte[] end = {'\r', '\n', '\r', '\n'};
        while (matched < 4) {
            int b = in.read();
            if (b < 0) {
                return null;
            }
            head.write(b);
            matched = b == end[matched] ? matched + 1 : (b == '\r' ? 1 : 0);
            if (head.size() > 64 * 1024) {
                return null;
            }
        }
        return head.toString(StandardCharsets.ISO_8859_1);
    }

    private static void write(OutputStream out, Answer answer) throws IOException, InterruptedException {
        if (answer.delay() != null) {
            Thread.sleep(answer.delay().toMillis());
        }
        StringBuilder head = new StringBuilder("HTTP/1.1 ").append(answer.status()).append(' ')
            .append(answer.status() == 200 ? "OK" : "Status").append("\r\n");
        answer.headers().forEach((name, value) -> head.append(name).append(": ").append(value).append("\r\n"));
        if (answer.inChunks()) {
            head.append("Transfer-Encoding: chunked\r\n");
        } else if (answer.length() == null) {
            head.append("Content-Length: ").append(answer.body().length).append("\r\n");
        } else if (answer.length() >= 0) {
            head.append("Content-Length: ").append(answer.length()).append("\r\n");
        }
        head.append("Connection: close\r\n\r\n");
        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
        byte[] body = answer.body();
        if (answer.inChunks()) {
            int chunk = 8 * 1024;
            for (int offset = 0; offset < body.length; offset += chunk) {
                int n = Math.min(chunk, body.length - offset);
                out.write((Integer.toHexString(n) + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
                out.write(body, offset, n);
                out.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
            }
            out.write("0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
        } else if (answer.drip() != null) {
            for (byte b : body) {
                out.write(b);
                out.flush();
                Thread.sleep(answer.drip().toMillis());
            }
        } else {
            out.write(body);
        }
        out.flush();
    }

    private static final char[] PASSWORD = "changeit".toCharArray();

    /** Made once per JVM with {@code keytool}: no key material in the repository, nothing to expire. */
    private static KeyStore keyStore() throws Exception {
        KeyStore store = keyStore;
        if (store != null) {
            return store;
        }
        synchronized (HttpsStubServer.class) {
            if (keyStore == null) {
                Path dir = Files.createTempDirectory("https-stub-");
                Path file = dir.resolve("stub.p12");
                String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
                List<String> command = new ArrayList<>(List.of(keytool, "-genkeypair", "-alias", "stub",
                    "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=fetch.test",
                    "-ext", "SAN=dns:fetch.test,dns:*.fetch.test,ip:127.0.0.1,ip:127.0.0.2",
                    "-validity", "3650", "-keystore", file.toString(), "-storetype", "PKCS12",
                    "-storepass", new String(PASSWORD), "-keypass", new String(PASSWORD)));
                Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                if (process.waitFor() != 0) {
                    throw new IllegalStateException("keytool failed: " + output);
                }
                KeyStore loaded = KeyStore.getInstance("PKCS12");
                try (InputStream in = Files.newInputStream(file)) {
                    loaded.load(in, PASSWORD);
                }
                Files.deleteIfExists(file);
                Files.deleteIfExists(dir);
                keyStore = loaded;
            }
            return keyStore;
        }
    }
}
