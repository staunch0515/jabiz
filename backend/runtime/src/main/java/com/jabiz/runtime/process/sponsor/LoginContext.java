package com.jabiz.runtime.process.sponsor;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessStart;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.security.Rbac;
import com.jabiz.security.LoginAttemptPolicy;
import com.jabiz.security.LoginOutcome;

import java.util.List;
import java.util.Optional;

/**
 * Context of the sign-in process. It offers typed access to the values that steps hand over to each other, instead
 * of relying on string keys at every use site.
 */
public class LoginContext extends ProcessContext {

    public static final String KEY_USERS = "users";
    public static final String KEY_LATEST_RECORD = "latest_login_record";
    public static final String KEY_ASSIGNMENTS = "role_assignments";
    public static final String KEY_ROLES = "roles";
    public static final String KEY_ROLE_PERMISSIONS = "role_permissions";
    public static final String KEY_MFA = "mfa";

    private final String userName;
    /** Cleared by the authentication step once checked, so that nothing later can see it. */
    private volatile String password;
    private volatile LoginOutcome outcome;
    private volatile Rbac.Access access;
    private volatile Object loginRecordId;
    private volatile long attemptNo;
    private volatile String factor = com.jabiz.runtime.security.SecurityEntities.FACTOR_PASSWORD;
    private volatile Long acceptedMfaStep;

    public LoginContext(ProcessStart start, SponsorSignInInput input) {
        this(start, input.userName(), input.password());
    }

    protected LoginContext(ProcessStart start, String userName, String password) {
        super(start);
        this.userName = userName;
        this.password = password;
    }

    /** The name signed in with, or the loaded user's name when the attempt names the user by id. */
    public String userName() {
        return userName != null ? userName : user().<String>map(u -> u.get("userName")).orElse(null);
    }

    /** The submitted password, once: later calls return null. */
    public String takePassword() {
        String taken = password;
        password = null;
        return taken;
    }

    /** The user of that name, if there is one. */
    @SuppressWarnings("unchecked")
    public Optional<EntityInstance> user() {
        List<EntityInstance> users = (List<EntityInstance>) get(KEY_USERS);
        return users == null || users.isEmpty() ? Optional.empty() : Optional.of(users.getFirst());
    }

    public Object userId() {
        return user().map(EntityInstance::id).orElse(null);
    }

    /** Counters of the user's latest login record. */
    @SuppressWarnings("unchecked")
    public LoginAttemptPolicy.State latestState() {
        List<EntityInstance> records = (List<EntityInstance>) get(KEY_LATEST_RECORD);
        return Rbac.state(records == null || records.isEmpty() ? null : records.getFirst());
    }

    /** The last TOTP step accepted before this attempt; -1 for none. */
    @SuppressWarnings("unchecked")
    public long latestMfaStep() {
        List<EntityInstance> records = (List<EntityInstance>) get(KEY_LATEST_RECORD);
        return Rbac.mfaStep(records == null || records.isEmpty() ? null : records.getFirst());
    }

    @SuppressWarnings("unchecked")
    public List<EntityInstance> list(String key) {
        List<EntityInstance> values = (List<EntityInstance>) get(key);
        return values == null ? List.of() : values;
    }

    /** Null while undecided, and for names of no user. */
    public LoginOutcome outcome() {
        return outcome;
    }

    public void setOutcome(LoginOutcome outcome) {
        this.outcome = outcome;
    }

    public Rbac.Access access() {
        return access;
    }

    public void setAccess(Rbac.Access access) {
        this.access = access;
    }

    public void setLoginRecordId(Object loginRecordId) {
        this.loginRecordId = loginRecordId;
    }

    /** Attempt number of the login record this attempt registered. */
    public long attemptNo() {
        return attemptNo;
    }

    public void setAttemptNo(long attemptNo) {
        this.attemptNo = attemptNo;
    }

    /** What the attempt was checked with (docs/design/10-security.md section 9). */
    public String factor() {
        return factor;
    }

    public void setFactor(String factor) {
        this.factor = factor;
    }

    /** The TOTP step this attempt accepted, or null: the record then carries the previous one. */
    public Long acceptedMfaStep() {
        return acceptedMfaStep;
    }

    public void setAcceptedMfaStep(long step) {
        this.acceptedMfaStep = step;
    }

    /** The user's confirmed second factor, if any. */
    public Optional<EntityInstance> confirmedMfa() {
        return list(KEY_MFA).stream().filter(mfa -> Boolean.TRUE.equals(mfa.get("confirmed"))).findFirst();
    }

    public SponsorSignInOutput output() {
        String recordId = loginRecordId == null ? null : String.valueOf(loginRecordId);
        if (outcome == LoginOutcome.MFA_REQUIRED || outcome == LoginOutcome.MFA_ENROLLMENT_REQUIRED) {
            // The second step needs to know whose attempt it continues; roles come only with the second factor.
            return new SponsorSignInOutput(outcome, String.valueOf(user().orElseThrow().id()), null, null, null,
                recordId, attemptNo, null, identityId());
        }
        if (outcome != LoginOutcome.SUCCESS) {
            return new SponsorSignInOutput(outcome == null ? LoginOutcome.BAD_CREDENTIALS : outcome, null, null,
                null, null, recordId, null, refusal(), null);
        }
        EntityInstance user = user().orElseThrow();
        return new SponsorSignInOutput(outcome, String.valueOf(user.id()), user.get("tenantId"),
            access.roles().stream().sorted().toList(), access.permissions().stream().sorted().toList(),
            recordId, attemptNo, null, identityId());
    }

    /** The provider account the attempt came through; null for passwords. */
    protected String identityId() {
        return null;
    }

    /** Whether the attempt itself passed a second factor (an identity provider's); false for passwords. */
    public boolean secondFactorPassed() {
        return false;
    }

    /** Why an attempt left no login record, when the caller answers differently for it; null by default. */
    protected String refusal() {
        return null;
    }
}
