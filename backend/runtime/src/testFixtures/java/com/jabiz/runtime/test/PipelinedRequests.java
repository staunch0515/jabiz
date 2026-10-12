package com.jabiz.runtime.test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/**
 * Speaks HTTP/1.1 over a plain socket to check that a keep-alive connection is still read after a pipelined request
 * (see {@code PipelinedReadResumer}): requests in one write, so that the later ones are buffered while the first is
 * answered, then, once those answers are in, a last request on the same connection.
 */
public final class PipelinedRequests {

    private PipelinedRequests() {}

    /** A {@code GET} of {@code path} with the given extra header lines (each ending in {@code \r\n}). */
    public static String get(String path, String... headerLines) {
        return "GET " + path + " HTTP/1.1\r\nHost: localhost\r\n" + String.join("", headerLines) + "\r\n";
    }

    /**
     * Sends {@code first} and {@code second} in one write, reads both answers, then sends {@code third}: whether it is
     * answered within {@code timeoutMillis}.
     */
    public static boolean thirdAnswered(int port, String first, String second, String third, int timeoutMillis)
        throws IOException {
        return answeredAfter(port, first + second, 2, third, timeoutMillis);
    }

    /**
     * Sends {@code initial} in one write and reads {@code initialAnswers} answers, then sends {@code next} (which may
     * finish a request {@code initial} left incomplete): whether one more answer arrives within {@code timeoutMillis}.
     * Answers are framed by {@code Content-Length} or chunked transfer coding.
     */
    public static boolean answeredAfter(int port, String initial, int initialAnswers, String next, int timeoutMillis)
        throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(timeoutMillis);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            out.write(initial.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            readResponses(in, initialAnswers);
            out.write(next.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            try {
                readResponses(in, 1);
                return true;
            }
            catch (SocketTimeoutException e) {
                return false;
            }
        }
    }

    /** Reads until {@code count} complete responses have arrived. */
    private static void readResponses(InputStream in, int count) throws IOException {
        var seen = new ByteArrayOutputStream();
        int complete = 0;
        while (complete < count) {
            int b = in.read();
            if (b < 0) {
                throw new IOException("connection closed after " + complete + " responses");
            }
            seen.write(b);
            String text = seen.toString(StandardCharsets.US_ASCII);
            int headersEnd = text.indexOf("\r\n\r\n");
            if (headersEnd >= 0 && isComplete(text.substring(0, headersEnd), text.substring(headersEnd + 4))) {
                complete++;
                seen.reset();
            }
        }
    }

    private static boolean isComplete(String headers, String body) {
        String length = header(headers, "content-length");
        if (length != null) {
            return body.length() == Integer.parseInt(length);
        }
        String coding = header(headers, "transfer-encoding");
        if (coding != null && coding.toLowerCase().contains("chunked")) {
            // The last chunk has size 0; no trailers are expected here.
            return body.equals("0\r\n\r\n") || body.endsWith("\r\n0\r\n\r\n");
        }
        throw new IllegalStateException("response framed neither by content-length nor chunked: " + headers);
    }

    private static String header(String headers, String name) {
        for (String line : headers.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase(name)) {
                return line.substring(colon + 1).trim();
            }
        }
        return null;
    }
}
