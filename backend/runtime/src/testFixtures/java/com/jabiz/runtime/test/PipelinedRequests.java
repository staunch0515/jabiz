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
 * (see {@code PipelinedReadResumer}): two requests in one write, so that the second is buffered while the first is
 * answered, then, once both answers are in, a third on the same connection.
 */
public final class PipelinedRequests {

    private PipelinedRequests() {}

    /** A {@code GET} of {@code path} with the given extra header lines (each ending in {@code \r\n}). */
    public static String get(String path, String... headerLines) {
        return "GET " + path + " HTTP/1.1\r\nHost: localhost\r\n" + String.join("", headerLines) + "\r\n";
    }

    /**
     * Sends {@code first} and {@code second} in one write, reads both answers, then sends {@code third}: whether it is
     * answered within {@code timeoutMillis}. Every answer must carry a {@code Content-Length}.
     */
    public static boolean thirdAnswered(int port, String first, String second, String third, int timeoutMillis)
        throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(timeoutMillis);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            out.write((first + second).getBytes(StandardCharsets.US_ASCII));
            out.flush();
            readResponses(in, 2);
            out.write(third.getBytes(StandardCharsets.US_ASCII));
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
            if (headersEnd >= 0 && text.length() - headersEnd - 4 == contentLength(text.substring(0, headersEnd))) {
                complete++;
                seen.reset();
            }
        }
    }

    private static int contentLength(String headers) {
        for (String line : headers.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("content-length")) {
                return Integer.parseInt(line.substring(colon + 1).trim());
            }
        }
        throw new IllegalStateException("no content-length in " + headers);
    }
}
