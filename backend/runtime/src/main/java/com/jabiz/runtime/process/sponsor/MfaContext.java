package com.jabiz.runtime.process.sponsor;

import com.jabiz.process.ProcessStart;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.security.LoginOutcome;

import java.util.List;

/** Context of {@link SponsorMfaVerifyProcess}: the sign-in context of a user named by id, with the submitted code. */
public class MfaContext extends LoginContext {

    public static final String KEY_LATER_RECORDS = "later_login_records";

    /** No record: a later sign-in replaced the challenge. */
    public static final String STALE = "STALE";
    /** No record: the user has no second factor. */
    public static final String NOT_ENROLLED = "NOT_ENROLLED";
    /** No record: the stored secret cannot be decrypted with the configured key. */
    public static final String UNREADABLE = "UNREADABLE";

    private final String userIdArgument;
    private final Long challengeAttemptNo;
    private volatile String refusal;
    private volatile String usedRecoveryCode;

    public MfaContext(ProcessStart start, SponsorMfaVerifyInput input) {
        // The code travels as the "password" of the sign-in context: taken once, then gone.
        super(start, null, input.mfaCode(), input.source());
        this.userIdArgument = input.userId();
        this.challengeAttemptNo = input.challengeAttemptNo();
    }

    /** The user named by the input (the loaded user may be absent). */
    public String userIdArgument() {
        return userIdArgument;
    }

    /** Attempt number of the login record the challenge followed; null for a step-up. */
    public Long challengeAttemptNo() {
        return challengeAttemptNo;
    }

    /** Whether a later password sign-in (or a completed one) has replaced the challenge. */
    public boolean challengeReplaced() {
        List<EntityInstance> later = list(KEY_LATER_RECORDS);
        return later.stream().map(record -> String.valueOf(record.<Object>get("outcome")))
            .anyMatch(outcome -> outcome.equals(LoginOutcome.SUCCESS.name())
                || outcome.equals(LoginOutcome.MFA_REQUIRED.name())
                || outcome.equals(LoginOutcome.MFA_ENROLLMENT_REQUIRED.name()));
    }

    public void refuse(String refusal) {
        this.refusal = refusal;
    }

    @Override
    protected String refusal() {
        return refusal;
    }

    /** Hash of the recovery code the attempt used, to be removed; null if none. */
    public String usedRecoveryCode() {
        return usedRecoveryCode;
    }

    public void setUsedRecoveryCode(String hash) {
        this.usedRecoveryCode = hash;
    }
}
