package com.jabiz.quizbuks.messaging;

import com.jabiz.entity.Violation;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.process.steps.RunTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The server's check of consent before money and contract actions (docs/quizbuks/plans/Q7-messages-legal.md,
 * D-Q7-5, Q7-2): topping up, submitting a publication and opening a payout account are refused (422
 * {@code QB_CONSENT_REQUIRED}, with the kind and the version to accept) while the caller has not accepted the version
 * in effect of their terms or the privacy policy. The pages ask after sign-in; this is what holds when a page does not
 * (an old app, a script). Starting a quiz is not checked (Q7-2). One statement ({@code qb.system.consent-state}), read
 * before any lock.
 *
 * <p>Also the shared reading of what a user accepts on registration's second step and on onboarding: the versions the
 * page showed must be those in effect ({@code QB_TERMS_OUTDATED}).
 */
public final class ConsentChecks {

    static final String STATE = "consentState";

    /** The steps refusing a caller who must accept the takers' terms or the privacy policy first. */
    public static <I, O> Consumer<ProcessDefinitionBuilder<I, O, ProcessContext>> taker() {
        return require(QbLegal.APP_KINDS);
    }

    /** The steps refusing a caller who must accept the sponsors' terms or the privacy policy first. */
    public static <I, O> Consumer<ProcessDefinitionBuilder<I, O, ProcessContext>> sponsor() {
        return require(QbLegal.SPONSOR_KINDS);
    }

    static <I, O> Consumer<ProcessDefinitionBuilder<I, O, ProcessContext>> require(List<String> kinds) {
        return pb -> {
            load(pb, "Load the caller's consents", kinds, ctx -> ctx.request().actorId());
            pb.compute("Refuse without consent", (metadata, ctx) -> {
                List<Violation> problems = missing(ctx);
                if (!problems.isEmpty()) {
                    throw new BusinessRuleViolationException(problems);
                }
            });
        };
    }

    /** Loads into the context each kind's state for the user ({@link #states}). */
    public static <I, O> void load(ProcessDefinitionBuilder<I, O, ProcessContext> pb, String step, List<String> kinds,
        Function<ProcessContext, String> userId) {
        pb.step(step, RunTemplate.<ProcessContext>of(QbLegal.CONSENT_STATE_TEMPLATE, ctx -> {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("userId", userId.apply(ctx));
            params.put("kinds", kinds);
            params.put("at", ctx.opTime());
            return params;
        }, STATE));
    }

    /** One kind's standing: the version in effect (its key and number), the number to accept and the user's highest. */
    public record State(String kind, Object currentVersionId, int currentVersionNo, int toAccept, int accepted) {

        public boolean mustAccept() {
            return LegalRules.mustAccept(toAccept, accepted);
        }
    }

    /** The states loaded by {@link #load}, by kind; a kind without a version in effect is absent. */
    @SuppressWarnings("unchecked")
    public static Map<String, State> states(ProcessContext ctx) {
        Map<String, State> states = new LinkedHashMap<>();
        List<Map<String, Object>> rows = (List<Map<String, Object>>) ctx.get(STATE);
        for (Map<String, Object> row : rows == null ? List.<Map<String, Object>>of() : rows) {
            String kind = String.valueOf(row.get("kind"));
            states.put(kind, new State(kind, row.get("currentVersionId"), number(row.get("currentVersionNo")),
                number(row.get("toAccept")), number(row.get("accepted"))));
        }
        return states;
    }

    /** The refusals of each kind the user must accept first. */
    public static List<Violation> missing(ProcessContext ctx) {
        List<Violation> problems = new ArrayList<>();
        states(ctx).values().stream().filter(State::mustAccept).forEach(state -> problems.add(new Violation(null,
            LegalCodes.CONSENT_REQUIRED, "Accept version " + state.currentVersionNo() + " of " + state.kind()
            + " first", Map.of("kind", state.kind(), "versionNo", state.currentVersionNo()))));
        return problems;
    }

    /**
     * The versions a page showed, checked against those in effect: for each kind in effect, the number given must be
     * that version's (422 {@code QB_TERMS_OUTDATED} otherwise, the page reloads). Kinds with nothing in effect ask
     * for nothing. Returns the states to record, by kind.
     */
    public static List<State> accepted(ProcessContext ctx, Map<String, Integer> shown) {
        List<State> toRecord = new ArrayList<>();
        for (State state : states(ctx).values()) {
            Integer given = shown.get(state.kind());
            if (given == null || given != state.currentVersionNo()) {
                ctx.reject(new Violation(field(state.kind()), LegalCodes.TERMS_OUTDATED, "Version "
                    + state.currentVersionNo() + " of " + state.kind() + " is in effect now",
                    Map.of("kind", state.kind(), "versionNo", state.currentVersionNo())));
            } else if (state.accepted() < state.currentVersionNo()) {
                toRecord.add(state);
            }
        }
        return List.copyOf(toRecord);
    }

    /** Whether any of the kinds has a version in effect: only then is anything asked. */
    public static boolean anyInEffect(ProcessContext ctx) {
        return !states(ctx).isEmpty();
    }

    /** The input field of a kind's version on registration and onboarding. */
    static String field(String kind) {
        return QbLegal.PRIVACY.equals(kind) ? "privacyVersionNo" : "termsVersionNo";
    }

    /** Records the user's acceptance of the version in effect of each state. */
    public static void record(ProcessContext ctx, String userId, List<State> states, String source) {
        for (State state : states) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("userId", userId);
            values.put("kind", state.kind());
            values.put("legalVersionId", state.currentVersionId());
            values.put("versionNumber", BigDecimal.valueOf(state.currentVersionNo()));
            values.put("acceptedAt", ctx.opTime());
            values.put("entry", ctx.request().entry());
            values.put("source", source);
            ctx.changes().insert(QbLegal.CONSENT, values);
        }
    }

    static int number(Object value) {
        return value == null ? 0 : new BigDecimal(value.toString()).intValueExact();
    }

    private ConsentChecks() {}
}
