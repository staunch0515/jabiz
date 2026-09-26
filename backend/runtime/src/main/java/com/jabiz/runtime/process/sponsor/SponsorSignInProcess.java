package com.jabiz.runtime.process.sponsor;

import com.jabiz.process.ProcessDefinition;

/**
 * Sponsor user sign-in: verify credentials, check the role, create a login audit record.
 *
 * Handlers are referenced by class and each step's metadata by its own record type, so a
 * misspelled handler or a metadata type its handler does not accept fails at compile time.
 */
public final class SponsorSignInProcess {

    public static final ProcessDefinition<SponsorSignInInput, SponsorSignInOutput, LoginContext> DEFINITION =
        ProcessDefinition.define("SPONSOR_SIGN_IN", 1,
            SponsorSignInInput.class, SponsorSignInOutput.class, LoginContext.class, pb -> pb
                .description("Sponsor user sign-in: verifies credentials, checks role validity and "
                    + "creates a login audit record.")
                .permissions("sponsor.sign-in")
                .contextFactory((start, input) -> {
                    LoginContext ctx = new LoginContext(start);
                    ctx.put(LoginContext.INPUT_USERNAME_OR_EMAIL, input.usernameOrEmail());
                    ctx.put(LoginContext.INPUT_PASSWORD, input.password());
                    return ctx;
                })
                .outputMapper(ctx -> new SponsorSignInOutput(
                    ctx.getAuthenticatedUser().userId(), ctx.getLoginRecordId()))

                .step("Authentication & User Load", AuthenticationHandler.class,
                    new AuthenticationMetadata(
                        LoginContext.INPUT_USERNAME_OR_EMAIL,
                        LoginContext.INPUT_PASSWORD,
                        "USER_V1",
                        LoginContext.KEY_AUTHENTICATED_USER))

                .step("Role and Access Check", RoleAccessHandler.class,
                    new RoleAccessMetadata(
                        LoginContext.KEY_AUTHENTICATED_USER,
                        "SPONSOR_ROLE_V1",
                        "HAS_ROLE"))

                .step("Create Login Record & Setup Context", LoginRecordCreationHandler.class,
                    new LoginRecordMetadata(
                        LoginContext.KEY_AUTHENTICATED_USER,
                        "LOGIN_RECORD_V1",
                        "SPONSOR_ROLE_V1",
                        "CREATE")));

    private SponsorSignInProcess() {}
}
