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

    private final String userName;
    /** Cleared by the authentication step once checked, so that nothing later can see it. */
    private volatile String password;
    private volatile LoginOutcome outcome;
    private volatile Rbac.Access access;
    private volatile Object loginRecordId;

    public LoginContext(ProcessStart start, SponsorSignInInput input) {
        super(start);
        this.userName = input.userName();
        this.password = input.password();
    }

    public String userName() {
        return userName;
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

    public SponsorSignInOutput output() {
        if (outcome != LoginOutcome.SUCCESS) {
            return new SponsorSignInOutput(outcome == null ? LoginOutcome.BAD_CREDENTIALS : outcome, null, null,
                null, null, loginRecordId == null ? null : String.valueOf(loginRecordId));
        }
        EntityInstance user = user().orElseThrow();
        return new SponsorSignInOutput(outcome, String.valueOf(user.id()), user.get("tenantId"),
            access.roles().stream().sorted().toList(), access.permissions().stream().sorted().toList(),
            String.valueOf(loginRecordId));
    }
}
