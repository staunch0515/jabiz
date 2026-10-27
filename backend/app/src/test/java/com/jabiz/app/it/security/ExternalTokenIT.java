package com.jabiz.app.it.security;

import tools.jackson.databind.json.JsonMapper;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetup;
import com.jabiz.app.it.fixture.SqlStatementLog;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.security.ExternalTokenProvider;
import com.jabiz.runtime.security.ExternalTokenProviders;
import com.jabiz.runtime.security.ExternalTokens;
import com.jabiz.runtime.test.MutableClock;
import com.jabiz.runtime.test.TestTokens;
import com.jabiz.runtime.security.VerifyExternalToken;
import com.jabiz.security.ExternalTokenRules;
import com.jabiz.security.Sensitive;
import com.jabiz.security.SignInDecision;
import com.jabiz.security.SignInGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.reactive.server.WebTestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Signing in with a token another platform signed (decision D59; docs/design/10-security.md section 15.12; ROADMAP
 * phase 17b): only at an entry that takes the provider's tokens; checked whole (signature, times, audience, one allowed
 * tenant) and used once; the identity is the person within the tenant, so the same person in two tenants is two users;
 * an account is opened only where the provider may and the entry lets people register; a guard still refuses. A
 * process verifies a token of its operator with {@link VerifyExternalToken}. The page of an SPA the provider embeds may
 * be framed by the tenant that opened it, and by nobody else.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {"it.sql-log.enabled=true",
    SignUpItSupport.ENTRY_PROPERTIES_REGISTRATION, SignUpItSupport.ENTRY_PROPERTIES_ADMIN,
    SignUpItSupport.ENTRY_PROPERTIES_LOOSE,
    "jabiz.security.external-tokens.shop.secrets[0]=" + ExternalTokenIT.OLD_SECRET,
    "jabiz.security.external-tokens.shop.secrets[1]=" + ExternalTokenIT.SECRET,
    "jabiz.security.external-tokens.shop.audience=" + ExternalTokenIT.AUDIENCE,
    "jabiz.security.external-tokens.shop.tenant-pattern=[a-z0-9][a-z0-9-]*\\.shop\\.example",
    "jabiz.security.external-tokens.shop.issuer-format=https://{tenant}/admin",
    "jabiz.security.external-tokens.shop.destination-format=https://{tenant}",
    "jabiz.security.external-tokens.shop.auto-provision=true",
    "jabiz.security.external-tokens.shop.frame-ancestors[0]=https://admin.shop.example",
    "jabiz.security.external-tokens.shut.secrets[0]=" + ExternalTokenIT.SECRET,
    "jabiz.security.external-tokens.shut.audience=" + ExternalTokenIT.AUDIENCE,
    "jabiz.security.external-tokens.shut.tenant-pattern=[a-z0-9][a-z0-9-]*\\.shop\\.example",
    "jabiz.security.external-tokens.shut.issuer-format=https://{tenant}/admin",
    "jabiz.security.external-tokens.shut.destination-format=https://{tenant}",
    "jabiz.security.entries.shop.accepted-roles=CUSTOMER",
    "jabiz.security.entries.shop.self-registration=true",
    "jabiz.security.entries.shop.registration-roles=CUSTOMER",
    "jabiz.security.entries.shop.external-token=shop",
    "jabiz.security.entries.shut.accepted-roles=CUSTOMER",
    "jabiz.security.entries.shut.self-registration=true",
    "jabiz.security.entries.shut.registration-roles=CUSTOMER",
    "jabiz.security.entries.shut.external-token=shut",
    "jabiz.web.spa[0].path=/", "jabiz.web.spa[1].path=/portal", "jabiz.web.spa[1].entry=portal",
    "jabiz.web.spa[1].pwa=on", "jabiz.web.spa[2].path=/shop", "jabiz.web.spa[2].entry=shop",
    "jabiz.web.spa[2].embedding=shop"})
@Import({SignUpItSupport.FixedTimeLimits.class, ExternalTokenIT.Processes.class})
class ExternalTokenIT extends SignUpItSupport {

    static final String SECRET = "it-external-token-secret-0123456789abcdef";
    static final String OLD_SECRET = "it-external-token-old-secret-0123456789ab";
    static final String AUDIENCE = "it-app-key";
    static final String SHOP = "shop";
    static final String PATH = "/api/auth/external-token";
    static final String VERIFY = "IT_EXTERNAL_TOKEN_VERIFY";
    static final JsonMapper JSON = JsonMapper.builder().build();

    @RegisterExtension
    static final GreenMailExtension MAIL = new GreenMailExtension(new ServerSetup(3031, null, ServerSetup.PROTOCOL_SMTP));

    @Autowired
    Clock clock;

    @Autowired
    ExternalTokenProviders providers;

    @Autowired
    ExternalTokens externalTokens;

    /** The application's time (fixed in tests): what the tokens' times are checked against. */
    static volatile Instant now;

    @BeforeEach
    void now() {
        now = clock.instant();
    }

    @Override
    GreenMailExtension mail() {
        return MAIL;
    }

    /** The token of {@code subject} in {@code tenant}, valid for a minute from now, changed by {@code change}. */
    static String token(String tenant, String subject, Consumer<Map<String, Object>> change) {
        long now = ExternalTokenIT.now.getEpochSecond();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", "https://" + tenant + "/admin");
        claims.put("dest", "https://" + tenant);
        claims.put("aud", AUDIENCE);
        claims.put("sub", subject);
        claims.put("exp", now + 60);
        claims.put("nbf", now - 1);
        claims.put("iat", now - 1);
        claims.put("jti", UUID.randomUUID().toString());
        change.accept(claims);
        return signed(Map.of("alg", "HS256", "typ", "JWT"), claims, SECRET);
    }

    static String token(String tenant, String subject) {
        return token(tenant, subject, c -> { });
    }

    static String signed(Map<String, Object> header, Map<String, Object> claims, String secret) {
        try {
            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            String input = encoder.encodeToString(JSON.writeValueAsBytes(header)) + "."
                + encoder.encodeToString(JSON.writeValueAsBytes(claims));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return input + "." + encoder.encodeToString(mac.doFinal(input.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    WebTestClient.ResponseSpec tokenSignIn(String token, String entry) {
        Map<String, Object> body = new HashMap<>();
        body.put("token", token);
        body.put("entry", entry);
        return anonymous(PATH, body);
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> session(String token, String entry) {
        return tokenSignIn(token, entry).expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
    }

    static String tenant(String prefix) {
        return unique(prefix).toLowerCase() + ".shop.example";
    }

    static String linkedUser(String provider, String subject) {
        List<Map<String, Object>> rows = query("SELECT user_id::text AS user_id FROM sec_user_identity_version v"
            + " WHERE provider = ? AND subject = ?", "ext:" + provider, subject);
        return rows.isEmpty() ? null : (String) rows.getFirst().get("user_id");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aTokenOfATenantOpensAnAccountThatSignsInAgain() {
        String tenant = tenant("demo");
        Map<String, Object> first = session(token(tenant, "42"), SHOP);
        assertThat(first).containsEntry("status", "SIGNED_IN");
        assertThat((List<String>) first.get("roles")).containsExactly(CUSTOMER);
        String userId = (String) first.get("userId");
        assertThat(linkedUser(SHOP, tenant + "/42")).isEqualTo(userId);
        // No address, no password; named after the identity.
        assertThat(query("SELECT user_name, email, password_hash FROM sec_user_version WHERE user_id = ?::uuid",
            userId)).singleElement().satisfies(row -> assertThat(row).containsEntry("user_name", tenant + "/42")
            .containsEntry("email", null).containsEntry("password_hash", null));
        assertThat(rolesOf(userId)).containsExactly(CUSTOMER);
        assertThat(query("SELECT outcome, factor, entry FROM sec_login_record_version WHERE user_id = ?::uuid",
            userId)).singleElement().satisfies(row -> assertThat(row).containsEntry("outcome", "SUCCESS")
            .containsEntry("factor", "EXTERNAL_TOKEN").containsEntry("entry", SHOP));
        assertThat(query("SELECT 1 FROM op_process WHERE process_name = 'SPONSOR_EXTERNAL_TOKEN_SIGN_UP'"))
            .isNotEmpty();

        // The next token signs the same user in; the session refreshes like any other.
        assertThat(session(token(tenant, "42"), SHOP)).containsEntry("userId", userId);
        post("/api/auth/refresh", null, Map.of("refreshToken", first.get("refreshToken"), "entry", SHOP))
            .expectStatus().isOk();
        // The same person in another tenant, and another person in this one, are other users.
        assertThat(session(token(tenant("other"), "42"), SHOP).get("userId")).isNotEqualTo(userId);
        assertThat(session(token(tenant, "43"), SHOP).get("userId")).isNotEqualTo(userId);
        // A secret being replaced signs too.
        Map<String, Object> claims = new LinkedHashMap<>(Map.of("iss", "https://" + tenant + "/admin", "dest",
            "https://" + tenant, "aud", AUDIENCE, "sub", "42", "exp", now.getEpochSecond() + 60, "jti",
            UUID.randomUUID().toString()));
        assertThat(session(signed(Map.of("alg", "HS256"), claims, OLD_SECRET), SHOP)).containsEntry("userId", userId);
    }

    @Test
    void aTokenIsUsedOnce() {
        String token = token(tenant("once"), "1");
        tokenSignIn(token, SHOP).expectStatus().isOk();
        assertThat(refusal(tokenSignIn(token, SHOP), 401)).isEqualTo("LOGIN_FAILED");
    }

    @Test
    void aTokenCheckedJustBeforeItExpiresIsStillUsedOnlyOnce() {
        // Checked before its last moment (exp + skew), used after it: the clean-up of expired uses must not drop
        // the first use and let the second in (phase 17e).
        ExternalTokenProvider provider = providers.find(SHOP).orElseThrow();
        String token = token(tenant("edge"), "1", c -> c.put("exp", now.getEpochSecond() + 1));
        tokenSignIn(token, SHOP).expectStatus().isOk();
        ExternalTokenRules.Verified replay = externalTokens.verify(provider, token);
        MutableClock moving = (MutableClock) clock;
        Instant before = moving.instant();
        moving.advance(Duration.ofSeconds(1).plus(provider.skew()).plusMillis(1));
        try {
            assertThatThrownBy(() -> externalTokens.useOnce(provider, replay).block())
                .isInstanceOf(ExternalTokenRules.RefusedException.class);
        } finally {
            // The other tests run on the fixed time.
            moving.set(before);
        }
        assertThat(query("SELECT 1 FROM sec_external_token_use WHERE token_id = ?",
            String.valueOf(replay.tokenId()))).hasSize(1);
    }

    @Test
    void aServerFailureIsNoRefusedToken() {
        // A failure of the server is a 5xx the client may retry, not 401 as for a bad token (phase 17e).
        execute("ALTER TABLE sec_external_token_use RENAME TO sec_external_token_use_away");
        try {
            tokenSignIn(token(tenant("down"), "1"), SHOP).expectStatus().is5xxServerError();
        } finally {
            execute("ALTER TABLE sec_external_token_use_away RENAME TO sec_external_token_use");
        }
    }

    @Test
    void everyBadTokenIsRefusedAlike() {
        String tenant = tenant("bad");
        long now = ExternalTokenIT.now.getEpochSecond();
        List<String> refused = List.of(
            token(tenant, "1", c -> c.put("exp", now - 30)),
            token(tenant, "1", c -> c.put("nbf", now + 60)),
            token(tenant, "1", c -> c.put("aud", "another-app")),
            token(tenant, "1", c -> c.put("iss", "https://evil.example/admin")),
            token(tenant, "1", c -> c.put("dest", "https://" + tenant("elsewhere"))),
            token("evil.example", "1"),
            token(tenant, "1", c -> c.remove("jti")),
            token(tenant, "1", c -> c.remove("sub")),
            signed(Map.of("alg", "HS256"), Map.of("sub", "1"), "another-secret-of-thirty-two-chars-x"),
            signed(Map.of("alg", "none"), Map.of("sub", "1"), SECRET),
            token(tenant, "1").replaceFirst("\\.[^.]+$", ".AAAA"),
            "not-a-token",
            "a".repeat(5000));
        for (String token : refused) {
            assertThat(refusal(tokenSignIn(token, SHOP), 401)).as(token.length() > 80 ? token.substring(0, 80) : token)
                .isEqualTo("LOGIN_FAILED");
        }
        assertThat(query("SELECT 1 FROM sec_user_identity_version WHERE subject LIKE ?", tenant + "%")).isEmpty();
        // Missing parts.
        assertThat(refusal(anonymous(PATH, Map.of("entry", SHOP)), 401)).isEqualTo("LOGIN_FAILED");
        assertThat(refusal(anonymous(PATH, Map.of("token", token(tenant, "1"))), 401)).isEqualTo("LOGIN_FAILED");
    }

    @Test
    void onlyAnEntryThatTakesTheProvidersTokensSignsIn() {
        String tenant = tenant("entries");
        for (String entry : List.of(PORTAL, "admin", LOOSE, "nowhere")) {
            assertThat(refusal(tokenSignIn(token(tenant, "1"), entry), 401)).as(entry).isEqualTo("LOGIN_FAILED");
        }
        // A provider that opens no accounts signs in only whom somebody linked.
        assertThat(refusal(tokenSignIn(token(tenant, "1"), "shut"), 401)).isEqualTo("LOGIN_FAILED");
        assertThat(linkedUser("shut", tenant + "/1")).isNull();
        String userId = createUserWithEmail(unique("linked"), address("linked"), PASSWORD);
        assign(userId, roleId(CUSTOMER), null);
        insert(com.jabiz.runtime.security.SecurityEntities.USER_IDENTITY_DATASET, Map.of("userId", userId,
            "provider", "ext:shut", "subject", tenant + "/1"), null);
        assertThat(session(token(tenant, "1"), "shut")).containsEntry("userId", userId);
    }

    @Test
    void aGuardsRefusalStays403() {
        // The sign-in guard refuses the person: 403 SIGN_IN_REFUSED as at any sign-in, not 401 (phase 17e).
        assertThat(refusal(tokenSignIn(token(tenant("guard"), Processes.GUARDED), SHOP), 403))
            .isEqualTo("SIGN_IN_REFUSED");
    }

    @Test
    void aDisabledUserIsRefused() {
        String tenant = tenant("disabled");
        String userId = (String) session(token(tenant, "1"), SHOP).get("userId");
        updateUser(userId, Map.of("enabled", false));
        assertThat(refusal(tokenSignIn(token(tenant, "1"), SHOP), 401)).isEqualTo("LOGIN_FAILED");
        // Nothing new is opened for the identity.
        assertThat(query("SELECT 1 FROM sec_user_identity_version WHERE subject = ?", tenant + "/1")).hasSize(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aProcessVerifiesATokenOfItsOperator() {
        String tenant = tenant("verify");
        String userId = (String) session(token(tenant, "7"), SHOP).get("userId");
        String bearer = TestTokens.bearer(tokens, userId, "it.external.verify");
        String token = token(tenant, "7");
        Map<String, Object> result = post("/api/processes/" + VERIFY + "/latest", bearer, Map.of("sessionToken", token))
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        assertThat((Map<String, Object>) result.get("output")).containsEntry("tenant", tenant).containsEntry("subject", "7");
        // Not used up: the platform that signed it judges reuse. It still signs in.
        tokenSignIn(token, SHOP).expectStatus().isOk();
        // The token is kept out of the record.
        assertThat(query("SELECT input_summary FROM op_process WHERE process_name = ?", VERIFY)).allSatisfy(row ->
            assertThat(String.valueOf(row.get("input_summary"))).doesNotContain(token.substring(0, 20)));

        // Somebody else's token, an unknown identity or a bad token: 403 alike, without the reason (phase 17e).
        String others = token(tenant, "8");
        session(others, SHOP);
        List<String> details = new ArrayList<>();
        for (String refused : List.of(token(tenant, "8"), token(tenant, "9"), token(tenant, "7", c -> c.put("aud",
            "x")), token(tenant, "7", c -> c.put("exp", now.getEpochSecond() - 60)), "not-a-token")) {
            details.add(String.valueOf(post("/api/processes/" + VERIFY + "/latest", bearer, Map.of("sessionToken",
                refused)).expectStatus().isForbidden().expectBody(MAP).returnResult().getResponseBody().get("detail")));
        }
        assertThat(details).containsOnly("External token refused");
    }

    @Test
    void theEmbeddedPagesAreFramedByTheTenantThatOpenedThemOnly() {
        String tenant = tenant("frame");
        WebTestClient.ResponseSpec framed = client.get().uri("/shop/settings?shop=" + tenant).exchange();
        framed.expectHeader().valueEquals("Content-Security-Policy", JabizPolicy.framed(tenant))
            .expectHeader().doesNotExist("X-Frame-Options");
        client.get().uri("/shop/settings?shop=evil.example").exchange()
            .expectHeader().valueEquals("X-Frame-Options", "DENY")
            .expectHeader().valueEquals("Content-Security-Policy",
                com.jabiz.runtime.web.JabizWebProperties.DEFAULT_CONTENT_SECURITY_POLICY);
        client.get().uri("/portal/orders?shop=" + tenant).exchange()
            .expectHeader().valueEquals("X-Frame-Options", "DENY");
    }

    /** The default policy with the tenant's frame-ancestors. */
    static final class JabizPolicy {
        static String framed(String tenant) {
            return "default-src 'self'; img-src 'self' blob: data:; style-src 'self' 'unsafe-inline'; "
                + "base-uri 'self'; object-src 'none'; form-action 'self'; worker-src 'self'; manifest-src 'self'; "
                + "frame-ancestors https://" + tenant + " https://admin.shop.example";
        }
    }

    record VerifyInput(@Sensitive String sessionToken) {
        @Override
        public String toString() {
            return "VerifyInput[sessionToken=***]";
        }
    }

    record VerifyOutput(String tenant, String subject) {}

    @TestConfiguration
    static class Processes {

        /** The subject the test guard refuses. */
        static final String GUARDED = "guarded";

        @Bean
        SignInGuard itExternalTokenGuard() {
            return attempt -> attempt.userName() != null && attempt.userName().endsWith("/" + GUARDED)
                ? SignInDecision.refuse("guarded") : SignInDecision.ALLOW;
        }

        @Bean
        ProcessDefinition<VerifyInput, VerifyOutput, ProcessContext> itExternalTokenVerify() {
            return ProcessDefinition.define(VERIFY, 1, VerifyInput.class, VerifyOutput.class, ProcessContext.class,
                pb -> pb
                    .permissions("it.external.verify")
                    .contextFactory((start, input) -> {
                        ProcessContext ctx = new ProcessContext(start);
                        ctx.put("in", input);
                        return ctx;
                    })
                    .steps(VerifyExternalToken.of(SHOP, ctx -> ctx.get("in", VerifyInput.class).sessionToken(),
                        "verified"))
                    // A second verification in the same process (its steps are named apart).
                    .steps(VerifyExternalToken.of(SHOP, ctx -> ctx.get("in", VerifyInput.class).sessionToken(),
                        "again"))
                    .outputMapper(ctx -> {
                        VerifyExternalToken.Verified verified = ctx.get("verified", VerifyExternalToken.Verified.class);
                        return new VerifyOutput(verified.tenant(), verified.subject());
                    }));
        }
    }

    @SuppressWarnings("unused")
    private static final Class<?> SQL_LOG = SqlStatementLog.class;
}
