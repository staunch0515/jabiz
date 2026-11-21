package com.jabiz.quizbuks.setup;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The administration accepts the first launch's waiting role (F-LAUNCH, docs/quizbuks/plans/impl/F-LAUNCH.md section
 * 5.2, B1): an invited account has only that role until its own roles are granted, and an entry that accepts none of
 * its roles refuses it. The default of {@code QB_ADMIN_ROLES} and every place that overrides it list the role.
 */
class FirstLaunchAdminRolesTest {

    private static final Path ROOT = Path.of("../..");

    /** The files that set QB_ADMIN_ROLES to a whole list (the default's own roles first). */
    private static final List<String> OVERRIDES = List.of(".github/workflows/quizbuks-store.yml",
        "quizbuks-web/e2e/app.env", "tools/quizbuks/test/e2e.test.sh", "tools/quizbuks/local-demo.sh",
        "tools/quizbuks/stripe-test-mode/run.sh", "deploy/quizbuks/compose.dev.yaml", "deploy/quizbuks/README.md",
        "docs/quizbuks/operations.md");

    private static final Pattern LIST = Pattern.compile("ADMIN,QB_ADMIN_SUPER,QB_ADMIN_CONTENT,QB_ADMIN_FINANCE[A-Z0-9_,]*");

    @Test
    void theAdministrationsDefaultAcceptsTheWaitingRole() throws IOException {
        String yml = Files.readString(Path.of("src/main/resources/application.yml"));
        Matcher matcher = Pattern.compile("\\$\\{QB_ADMIN_ROLES:([^}]*)}").matcher(yml);
        assertThat(matcher.find()).isTrue();
        assertThat(List.of(matcher.group(1).split(","))).contains(FirstLaunchRoles.PENDING);
    }

    @Test
    void everyListOfTheAdministrationsRolesHasTheWaitingRole() throws IOException {
        for (String file : OVERRIDES) {
            String text = Files.readString(ROOT.resolve(file));
            Matcher matcher = LIST.matcher(text);
            int lists = 0;
            while (matcher.find()) {
                lists++;
                assertThat(List.of(matcher.group().split(","))).as("%s: %s", file, matcher.group())
                    .contains(FirstLaunchRoles.PENDING);
            }
            assertThat(lists).as("%s lists the administration's roles", file).isPositive();
        }
    }

    @Test
    void theWaitingRoleIsTheFirstLaunchsName() {
        assertThat(FirstLaunchRoles.PENDING).isEqualTo("FIRST_LAUNCH_PENDING");
    }
}
