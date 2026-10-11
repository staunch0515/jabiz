package com.jabiz.runtime.security;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessStart;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.mail.MailTokens;
import com.jabiz.runtime.process.sponsor.SignInSource;

import java.util.List;
import java.util.Map;

/**
 * Context of the account processes of {@link SignUpProcesses}: what the request said (entry, address, name, language,
 * the token of a mailed link, where it came from) and typed access to what the steps hand over. The password travels
 * under {@link SignUpProcesses#PASSWORD} only until the hashing step removes it.
 */
public class AccountContext extends ProcessContext {

    private final String entry;
    private final String email;
    private final String displayName;
    private final String locale;
    private final String userId;
    private final String token;
    private final SignInSource source;

    AccountContext(ProcessStart start, String entry, String email, String displayName, String locale, String userId,
        String token, SignInSource source) {
        super(start);
        this.entry = entry;
        this.email = email == null ? null : email.strip();
        this.displayName = displayName;
        this.locale = locale;
        this.userId = userId;
        this.token = token;
        this.source = source == null ? SignInSource.NONE : source;
    }

    /**
     * The sign-in entry: as the request named it, or for a mailed link the one the mail was sent for (its
     * parameters); null when neither is known.
     */
    public String entryName() {
        if (entry != null) {
            return entry;
        }
        Map<String, String> params = mailParams();
        return params.get(AuthMailTemplates.ENTRY);
    }

    /** The address the request is about, as given (without surrounding blanks); null for none. */
    public String email() {
        return email;
    }

    public String displayName() {
        return displayName;
    }

    /** The language asked for, one of the platform's; null for none. */
    public String locale() {
        return locale;
    }

    /** The user the request is about, by id (a signed-in user's own request); null for none. */
    public String userId() {
        return userId;
    }

    /** The token of a mailed link; null for none. */
    public String token() {
        return token;
    }

    /** Where the request came from, for login records. */
    public SignInSource source() {
        return source;
    }

    /** The used token of a mailed link, once accepted; null otherwise. */
    public MailTokens.Consumed consumed() {
        return get(SignUpProcesses.CONSUMED) instanceof MailTokens.Consumed consumed ? consumed : null;
    }

    /** The parameters of the mail whose link was used; empty before they are read or without one. */
    @SuppressWarnings("unchecked")
    public Map<String, String> mailParams() {
        Object params = get(SignUpProcesses.MAIL_PARAMS);
        return params instanceof Map<?, ?> map ? (Map<String, String>) map : Map.of();
    }

    /** The one user found (by address or id); null for none. */
    public EntityInstance user() {
        return LoadUserByEmail.only(this, SignUpProcesses.USERS);
    }

    @SuppressWarnings("unchecked")
    public List<EntityInstance> list(String key) {
        Object values = get(key);
        return values instanceof List<?> list ? (List<EntityInstance>) list : List.of();
    }
}
