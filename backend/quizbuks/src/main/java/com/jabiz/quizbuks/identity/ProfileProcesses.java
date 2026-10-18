package com.jabiz.quizbuks.identity;

import com.jabiz.entity.Violation;
import com.jabiz.mail.MailRecipient;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.country.QbCountries;
import com.jabiz.quizbuks.messaging.ConsentChecks;
import com.jabiz.quizbuks.messaging.LegalCodes;
import com.jabiz.quizbuks.messaging.QbLegal;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.mail.SendMail;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static com.jabiz.quizbuks.identity.AccountEditing.INPUT;
import static com.jabiz.quizbuks.identity.AccountEditing.OUTPUT;
import static com.jabiz.quizbuks.identity.AccountEditing.PROFILES;
import static com.jabiz.quizbuks.identity.AccountEditing.first;

/**
 * {@code QB_PROFILE_SAVE}: a taker's own profile (U-02 second step, U-03, U-04, U-70; D-Q2-2). The caller is the user:
 * the input names nobody. The first save that gives a country completes registration and sends the welcome mail.
 * Like every QuizBuks write, it needs a verified address (D-Q2-1), so an account somebody pre-claimed with another
 * person's address keeps no QuizBuks data.
 */
public final class ProfileProcesses {

    public static final String SAVE = "QB_PROFILE_SAVE";

    static final String COUNTRIES = "countries";
    static final String WELCOME = "welcome";
    private static final Pattern COUNTRY = Pattern.compile("[A-Z]{2}");

    /**
     * @param countryCode       a country of the dictionary ({@code QbCountry.code}); the takers' regions follow it
     * @param preferredCurrency for showing amounts only (U-04): one of {@link QbAccounts#CURRENCIES}
     * @param baseRevision      the revision the caller edited; when given and no longer current, 422
     * @param acceptTerms       registration's second step (the save completing the profile): the takers' terms and
     *                          the privacy policy are accepted (Q7b, D-Q7-5); asked only while a version is in effect
     * @param termsVersionNo    the version of the takers' terms the page showed; must be the one in effect
     * @param privacyVersionNo  the version of the privacy policy the page showed; must be the one in effect
     */
    public record ProfileInput(@NotNull String displayName, @NotBlank String countryCode, String city,
        @NotBlank String preferredCurrency, Long baseRevision, Boolean acceptTerms, Integer termsVersionNo,
        Integer privacyVersionNo) {

        /** The versions the page showed, by kind of text. */
        Map<String, Integer> shown() {
            Map<String, Integer> shown = new HashMap<>();
            shown.put(QbLegal.TERMS_APP, termsVersionNo);
            shown.put(QbLegal.PRIVACY, privacyVersionNo);
            return shown;
        }
    }

    /** @param created whether this save completed the profile (the welcome mail was sent) */
    public record ProfileOutput(String userId, UUID profileId, long revision, Instant editedAt, boolean created) {}

    public static ProcessDefinition<ProfileInput, ProfileOutput, ProcessContext> save() {
        return ProcessDefinition.define(SAVE, 1, ProfileInput.class, ProfileOutput.class, ProcessContext.class, pb -> {
            pb.description("Saves the caller's own profile: name, country, city and preferred currency.")
                .permissions(QbPermissions.ME)
                .requiresVerifiedEmail()
                .contextFactory((start, input) -> AccountEditing.start(start, input, start.request().actorId()))
                .outputMapper(ctx -> ctx.get(OUTPUT, ProfileOutput.class));
            AccountEditing.lock(pb);
            CallerChecks.refuseBanned(pb);
            AccountEditing.loadOfUser(pb, "Load the profile", QbAccounts.PROFILE, PROFILES);
            pb.step("Load the country", QueryEntities.<ProcessContext>of(QbCountries.DATASET, ctx -> {
                List<Object> codes = new ArrayList<>();
                String code = ctx.get(INPUT, ProfileInput.class).countryCode();
                if (code != null && COUNTRY.matcher(code).matches()) {
                    codes.add(code);
                }
                return EntityQuery.builder().where(new QueryPredicate.In("code", codes)).limit(1).build();
            }, COUNTRIES));
            AccountEditing.loadUser(pb, AccountEditing::userId);
            ConsentChecks.load(pb, "Load the terms in effect", QbLegal.APP_KINDS, AccountEditing::userId);
            pb.compute("Save the profile", (metadata, ctx) -> save(ctx));
            pb.step("Welcome", SendMail.<ProcessContext>when(ctx -> !ctx.hasViolations()
                    && Boolean.TRUE.equals(ctx.get(WELCOME)) && AccountEditing.hasAddress(ctx), AccountMails.WELCOME,
                ctx -> MailRecipient.user(AccountEditing.userId(ctx)),
                Map.of("name", ctx -> ctx.get(INPUT, ProfileInput.class).displayName())));
        });
    }

    static void save(ProcessContext ctx) {
        ProfileInput input = ctx.get(INPUT, ProfileInput.class);
        String userId = AccountEditing.userId(ctx);
        EntityInstance profile = first(ctx, PROFILES);
        if (!AccountEditing.current(ctx, profile, input.baseRevision(), AccountCodes.PROFILE_CHANGED)) {
            return;
        }
        // A code of the wrong form is the field rule's (400); a well-formed one must be in the dictionary.
        if (COUNTRY.matcher(input.countryCode()).matches() && first(ctx, COUNTRIES) == null) {
            ctx.reject(new Violation("countryCode", AccountCodes.COUNTRY_UNKNOWN, "There is no country "
                + input.countryCode(), Map.of("countryCode", input.countryCode())));
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("displayName", input.displayName());
        values.put("countryCode", input.countryCode());
        values.put("city", input.city() == null || input.city().isBlank() ? null : input.city());
        values.put("preferredCurrency", input.preferredCurrency());
        // Registration's second step is done once a country is known (a sponsor's profile has only a name).
        boolean completes = profile == null || profile.get("countryCode") == null;
        // Completing the profile is where a taker accepts the terms and the privacy policy (Q7-3), once any is in
        // effect; who completed it earlier accepts after signing in (QB_CONSENT_ACCEPT).
        if (completes && ConsentChecks.anyInEffect(ctx)) {
            if (!Boolean.TRUE.equals(input.acceptTerms())) {
                ctx.reject(new Violation("acceptTerms", LegalCodes.TERMS_REQUIRED, "Accept the terms and the "
                    + "privacy policy to continue", Map.of()));
                return;
            }
            List<ConsentChecks.State> accepted = ConsentChecks.accepted(ctx, input.shown());
            if (ctx.hasViolations()) {
                return;
            }
            ConsentChecks.record(ctx, userId, accepted, QbLegal.SOURCE_WELCOME);
        }
        ctx.put(WELCOME, completes);
        if (profile == null) {
            values.put("userId", userId);
            values.put("createdAt", ctx.opTime());
            values.put("editedAt", ctx.opTime());
            values.put("revision", BigDecimal.ONE);
            Object id = ctx.changes().insert(QbAccounts.PROFILE, values);
            ctx.put(OUTPUT, new ProfileOutput(userId, AccountEditing.uuid(id), 1, ctx.opTime(), true));
            return;
        }
        Map<String, Object> changed = AccountEditing.differences(profile, values);
        long revision = AccountEditing.number(profile.get("revision"));
        Instant editedAt = AccountEditing.instant(profile.get("editedAt"));
        if (!changed.isEmpty()) {
            revision++;
            editedAt = ctx.opTime();
            changed.put("editedAt", editedAt);
            changed.put("revision", BigDecimal.valueOf(revision));
            ctx.changes().update(QbAccounts.PROFILE, profile.id(), profile.version(), changed);
        }
        ctx.put(OUTPUT, new ProfileOutput(userId, AccountEditing.uuid(profile.id()), revision, editedAt, completes));
    }

    private ProfileProcesses() {}
}
