package com.jabiz.app.it.security;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.jabiz.app.commerce.PortalOrders;
import com.jabiz.runtime.event.OutboxDeliverer;
import com.jabiz.runtime.security.SecurityEntities;
import jakarta.mail.BodyPart;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.WebFilter;

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Self-registration, verification and password reset against a local SMTP server (docs/design/10-security.md section
 * 15; decision D36; ROADMAP phase 16b-2). The application's {@code portal} entry registers customers with the role
 * {@value #CUSTOMER}, created here once per test class (each class has its own schema). The mock server has no
 * connections: a test filter sets the client's address from a header, and every test starts from an address of its
 * own, so that the rate limit of one client does not carry over between tests.
 */
abstract class SignUpItSupport extends SecurityItSupport {

    static final String CUSTOMER = "CUSTOMER";
    static final String PORTAL = "portal";
    static final String PASSWORD = "correct horse battery";
    static final String CONNECTION = "X-It-Connection";

    static final Pattern VERIFY_LINK = Pattern.compile(
        "https://app\\.example\\.com/auth/verify-email\\?entry=([a-z0-9-]+)&token=([A-Za-z0-9_-]+)");
    static final Pattern JOIN_LINK = Pattern.compile(
        "https://app\\.example\\.com/auth/join\\?entry=([a-z0-9-]+)&token=([A-Za-z0-9_-]+)");
    static final Pattern RESET_LINK = Pattern.compile(
        "https://app\\.example\\.com/auth/password-reset\\?entry=([a-z0-9-]+)&token=([A-Za-z0-9_-]+)");

    private static final AtomicInteger ADDRESSES = new AtomicInteger();

    @Autowired
    OutboxDeliverer deliverer;

    /** The client address of the current test's requests. */
    String clientAddress;

    abstract GreenMailExtension mail();

    @BeforeEach
    @Override
    protected void client() {
        int n = ADDRESSES.incrementAndGet();
        clientAddress = "198.51." + (n / 250) + "." + (1 + n % 250);
        WebFilter connection = (exchange, chain) -> {
            String address = exchange.getRequest().getHeaders().getFirst(CONNECTION);
            String chosen = address != null ? address : clientAddress;
            return chain.filter(exchange.mutate().request(request -> request
                .remoteAddress(new InetSocketAddress(chosen, 40000))).build());
        };
        client = WebTestClient.bindToApplicationContext(context).webFilter(connection).build();
    }

    @BeforeEach
    void customerRole() {
        if (query("SELECT 1 FROM sec_role_version WHERE role_code = ?", CUSTOMER).isEmpty()) {
            createRole(CUSTOMER, PortalOrders.PERMISSION);
        }
    }

    static String address(String prefix) {
        return unique(prefix).toLowerCase() + "@example.com";
    }

    WebTestClient.ResponseSpec anonymous(String path, Map<String, ?> body) {
        return client.post().uri(path).contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
    }

    WebTestClient.ResponseSpec register(String entry, String email, String password) {
        Map<String, Object> body = new HashMap<>();
        body.put("entry", entry);
        body.put("email", email);
        body.put("password", password);
        return anonymous("/api/auth/register", body);
    }

    WebTestClient.ResponseSpec loginTo(String name, String password, String entry) {
        Map<String, Object> body = new HashMap<>(Map.of("userName", name, "password", password));
        if (entry != null) {
            body.put("entry", entry);
        }
        return anonymous("/api/auth/login", body);
    }

    String refusal(WebTestClient.ResponseSpec response, int status) {
        return ruleCode(response.expectStatus().isEqualTo(status).expectBody(MAP).returnResult().getResponseBody());
    }

    int deliver() {
        return deliverer.deliverPending().block();
    }

    /** Mail received at the address, oldest first. */
    List<MimeMessage> to(String address) {
        return Arrays.stream(mail().getReceivedMessages()).filter(message -> {
            try {
                return message.getAllRecipients()[0].toString().equalsIgnoreCase(address);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).toList();
    }

    /** The token of the link in the latest mail to the address that has one of the pattern. */
    String token(Pattern pattern, String address) {
        List<MimeMessage> received = to(address);
        for (int i = received.size() - 1; i >= 0; i--) {
            Matcher matcher = pattern.matcher(text(received.get(i)));
            if (matcher.find()) {
                return matcher.group(2);
            }
        }
        throw new AssertionError("No link " + pattern + " in the mail to " + address);
    }

    /** The plain text of a mail. */
    static String text(MimeMessage message) {
        try {
            return part(message, "text/plain");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String part(Part part, String type) throws Exception {
        if (part.isMimeType(type)) {
            return String.valueOf(part.getContent());
        }
        if (part.getContent() instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart child = multipart.getBodyPart(i);
                String found = part(child, type);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** The user of an address (current version), or null. */
    static Map<String, Object> userOf(String email) {
        List<Map<String, Object>> rows = query("""
            SELECT DISTINCT ON (user_id) user_id::text AS user_id, user_name, email, verified_email, email_verified_at,
                   password_hash, enabled, is_deleted
            FROM sec_user_version WHERE lower(email) = lower(?) ORDER BY user_id, version_no DESC""", email);
        assertThat(rows).hasSizeLessThanOrEqualTo(1);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** The role codes the user is assigned (current versions). */
    static List<String> rolesOf(String userId) {
        return query("""
            SELECT r.role_code FROM (SELECT DISTINCT ON (user_role_id) role_id, is_deleted FROM sec_user_role_version
                WHERE user_id = ?::uuid ORDER BY user_role_id, version_no DESC) a
            JOIN (SELECT DISTINCT ON (role_id) role_id, role_code FROM sec_role_version
                ORDER BY role_id, version_no DESC) r ON r.role_id = a.role_id
            WHERE NOT a.is_deleted ORDER BY r.role_code""", userId).stream()
            .map(row -> String.valueOf(row.get("role_code"))).toList();
    }

    /** Marks a user's current address verified (the test fixture's process, as an administrator). */
    void verifyEmail(String userId) {
        post("/api/processes/IT_EMAIL_VERIFY/latest", admin(), Map.of("userId", userId)).expectStatus().isOk();
    }

    /** A user created by an administrator, with an address and a password; returns the user id. */
    @SuppressWarnings("unchecked")
    String createUserWithEmail(String name, String email, String password) {
        Map<String, Object> input = new HashMap<>(Map.of("userName", name, "displayName", name, "email", email));
        if (password != null) {
            input.put("password", password);
        }
        Map<String, Object> result = post("/api/processes/SEC_USER_CREATE/latest", admin(), input)
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        return (String) ((Map<String, Object>) result.get("output")).get("userId");
    }

    /** The role id of a code. */
    static String roleId(String code) {
        return String.valueOf(query("SELECT role_id::text AS id FROM sec_role_version WHERE role_code = ?", code)
            .getFirst().get("id"));
    }

    static String userDataset() {
        return SecurityEntities.USER_DATASET;
    }
}
