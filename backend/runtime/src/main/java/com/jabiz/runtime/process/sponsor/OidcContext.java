package com.jabiz.runtime.process.sponsor;

import com.jabiz.process.ProcessStart;
import com.jabiz.runtime.security.SecurityEntities;

import java.util.List;

/** Context of {@link SponsorOidcSignInProcess}: the sign-in context of the user linked to a provider's subject. */
public class OidcContext extends LoginContext {

    public static final String KEY_IDENTITIES = "identities";

    private final SponsorOidcSignInInput input;

    public OidcContext(ProcessStart start, SponsorOidcSignInInput input) {
        super(start, null, null, input.source());
        this.input = input;
        setFactor(SecurityEntities.FACTOR_OIDC);
    }

    public SponsorOidcSignInInput input() {
        return input;
    }

    /** The linked user's id, if the subject is linked. */
    public List<Object> linkedUserIds() {
        return list(KEY_IDENTITIES).stream().map(identity -> identity.<Object>get("userId")).toList();
    }

    @Override
    protected String identityId() {
        return list(KEY_IDENTITIES).stream().findFirst().map(identity -> String.valueOf(identity.id())).orElse(null);
    }

    @Override
    public boolean secondFactorPassed() {
        return input.secondFactor();
    }
}
