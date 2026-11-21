package com.jabiz.runtime.firstlaunch.accounts;

import com.jabiz.firstlaunch.FirstLaunchAccount;
import com.jabiz.firstlaunch.FirstLaunchConfig;
import com.jabiz.firstlaunch.FirstLaunchProblem;
import com.jabiz.firstlaunch.FirstLaunchResults;
import com.jabiz.firstlaunch.FirstLaunchStep;
import com.jabiz.runtime.firstlaunch.FirstLaunchPlatformSteps;
import com.jabiz.runtime.mail.MailTemplates;
import com.jabiz.runtime.security.InvitationProcesses;
import com.jabiz.runtime.security.SignInEntries;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The platform's part of the first launch's content phase: the configuration's accounts (docs/design/23-first-launch.md
 * section 8). One step creates the waiting role, then one step per account (keyed by its place, {@code account:#<n>};
 * {@code account:<key>} finds it among the results) creates the user,
 * holding the waiting role, without a password or an invitation. The waiting phase's job invites them once the
 * content phase is done.
 *
 * <p>Validation: the entry the invitations lead to ({@value InvitationProcesses#DEFAULT_ENTRY}) must accept the
 * waiting role, else the invited could not sign in to bind their second factor; mail must be on, else no invitation
 * would arrive.
 */
@Component
public class FirstLaunchAccountSteps implements FirstLaunchPlatformSteps {

    /** The key of the step that creates the waiting role. */
    public static final String WAITING_ROLE_STEP = "waiting-role";

    private final SignInEntries entries;
    private final MailTemplates mail;
    private final ConfigShape shape;

    public FirstLaunchAccountSteps(SignInEntries entries, MailTemplates mail, ConfigShape shape) {
        this.entries = entries;
        this.mail = mail;
        this.shape = shape;
    }

    @Override
    public int getOrder() {
        return 0;
    }

    @Override
    public List<FirstLaunchProblem> validate(FirstLaunchConfig config) {
        List<FirstLaunchProblem> problems = new ArrayList<>();
        if (config.accounts().isEmpty()) {
            return problems;
        }
        String entry = InvitationProcesses.DEFAULT_ENTRY;
        boolean accepts = entries.find(entry).map(found -> found.accepts(WaitingRole.CODE)).orElse(false);
        if (!accepts) {
            problems.add(new FirstLaunchProblem("jabiz.security.entries." + entry + ".accepted-roles",
                "does not accept the first launch's waiting role " + WaitingRole.CODE + ": the invited accounts could"
                    + " not sign in to bind their second factor",
                "add " + WaitingRole.CODE + " to the roles the entry " + entry + " accepts"));
        }
        if (!mail.enabled()) {
            problems.add(new FirstLaunchProblem("jabiz.mail.enabled", "is off: the accounts' invitations would"
                + " never arrive", "turn mail on (jabiz.mail.enabled=true) with a working server"));
        }
        for (int i = 0; i < config.accounts().size(); i++) {
            FirstLaunchAccount account = config.accounts().get(i);
            if (account.roles().contains(WaitingRole.CODE)) {
                // By place: the key is the user's name, and problems go to the log.
                problems.add(new FirstLaunchProblem("accounts[#" + (i + 1) + "].roles", "names the waiting"
                    + " role " + WaitingRole.CODE, "list only the roles the account is to be given"));
            }
        }
        return problems;
    }

    @Override
    public List<FirstLaunchStep> plan(FirstLaunchConfig config) {
        if (config.accounts().isEmpty()) {
            return List.of();
        }
        List<FirstLaunchStep> steps = new ArrayList<>();
        steps.add(FirstLaunchStep.run(WAITING_ROLE_STEP, FirstLaunchAccountProcesses.WAITING_ROLE_CREATE,
                results -> new FirstLaunchAccountProcesses.RoleInput())
            .describedAs("waiting role " + WaitingRole.CODE));
        int position = 0;
        for (FirstLaunchAccount account : config.accounts()) {
            // Keyed and described by its place: step keys are recorded with the operations, and the key is the user's
            // name (personal data); the results map account:<key> to the place.
            FirstLaunchAccountProcesses.NewAccount input = new FirstLaunchAccountProcesses.NewAccount(position,
                account.key(), account.name(), account.email(), account.language(), account.roles(),
                account.report());
            steps.add(FirstLaunchStep.run(FirstLaunchStep.accountStepKeyAt(position),
                    FirstLaunchAccountProcesses.ACCOUNT_CREATE, results -> input)
                .describedAs("account #" + (position + 1) + " (" + String.join(", ", account.roles()) + ")"));
            position++;
        }
        return steps;
    }

    @Override
    public Object contentDoneDetail(FirstLaunchConfig config, FirstLaunchResults results) {
        return config.accounts().isEmpty() ? null : Map.of("shape", shape.of(config));
    }

    @Override
    public String detailKey() {
        return "accounts";
    }
}
