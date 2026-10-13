package com.jabiz.app.it.security;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetup;
import com.jabiz.runtime.security.SecurityEntities;
import com.jabiz.runtime.test.TestOidcProvider;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A signed-in user links provider accounts to their own account and removes them (docs/design/10-security.md section
 * 12; decision D39 item 2; ROADMAP phase 16f-1): only after proving again who they are (a recent second factor or the
 * current password, a wrong one counting towards the lock), only with a state of their own link, never an account that
 * is someone else's or a second one of a provider, never on an account somebody else may still take over; and never the
 * last way to sign in.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "jabiz.mail.enabled=true", "jabiz.mail.from=jabiz@example.com", "jabiz.mail.base-url=https://app.example.com",
    "spring.mail.host=localhost", "spring.mail.port=3032", SignUpItSupport.ENTRY_PROPERTIES_REGISTRATION,
    SignUpItSupport.ENTRY_PROPERTIES_ADMIN, SignUpItSupport.ENTRY_PROPERTIES_LOOSE})
@Import(SignUpItSupport.FixedTimeLimits.class)
class OidcIdentityLinkIT extends OidcItSupport {

    static final String IDENTITIES = "/api/auth/account/identities";

    @RegisterExtension
    static final GreenMailExtension MAIL = new GreenMailExtension(new ServerSetup(3032, null, ServerSetup.PROTOCOL_SMTP));

    static final TestOidcProvider IDP = new TestOidcProvider();

    @DynamicPropertySource
    static void provider(DynamicPropertyRegistry registry) {
        providers(registry, IDP);
    }

    @AfterAll
    static void stop() {
        IDP.close();
    }

    @Override
    GreenMailExtension mail() {
        return MAIL;
    }

    @Override
    TestOidcProvider idp() {
        return IDP;
    }

    /** A portal customer with a verified address and a password; signed in at the portal. */
    private record Customer(String userId, String email, Map<String, Object> session) {
        String bearer() {
            return bearerOf(session);
        }
    }

    private Customer customer() {
        String email = address("cust");
        String userId = createUserWithEmail(email, email, PASSWORD);
        verifyEmail(userId);
        assign(userId, roleId(CUSTOMER), null);
        return new Customer(userId, email, session(email, PASSWORD, PORTAL));
    }

    private WebTestClient.ResponseSpec startLink(String bearer, String provider, String password) {
        Map<String, Object> body = new HashMap<>();
        body.put("provider", provider);
        if (password != null) {
            body.put("currentPassword", password);
        }
        return authorized("POST", IDENTITIES + "/start", bearer, body);
    }

    private Request linkRequest(String bearer, String provider, String password) {
        return request(startLink(bearer, provider, password).expectStatus().isOk().expectBody(MAP).returnResult()
            .getResponseBody());
    }

    private WebTestClient.ResponseSpec finishLink(String bearer, Request request, String idToken) {
        Map<String, Object> body = new HashMap<>();
        body.put("state", request.state());
        body.put("code", IDP.code(request.challenge(), idToken, request.redirectUri()));
        body.put("binder", request.binder());
        return authorized("POST", IDENTITIES + "/callback", bearer, body);
    }

    private List<Map<String, Object>> identities(String bearer) {
        return authorized("GET", IDENTITIES, bearer, null).expectStatus().isOk().expectBody(LIST).returnResult()
            .getResponseBody();
    }

    private String problem(WebTestClient.ResponseSpec response, int status) {
        return refusal(response, status);
    }

    @Test
    void signedOutCallersLinkNothing() {
        authorized("GET", IDENTITIES, null, null).expectStatus().isUnauthorized();
        startLink(null, OPEN, PASSWORD).expectStatus().isUnauthorized();
        authorized("POST", IDENTITIES + "/callback", null, Map.of("state", "s", "code", "c", "binder", "b"))
            .expectStatus().isUnauthorized();
        authorized("DELETE", IDENTITIES + "/x", null, null).expectStatus().isUnauthorized();
    }

    @Test
    @SuppressWarnings("unchecked")
    void aCustomerLinksAnAccountWithTheCurrentPasswordAndSignsInThroughIt() {
        Customer customer = customer();
        // A password session without a recent second factor: the password is needed.
        assertThat(problem(startLink(customer.bearer(), OPEN, null), 422)).isEqualTo("REAUTHENTICATION_REQUIRED");
        assertThat(problem(startLink(customer.bearer(), OPEN, "wrong horse battery"), 422))
            .isEqualTo("PASSWORD_INCORRECT");
        assertThat(query("SELECT outcome FROM sec_login_record_version WHERE user_id = ?::uuid ORDER BY attempt_no",
            customer.userId())).extracting(row -> row.get("outcome")).containsExactly("SUCCESS", "BAD_CREDENTIALS");
        // A provider not offered to the entry is none.
        startLink(customer.bearer(), "nowhere", PASSWORD).expectStatus().isNotFound();

        String subject = unique("sub");
        Request request = linkRequest(customer.bearer(), OPEN, PASSWORD);
        assertThat(request.redirectUri()).isEqualTo(callbackOf(PORTAL));
        Map<String, Object> linked = finishLink(customer.bearer(), request, verified(request, subject,
            "other@example.org")).expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        assertThat(linked).containsEntry("status", "LINKED").containsEntry("provider", OPEN);
        assertThat(linkedUser(OPEN, subject)).isEqualTo(customer.userId());
        assertThat(identities(customer.bearer())).singleElement().satisfies(identity -> assertThat(identity)
            .containsEntry("provider", OPEN).containsEntry("label", "Label open").containsEntry("kind", "generic")
            .containsEntry("email", "other@example.org").containsKeys("id", "linkedAt"));
        // The user is told.
        deliver();
        assertThat(to(customer.email())).anySatisfy(mail -> assertThat(text(mail)).contains("Label open"));

        // The provider signs them in now, with the portal's roles.
        Request signIn = start(OPEN, PORTAL);
        Map<String, Object> session = callback(signIn, verified(signIn, subject, "other@example.org"))
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        assertThat(session).containsEntry("userId", customer.userId());
        assertThat((List<String>) session.get("roles")).containsExactly(CUSTOMER);
    }

    @Test
    void aRecentSecondFactorIsEnough() {
        Customer customer = customer();
        String recent = TestTokens.bearer(tokens, customer.userId(), "x");
        Request request = linkRequest(recent, OPEN, null);
        // The administration's callback: the token is of the administration's entry.
        assertThat(request.redirectUri()).isEqualTo(TestOidcProvider.REDIRECT_URI);
        finishLink(recent, request, idToken(request, unique("sub"), c -> c)).expectStatus().isOk();
        String stale = TestTokens.withoutMfa(tokens, customer.userId(), "x");
        assertThat(problem(startLink(stale, CLOSED, null), 422)).isEqualTo("REAUTHENTICATION_REQUIRED");
    }

    @Test
    void aStateIsGoodForItsOwnPurposeUserAndEntryOnly() {
        Customer owner = customer();
        Customer other = customer();
        // Another user's link state.
        Request link = linkRequest(owner.bearer(), OPEN, PASSWORD);
        assertThat(problem(finishLink(other.bearer(), link, idToken(link, unique("sub"), c -> c)), 422))
            .isEqualTo("IDENTITY_LINK_FAILED");
        // A link state at the sign-in callback, and a sign-in state at the link callback.
        Request another = linkRequest(owner.bearer(), OPEN, PASSWORD);
        assertThat(refusal(callback(another, idToken(another, unique("sub"), c -> c)), 401)).isEqualTo("LOGIN_FAILED");
        Request signIn = start(OPEN, PORTAL);
        assertThat(problem(finishLink(owner.bearer(), signIn, idToken(signIn, unique("sub"), c -> c)), 422))
            .isEqualTo("IDENTITY_LINK_FAILED");
        // The same user at another entry (the administration's token).
        Request portal = linkRequest(owner.bearer(), OPEN, PASSWORD);
        String admin = TestTokens.bearer(tokens, owner.userId(), "x");
        assertThat(problem(finishLink(admin, portal, idToken(portal, unique("sub"), c -> c)), 422))
            .isEqualTo("IDENTITY_LINK_FAILED");
        assertThat(identities(owner.bearer())).isEmpty();
    }

    @Test
    void anAccountLinksOneUserAndAUserOneAccountOfAProvider() {
        Customer owner = customer();
        Customer other = customer();
        String subject = unique("sub");
        insert(SecurityEntities.USER_IDENTITY_DATASET, Map.of("userId", other.userId(), "provider", OPEN,
            "subject", subject), null);
        Request request = linkRequest(owner.bearer(), OPEN, PASSWORD);
        assertThat(problem(finishLink(owner.bearer(), request, idToken(request, subject, c -> c)), 422))
            .isEqualTo("IDENTITY_IN_USE");
        assertThat(linkedUser(OPEN, subject)).isEqualTo(other.userId());

        String own = unique("sub");
        Request first = linkRequest(owner.bearer(), OPEN, PASSWORD);
        finishLink(owner.bearer(), first, idToken(first, own, c -> c)).expectStatus().isOk();
        // The same account again: nothing changes.
        Request again = linkRequest(owner.bearer(), OPEN, PASSWORD);
        assertThat(finishLink(owner.bearer(), again, idToken(again, own, c -> c)).expectStatus().isOk()
            .expectBody(MAP).returnResult().getResponseBody()).containsEntry("status", "ALREADY_LINKED");
        // Another account of the same provider.
        Request second = linkRequest(owner.bearer(), OPEN, PASSWORD);
        assertThat(problem(finishLink(owner.bearer(), second, idToken(second, unique("sub"), c -> c)), 422))
            .isEqualTo("IDENTITY_PROVIDER_LINKED");
        assertThat(identities(owner.bearer())).hasSize(1);
    }

    @Test
    void anAccountSomebodyElseMayTakeOverLinksNothing() {
        String email = address("pre");
        register(PORTAL, email, PASSWORD).expectStatus().isAccepted();
        Map<String, Object> session = session(email, PASSWORD, LOOSE);
        Request request = linkRequest(bearerOf(session), OPEN, PASSWORD);
        assertThat(request.redirectUri()).isEqualTo(callbackOf(LOOSE));
        String subject = unique("sub");
        assertThat(problem(finishLink(bearerOf(session), request, idToken(request, subject, c -> c)), 422))
            .isEqualTo("IDENTITY_LINK_UNAVAILABLE");
        assertThat(linkedUser(OPEN, subject)).isNull();
    }

    @Test
    void theLastWayToSignInStaysAndRemovingALinkEndsItsSessions() {
        // An account a provider opened: no password, one link.
        String email = address("only");
        String subject = unique("sub");
        Request opened = start(OPEN, PORTAL);
        Map<String, Object> session = callback(opened, verified(opened, subject, email)).expectStatus().isOk()
            .expectBody(MAP).returnResult().getResponseBody();
        String id = (String) identities(bearerOf(session)).getFirst().get("id");
        assertThat(problem(authorized("DELETE", IDENTITIES + "/" + id, bearerOf(session), null), 422))
            .isEqualTo("LAST_SIGN_IN_METHOD");

        // A customer with a password removes a link; its sessions end at the next refresh.
        Customer customer = customer();
        String own = unique("sub");
        Request request = linkRequest(customer.bearer(), CLOSED, PASSWORD);
        finishLink(customer.bearer(), request, idToken(request, own, c -> c)).expectStatus().isOk();
        Request signIn = start(CLOSED, PORTAL);
        Map<String, Object> through = callback(signIn, idToken(signIn, own, c -> c)).expectStatus().isOk()
            .expectBody(MAP).returnResult().getResponseBody();
        String link = (String) identities(customer.bearer()).getFirst().get("id");
        // Somebody else's id, or none at all, is not found.
        authorized("DELETE", IDENTITIES + "/" + link, bearerOf(session), null).expectStatus().isNotFound();
        authorized("DELETE", IDENTITIES + "/" + id, customer.bearer(), null).expectStatus().isNotFound();
        authorized("DELETE", IDENTITIES + "/" + link, customer.bearer(), null).expectStatus().isNoContent();
        assertThat(identities(customer.bearer())).isEmpty();
        assertThat(refusal(post("/api/auth/refresh", null, Map.of("refreshToken", through.get("refreshToken"),
            "entry", PORTAL)), 401)).isEqualTo("INVALID_REFRESH_TOKEN");
        // The password session goes on.
        post("/api/auth/refresh", null, Map.of("refreshToken", customer.session().get("refreshToken"),
            "entry", PORTAL)).expectStatus().isOk();
    }

    @Test
    void theProcessesRunOnlyFromTheirEndpoints() {
        Customer customer = customer();
        String user = customer.userId();
        Map<String, Map<String, Object>> inputs = Map.of(
            "SEC_IDENTITY_LINK_BEGIN", Map.of("userId", user, "currentPassword", PASSWORD),
            "SEC_IDENTITY_LINK_SELF", Map.of("userId", user, "provider", OPEN, "subject", unique("sub")),
            "SEC_IDENTITY_UNLINK_SELF", Map.of("userId", user, "identityId", user),
            "SPONSOR_OIDC_SIGN_UP", Map.of("entry", PORTAL, "provider", OPEN, "subject", unique("sub"),
                "email", address("api")));
        inputs.forEach((process, input) -> assertThat(problem(post("/api/processes/" + process + "/latest", admin(),
            input), 403)).as(process).isEqualTo("PROCESS_INTERNAL"));
        assertThat(identities(customer.bearer())).isEmpty();
    }
}
