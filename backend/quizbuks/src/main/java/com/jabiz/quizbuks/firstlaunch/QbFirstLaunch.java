package com.jabiz.quizbuks.firstlaunch;

import com.jabiz.firstlaunch.FirstLaunchAccount;
import com.jabiz.firstlaunch.FirstLaunchConfig;
import com.jabiz.firstlaunch.FirstLaunchHandler;
import com.jabiz.firstlaunch.FirstLaunchProblem;
import com.jabiz.firstlaunch.FirstLaunchResults;
import com.jabiz.firstlaunch.FirstLaunchStep;
import com.jabiz.i18n.MessageCatalog;
import com.jabiz.quizbuks.operator.OperatorProcesses;
import com.jabiz.quizbuks.page.ProfilePackage;
import com.jabiz.quizbuks.page.SponsorPageProcesses;
import com.jabiz.quizbuks.publishing.PublicationLifecycle;
import com.jabiz.quizbuks.publishing.PublicationProcesses;
import com.jabiz.quizbuks.publishing.RewardMethodProcesses;
import com.jabiz.quizbuks.setup.FirstLaunchParams;
import com.jabiz.quizbuks.setup.FirstLaunchSwitches;
import com.jabiz.quizbuks.setup.QbApprovalRules;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.setup.QbSetupProcesses;
import com.jabiz.quizbuks.sponsorpackage.PackageImportProcesses;
import com.jabiz.quizbuks.sponsorpackage.PackagePlan;
import com.jabiz.quizbuks.sponsorpackage.PackageStepInputs;
import com.jabiz.quizbuks.tree.TreeProcesses;
import com.jabiz.runtime.approval.ControlChanges;
import com.jabiz.runtime.param.ParamProcesses;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * QuizBuks's part of the first launch (F-LAUNCH, docs/quizbuks/plans/impl/F-LAUNCH.md sections 2.3 and 7.2): reads
 * {@code operators}, {@code sponsors} and {@code params} of the configuration, checks them, and gives the steps of
 * the content phase after the platform has made the accounts:
 * <ol>
 *   <li>{@code QB_SETUP}; the initial values of the parameters (the configured settable ones, the money switches at
 *       their first launch's values) and the approval rules, published by the platform's first-launch publication
 *       (D82); separation of duties and the switches that wait for a decision stay proposals;</li>
 *   <li>each operator with its lead as its own sponsor ({@code QB_OPERATOR_SAVE}), and the sponsor's reward
 *       methods;</li>
 *   <li>each sponsor's package, imported as the sponsor (acting): its files stored, then each step of the import
 *       the sponsor page would run, the publications with the configuration's reward and times;</li>
 *   <li>the page and the graphs submitted as the sponsor, approved by the first launch, and waited for; then the
 *       publications, the same way.</li>
 * </ol>
 * Nothing moves money (section 4): the checks refuse what would.
 */
@Component
public class QbFirstLaunch implements FirstLaunchHandler {

    /** The processes a step runs as a sponsor (section 6.3: the platform refuses any other). */
    static final Set<String> ACTING = Set.of(PackageImportProcesses.START, PackageImportProcesses.STEP,
        SponsorPageProcesses.SUBMIT, TreeProcesses.SUBMIT, PublicationLifecycle.SUBMIT);

    private static final String MESSAGES = "qb.firstLaunch.";

    /**
     * The key of an account's one step once it is ready ({@link QbFirstLaunchChecks} keeps an account to one
     * operator). The platform records the key and refuses one that holds the account's key (its user's name), which
     * is made of letters, digits, '-' and '_': this key has none of them, so no account's key is in it.
     */
    public static final String MEMBER_STEP = "::";

    private final JsonMapper json;
    private final MessageCatalog messages;
    private final Clock clock;
    /** The package last read for a file step: each of its files is picked out of the same bytes. */
    private final AtomicReference<QbPackages.Package> lastPackage = new AtomicReference<>();

    public QbFirstLaunch(JsonMapper json, MessageCatalog messages, Clock clock) {
        this.json = json;
        this.messages = messages;
        this.clock = clock;
    }

    @Override
    public String name() {
        return "QuizBuks";
    }

    @Override
    public List<FirstLaunchProblem> validate(FirstLaunchConfig config) {
        QbFirstLaunchConfig sections = QbFirstLaunchConfig.of(config);
        Locale locale = locale(config);
        return QbFirstLaunchChecks.check(config, sections, path -> packageAt(config, path), clock.instant()).stream()
            .map(problem -> new FirstLaunchProblem(problem.location(),
                say(locale, "problem." + problem.code(), problem.params()),
                say(locale, "fix." + problem.code(), problem.params())))
            .toList();
    }

    @Override
    public Set<String> actingProcesses() {
        return ACTING;
    }

    /** The waiting phase's steps: only adding the configured members to their operators. */
    @Override
    public Set<String> accountReadyProcesses() {
        return Set.of(QbFirstLaunchProcesses.MEMBER);
    }

    /**
     * Once an account's own roles are in effect (its address verified by the invitation, a second factor bound): it
     * joins each operator that names it among its members (plan section 5.2). The operator is found by its lead, as
     * the waiting phase keeps no output of the content phase.
     */
    @Override
    public List<FirstLaunchStep> whenAccountReady(FirstLaunchConfig config, FirstLaunchAccount account,
        FirstLaunchResults results) {
        List<FirstLaunchStep> steps = new ArrayList<>();
        for (QbFirstLaunchConfig.Operator operator : QbFirstLaunchConfig.of(config).operators()) {
            if (operator.members().contains(account.key())) {
                steps.add(FirstLaunchStep.run(MEMBER_STEP, QbFirstLaunchProcesses.MEMBER,
                    ready -> new QbFirstLaunchProcesses.MemberInput(ready.userId(operator.lead()),
                        ready.userId(account.key())))
                    // Shown in the log and the run's report: in the configuration's language, as the plan's steps.
                    .describedAs(say(locale(config), "step.member", Map.of("operator", operator.name()))));
                break;
            }
        }
        return steps;
    }

    @Override
    public Set<String> filePolicies() {
        return QbPackages.POLICIES;
    }

    /** The parameters whose proposals the run publishes, and the approval rules. */
    @Override
    public Set<String> publishableControlKeys() {
        Set<String> keys = new LinkedHashSet<>(switchesAtLaunch().keySet());
        keys.addAll(asDeclared());
        QbApprovalRules.ALL.forEach(rule -> keys.add(rule.code()));
        return keys;
    }

    @Override
    public List<FirstLaunchStep> plan(FirstLaunchConfig config) {
        QbFirstLaunchConfig sections = QbFirstLaunchConfig.of(config);
        Instant launch = clock.instant();
        String reason = "First launch configuration " + config.hash().substring(0, Math.min(12,
            config.hash().length()));
        List<FirstLaunchStep> steps = new ArrayList<>();
        steps.add(FirstLaunchStep.run(SETUP, QbSetupProcesses.SETUP, results -> new QbSetupProcesses.SetupInput())
            .describedAs("QB_SETUP: roles, ledger accounts, parameters, approval rules"));
        params(sections, reason, steps);
        for (QbApprovalRules.Rule rule : QbApprovalRules.ALL) {
            steps.add(FirstLaunchStep.run("qb:rule:" + rule.code(), ControlChanges.FIRST_LAUNCH_PUBLISH,
                results -> new ControlChanges.ChangeInput(proposal(results, rule.code(), true)))
                .describedAs("Approval rule " + rule.code()));
        }
        for (QbFirstLaunchConfig.Operator operator : sections.operators()) {
            FirstLaunchAccount lead = config.account(operator.lead()).orElseThrow();
            steps.add(FirstLaunchStep.run(operatorStep(operator), OperatorProcesses.SAVE,
                results -> new OperatorProcesses.SaveInput(null, operator.name(), lead.name() == null
                    ? operator.name() : lead.name(), lead.email(), null, null, null, language(lead, config),
                    results.userId(lead.key()), operator.isDefault() ? Boolean.TRUE : null))
                .describedAs("Operator " + operator.name() + (operator.isDefault() ? " (default)" : "")
                    + ", lead " + lead.key()));
        }
        List<Sponsored> sponsored = new ArrayList<>();
        for (QbFirstLaunchConfig.Sponsor sponsor : sections.sponsors()) {
            QbFirstLaunchConfig.Operator operator = sections.operator(sponsor.operator()).orElseThrow();
            steps.add(FirstLaunchStep.run("qb:" + operator.key() + ":rewards", RewardMethodProcesses.SET,
                results -> new RewardMethodProcesses.SetInput(results.userId(operator.lead()),
                    sponsor.rewardMethods(), reason))
                .describedAs("Reward methods of " + operator.key() + "'s sponsor"));
            Sponsored made = new Sponsored(operator, sponsor, packageAt(config, sponsor.packagePath()));
            sponsored.add(made);
            importSteps(made, launch, steps);
        }
        // The page and the graphs first: a publication is submitted once the node it is bound to is in effect.
        for (Sponsored made : sponsored) {
            if (hasPage(made.pkg())) {
                steps.add(made.acting(FirstLaunchStep.run(made.prefix() + "page", SponsorPageProcesses.SUBMIT,
                    results -> new SponsorPageProcesses.SubmitInput(null))).describedAs("Submit the sponsor page"));
            }
            for (PackagePlan.Step step : made.pkg().planned().steps()) {
                if (step instanceof PackagePlan.Step.TreeStep tree) {
                    steps.add(made.acting(FirstLaunchStep.run(made.prefix() + "submit:" + tree.key(),
                        TreeProcesses.SUBMIT, results -> new TreeProcesses.TreeInput(String.valueOf(
                            made.result(results, tree.key()).get("treeId")), null)))
                        .describedAs("Submit the graph " + tree.key()));
                }
            }
        }
        steps.add(FirstLaunchStep.run("qb:approve:content", QbFirstLaunchProcesses.APPROVE,
            results -> new QbFirstLaunchProcesses.ApproveInput()).describedAs("Approve the pages and graphs"));
        steps.add(FirstLaunchStep.await("qb:wait:content", QbFirstLaunchProcesses.READY,
            results -> ready(sponsored, results, false)).describedAs("Wait for the pages and graphs"));
        for (Sponsored made : sponsored) {
            for (PackagePlan.Step step : made.pkg().planned().steps()) {
                if (step instanceof PackagePlan.Step.PublicationStep publication) {
                    steps.add(made.acting(FirstLaunchStep.run(made.prefix() + "submit:" + publication.key(),
                        PublicationLifecycle.SUBMIT, results -> new PublicationProcesses.PublicationRef(
                            publicationId(made, results, publication.key()), null)))
                        .describedAs("Submit the publication " + publication.name()));
                }
            }
        }
        steps.add(FirstLaunchStep.run("qb:approve:publications", QbFirstLaunchProcesses.APPROVE,
            results -> new QbFirstLaunchProcesses.ApproveInput()).describedAs("Approve the publications"));
        steps.add(FirstLaunchStep.await("qb:wait:publications", QbFirstLaunchProcesses.READY,
            results -> ready(sponsored, results, true)).describedAs("Wait for the publications to go live"));
        return steps;
    }

    @Override
    public List<String> remaining(FirstLaunchConfig config) {
        QbFirstLaunchConfig sections = QbFirstLaunchConfig.of(config);
        Locale locale = locale(config);
        List<String> remaining = new ArrayList<>();
        remaining.add(say(locale, "remaining.legal", Map.of()));
        sections.operators().forEach(operator -> remaining.add(say(locale, "remaining.join",
            Map.of("account", operator.lead()))));
        remaining.add(say(locale, "remaining.sod", Map.of()));
        FirstLaunchSwitches.ALL.stream().filter(row -> row.prerequisite() != null)
            .forEach(row -> remaining.add(say(locale, "remaining.prerequisite",
                Map.of("key", row.key(), "prerequisite", row.prerequisite()))));
        remaining.add(say(locale, "remaining.amounts", Map.of()));
        remaining.add(say(locale, "remaining.verification", Map.of()));
        remaining.add(say(locale, "remaining.money", Map.of()));
        return remaining;
    }

    // ---- steps ------------------------------------------------------------------------------------------------------

    static final String SETUP = "qb:setup";

    /** A sponsor with its operator and package, and the keys of its steps. */
    private record Sponsored(QbFirstLaunchConfig.Operator operator, QbFirstLaunchConfig.Sponsor sponsor,
        QbPackages.Package pkg) {

        String prefix() {
            return "qb:" + operator.key() + ":";
        }

        String stepKey(String importStep) {
            return prefix() + "import:" + importStep;
        }

        FirstLaunchStep acting(FirstLaunchStep step) {
            return step.asAccount(operator.lead());
        }

        /** What an import step made ({@code StepOutput.result}). */
        Map<String, Object> result(FirstLaunchResults results, String importStep) {
            return results.output(stepKey(importStep), PackageImportProcesses.StepOutput.class).result();
        }
    }

    private static String operatorStep(QbFirstLaunchConfig.Operator operator) {
        return "qb:operator:" + operator.key();
    }

    /** The money switches that take their first launch's value now, with it (the others stay proposals). */
    static Map<String, Object> switchesAtLaunch() {
        Map<String, Object> values = new LinkedHashMap<>();
        FirstLaunchSwitches.ALL.stream().filter(FirstLaunchSwitches.Row::takesEffectAtLaunch)
            .forEach(row -> values.put(row.key(), row.value()));
        return values;
    }

    private static void params(QbFirstLaunchConfig sections, String reason, List<FirstLaunchStep> steps) {
        Map<String, Object> values = new LinkedHashMap<>(switchesAtLaunch());
        values.putAll(QbFirstLaunchChecks.values(sections));
        // A settable parameter not configured, and one fixed at its declared value, take the value QB_SETUP proposed
        // (the declared one; the launch's own processes read it from now on).
        for (String key : asDeclared()) {
            if (QbParams.CONTROLLED.contains(key) && !values.containsKey(key)) {
                steps.add(FirstLaunchStep.run("qb:param:" + key, ControlChanges.FIRST_LAUNCH_PUBLISH,
                    results -> new ControlChanges.ChangeInput(proposal(results, key, false)))
                    .describedAs("Parameter " + key + " as declared"));
            }
        }
        values.forEach((key, value) -> {
            QbParams.Param param = QbFirstLaunchChecks.param(key);
            if (QbParams.CONTROLLED.contains(key)) {
                steps.add(FirstLaunchStep.run("qb:param:" + key, QbFirstLaunchProcesses.PARAM,
                    results -> new QbFirstLaunchProcesses.ParamInput(proposal(results, key, false), key,
                        param.valueKind(), value, param.description(), reason))
                    .describedAs("Parameter " + key + " = " + value));
            } else {
                steps.add(FirstLaunchStep.run("qb:param:" + key, "PARAM_SET",
                    results -> new ParamProcesses.SetInput(key, value)).describedAs("Parameter " + key + " = "
                    + value));
            }
        });
    }

    /**
     * The parameters published as {@code QB_SETUP} proposed them: the settable ones unless configured, and those
     * {@link FirstLaunchParams#FIXED_VALUES fixed} at their declared value (the publications' review: no automatic
     * approval, plan section 3.1), which the configuration cannot set.
     */
    static Set<String> asDeclared() {
        Set<String> keys = new LinkedHashSet<>();
        FirstLaunchParams.SETTABLE.stream().filter(QbParams.CONTROLLED::contains).forEach(keys::add);
        FirstLaunchParams.FIXED_VALUES.keySet().stream().filter(QbParams.CONTROLLED::contains).forEach(keys::add);
        return keys;
    }

    /** The change {@code QB_SETUP} proposed of a parameter or an approval rule. */
    static UUID proposal(FirstLaunchResults results, String key, boolean rule) {
        QbSetupProcesses.SetupOutput setup = results.output(SETUP, QbSetupProcesses.SetupOutput.class);
        int index = rule ? setup.rulesProposed().indexOf(key) : setup.paramsProposed().indexOf(key);
        if (index < 0) {
            throw new IllegalStateException("QB_SETUP proposed no " + key + ": the database is not a new one");
        }
        return UUID.fromString(setup.proposals().get(rule ? setup.paramsProposed().size() + index : index));
    }

    private void importSteps(Sponsored made, Instant launch, List<FirstLaunchStep> steps) {
        QbPackages.Package pkg = made.pkg();
        PackagePlan.Planned planned = pkg.planned();
        String path = made.sponsor().packagePath();
        steps.add(made.acting(FirstLaunchStep.run(made.prefix() + "import", PackageImportProcesses.START,
            results -> new PackageImportProcesses.StartInput(pkg.hash(), pkg.document().version(), planned.mode().name(),
                planned.batchSize(), planned.batchBytes() > 0 ? (int) planned.batchBytes() : null,
                planned.steps().size())))
            .describedAs("Import " + path));
        Map<String, List<QbPackages.Upload>> uploads = pkg.uploads();
        for (PackagePlan.Step step : planned.steps()) {
            List<QbPackages.Upload> files = uploads.getOrDefault(step.key(), List.of());
            for (QbPackages.Upload upload : files) {
                steps.add(made.acting(FirstLaunchStep.file(fileKey(made, step, upload),
                    new FirstLaunchStep.FileSource(upload.policy(), path, bytes -> pick(bytes, upload), "file"))));
            }
            steps.add(made.acting(FirstLaunchStep.run(made.stepKey(step.key()), PackageImportProcesses.STEP,
                results -> stepInput(made, step, files, results, launch)))
                .describedAs("Import " + step.kind() + " " + step.key()));
        }
    }

    private static String fileKey(Sponsored made, PackagePlan.Step step, QbPackages.Upload upload) {
        return made.prefix() + "file:" + step.key() + ":" + upload.key();
    }

    /** The bytes of an upload out of the package's bytes (read again: the platform gives the file as it is now). */
    private byte[] pick(byte[] bytes, QbPackages.Upload upload) {
        String hash = QbPackages.sha256(bytes);
        QbPackages.Package pkg = lastPackage.get();
        if (pkg == null || !pkg.hash().equals(hash)) {
            pkg = QbPackages.read(bytes, json);
            lastPackage.set(pkg);
        }
        return pkg.document() == null ? null : pkg.bytes(upload);
    }

    private PackageImportProcesses.StepInput stepInput(Sponsored made, PackagePlan.Step step,
        List<QbPackages.Upload> files, FirstLaunchResults results, Instant launch) {
        Map<String, Map<String, Object>> done = new LinkedHashMap<>();
        for (PackagePlan.Step earlier : made.pkg().planned().steps()) {
            if (earlier == step) {
                break;
            }
            done.put(earlier.key(), made.result(results, earlier.key()));
        }
        Map<String, String> uploaded = new LinkedHashMap<>();
        files.forEach(upload -> uploaded.put(upload.key(),
            String.valueOf(results.output(fileKey(made, step, upload), UUID.class))));
        Instant start = launch;
        QbFirstLaunchConfig.PublicationExtra extra = null;
        if (step instanceof PackagePlan.Step.PublicationStep publication) {
            extra = made.sponsor().publications().get(publication.name());
            start = extra.start(launch);
        }
        Map<String, Object> input = new LinkedHashMap<>(PackageStepInputs.inputOf(step, made.pkg().document(), done,
            Set.of(), start, new PackageStepInputs.Uploads(files.isEmpty() ? null : uploaded, null)));
        if (extra != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> publication = new LinkedHashMap<>((Map<String, Object>) input.get("publication"));
            @SuppressWarnings("unchecked")
            Map<String, Object> save = new LinkedHashMap<>((Map<String, Object>) publication.get("save"));
            save.put("rewardMethod", extra.rewardMethod());
            save.put("redeemDescription", extra.redeemDescription());
            save.put("endAt", extra.end().toString());
            // No Kudos at the first launch (section 4): a budget of 0 is no budget.
            if (save.get("budgetCap") instanceof Number budget && budget.doubleValue() == 0) {
                save.put("budgetCap", null);
            }
            publication.put("save", save);
            input.put("publication", publication);
        }
        UUID importId = results.output(made.prefix() + "import", PackageImportProcesses.ImportView.class).importId();
        input.put("importId", importId.toString());
        input.put("step", step.key());
        return json.convertValue(input, PackageImportProcesses.StepInput.class);
    }

    private static UUID publicationId(Sponsored made, FirstLaunchResults results, String importStep) {
        return UUID.fromString(String.valueOf(made.result(results, importStep).get("publicationId")));
    }

    private static boolean hasPage(QbPackages.Package pkg) {
        if (pkg.document().profile() == null) {
            return false;
        }
        ProfilePackage profile = ProfilePackage.read(pkg.document().profile()).profile();
        return profile != null && profile.page() != null;
    }

    private static QbFirstLaunchProcesses.ReadyInput ready(List<Sponsored> sponsored, FirstLaunchResults results,
        boolean publications) {
        List<String> trees = new ArrayList<>();
        List<String> pages = new ArrayList<>();
        List<UUID> made = new ArrayList<>();
        for (Sponsored sponsor : sponsored) {
            for (PackagePlan.Step step : sponsor.pkg().planned().steps()) {
                if (step instanceof PackagePlan.Step.TreeStep) {
                    trees.add(String.valueOf(sponsor.result(results, step.key()).get("treeId")));
                } else if (publications && step instanceof PackagePlan.Step.PublicationStep) {
                    made.add(publicationId(sponsor, results, step.key()));
                }
            }
            if (hasPage(sponsor.pkg())) {
                pages.add(results.userId(sponsor.operator().lead()));
            }
        }
        return new QbFirstLaunchProcesses.ReadyInput(trees, pages, made);
    }

    // ---- reading ----------------------------------------------------------------------------------------------------

    /** The package at a path of the configuration's directory, read and planned; null when there is no such file. */
    private QbPackages.Package packageAt(FirstLaunchConfig config, String path) {
        if (path == null || !config.files().exists(path)) {
            return null;
        }
        return QbPackages.read(config.files().read(path), json);
    }

    private static String language(FirstLaunchAccount account, FirstLaunchConfig config) {
        String language = account.language() != null ? account.language() : config.language();
        return language != null && Set.of("ja", "zh", "en").contains(language) ? language : null;
    }

    private Locale locale(FirstLaunchConfig config) {
        return config.language() == null || config.language().isBlank() ? messages.defaultLocale()
            : messages.supported(Locale.forLanguageTag(config.language()));
    }

    /** The message {@code qb.firstLaunch.<key>} in the configuration's language, its {@code {name}}s filled in. */
    private String say(Locale locale, String key, Map<String, ?> params) {
        String text = messages.find(MESSAGES + key, locale).orElse(MESSAGES + key);
        for (Map.Entry<String, ?> param : params.entrySet()) {
            text = text.replace("{" + param.getKey() + "}", String.valueOf(param.getValue()));
        }
        return text;
    }
}
