import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A stand-in for the demo AI service {@code demo-ai} (decision D38) for manual demonstrations with the dev profile,
 * which points the service at http://localhost:8091/v1. No provider is called. Run it with the JDK alone:
 *
 * <pre>java tools/demo/ai-stub/AiStub.java [port]</pre>
 *
 * {@code POST /v1/moderate} flags texts containing "badword". {@code POST /v1/generate} (the external job
 * {@code commerce.product-copy}) answers after two seconds with a line of copy for the product's name and made-up
 * token usage; a product name containing "refuse" is answered 400, one containing "busy" 503.
 * {@code POST /v1/images} (the external job {@code commerce.product-image}, decision D50) answers with the photo it was
 * sent in grey, framed for the style ("white" or "studio"), as a JPEG of at most 2048 pixels a side. It listens on this machine only.
 */
public final class AiStub {

    private static final Pattern TEXT = Pattern.compile("\"text\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern PRODUCT_NAME = Pattern.compile("\"productName\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern IMAGE = Pattern.compile("\"image\"\\s*:\\s*\"([A-Za-z0-9+/=]*)\"");
    private static final Pattern STYLE = Pattern.compile("\"style\"\\s*:\\s*\"(white|studio)\"");

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8091;
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/v1/moderate", AiStub::moderate);
        server.createContext("/v1/generate", AiStub::generate);
        server.createContext("/v1/images", AiStub::images);
        server.createContext("/", exchange -> answer(exchange, 404, "{\"error\":\"not found\"}"));
        server.start();
        System.out.println("demo-ai stub on http://localhost:" + port + "/v1 (Ctrl+C to stop)");
    }

    private static void moderate(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("POST")) {
            answer(exchange, 405, "{\"error\":\"POST only\"}");
            return;
        }
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Matcher text = TEXT.matcher(body);
        boolean flagged = text.find() && text.group(1).toLowerCase(Locale.ROOT).contains("badword");
        System.out.println("moderate: " + (flagged ? "flagged" : "accepted"));
        answer(exchange, 200, flagged ? "{\"flagged\":true,\"categories\":[\"harassment\"]}"
            : "{\"flagged\":false,\"categories\":[]}");
    }

    /** Takes its time like a model does; the platform runs it as an external job, outside any transaction. */
    private static void generate(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("POST")) {
            answer(exchange, 405, "{\"error\":\"POST only\"}");
            return;
        }
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Matcher match = PRODUCT_NAME.matcher(body);
        String name = match.find() ? match.group(1) : "this product";
        String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
        System.out.println("generate: " + name + " (Idempotency-Key " + key + ")");
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.contains("refuse")) {
            answer(exchange, 400, "{\"error\":\"refused\"}");
        } else if (lower.contains("busy")) {
            answer(exchange, 503, "{\"error\":\"busy\",\"usage\":{\"promptTokens\":9}}");
        } else {
            answer(exchange, 200, "{\"text\":\"Discover " + name + ": made with care, ready for every day.\","
                + "\"usage\":{\"promptTokens\":" + (20 + name.length()) + ",\"completionTokens\":48}}");
        }
    }

    /** The photo in grey with a frame, as a PNG: something visibly new, made from what was sent. */
    private static void images(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("POST")) {
            answer(exchange, 405, "{\"error\":\"POST only\"}");
            return;
        }
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Matcher image = IMAGE.matcher(body);
        BufferedImage photo = image.find()
            ? ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(image.group(1)))) : null;
        if (photo == null) {
            answer(exchange, 400, "{\"error\":\"no image\"}");
            return;
        }
        Matcher style = STYLE.matcher(body);
        boolean white = style.find() && style.group(1).equals("white");
        // At most 2048 pixels wide or high, as a JPEG: well within the 10 MB the demo's job takes back.
        double scale = Math.min(1.0, 2048.0 / Math.max(photo.getWidth(), photo.getHeight()));
        int width = Math.max(1, (int) (photo.getWidth() * scale));
        int height = Math.max(1, (int) (photo.getHeight() * scale));
        int border = Math.max(8, Math.min(width, height) / 12);
        BufferedImage out = new BufferedImage(width + 2 * border, height + 2 * border, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(white ? Color.WHITE : new Color(40, 40, 48));
        g.fillRect(0, 0, out.getWidth(), out.getHeight());
        BufferedImage grey = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        grey.getGraphics().drawImage(photo, 0, 0, width, height, null);
        g.drawImage(grey, border, border, null);
        g.dispose();
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        ImageIO.write(out, "jpeg", jpeg);
        System.out.println("images: " + photo.getWidth() + "x" + photo.getHeight() + (white ? " white" : " studio"));
        answer(exchange, 200, "{\"image\":\"" + Base64.getEncoder().encodeToString(jpeg.toByteArray())
            + "\",\"usage\":{\"imageTokens\":" + (photo.getWidth() * photo.getHeight() / 750) + "}}");
    }

    private static void answer(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
