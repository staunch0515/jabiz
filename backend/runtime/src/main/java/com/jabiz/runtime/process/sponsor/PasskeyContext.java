package com.jabiz.runtime.process.sponsor;

import com.jabiz.process.ProcessStart;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.security.SecurityEntities;

import java.util.List;
import java.util.Optional;

/** Context of {@link SponsorPasskeySignInProcess}: the sign-in of the user whose passkey answered. */
public class PasskeyContext extends LoginContext {

    public static final String KEY_PASSKEY = "passkey";

    private final SponsorPasskeySignInInput input;
    private volatile boolean verified;
    private volatile long signCount;
    private volatile boolean backedUp;

    public PasskeyContext(ProcessStart start, SponsorPasskeySignInInput input) {
        super(start, null, null, input.source());
        this.input = input;
        setFactor(SecurityEntities.FACTOR_PASSKEY);
    }

    public SponsorPasskeySignInInput input() {
        return input;
    }

    /** The passkey of the answer's credential id, if there is one. */
    public Optional<EntityInstance> passkey() {
        return list(KEY_PASSKEY).stream().findFirst();
    }

    /** The passkey's user, when the passkey exists and is the expected user's (if one is expected). */
    public List<Object> passkeyUserIds() {
        return passkey().<Object>map(passkey -> passkey.get("userId"))
            .filter(user -> input.expectedUser() == null || input.expectedUser().equals(String.valueOf(user)))
            .map(List::of).orElse(List.of());
    }

    /** Whether the answer's signature held: then the counter and the last use are recorded. */
    public boolean verified() {
        return verified;
    }

    public void setVerified(long signCount, boolean backedUp) {
        this.verified = true;
        this.signCount = signCount;
        this.backedUp = backedUp;
    }

    public long signCount() {
        return signCount;
    }

    public boolean backedUp() {
        return backedUp;
    }

    /** A user-verifying passkey is a second factor itself (decision D71 item 3). */
    @Override
    public boolean secondFactorPassed() {
        return true;
    }
}
