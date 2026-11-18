package com.jabiz.app.it.firstlaunch;

import com.jabiz.firstlaunch.FirstLaunchHandler;
import com.jabiz.i18n.MessageCatalog;
import com.jabiz.runtime.firstlaunch.FirstLaunchExit;
import com.jabiz.runtime.firstlaunch.FirstLaunchGate;
import com.jabiz.runtime.firstlaunch.FirstLaunchPlatformSteps;
import com.jabiz.runtime.firstlaunch.FirstLaunchRunner;
import com.jabiz.runtime.firstlaunch.FirstLaunchRuns;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.test.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a start decides about the first launch (docs/design/23-first-launch.md section 3): a database that had users
 * is never touched, problems of the configuration stop the start (all of them at once), settings the first launch
 * cannot work with are problems, a dry run validates without writing, a restore verification ignores it all.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "jabiz.security.bootstrap-admin.user-name=root",
    "jabiz.security.bootstrap-admin.password=bootstrap password 1"})
class FirstLaunchStartupIT extends PostgresIntegrationTest {

    @Autowired
    FirstLaunchRuns runs;

    @Autowired
    ProcessExecutor processes;

    @Autowired
    ObjectProvider<FirstLaunchHandler> handlers;

    @Autowired
    ObjectProvider<FirstLaunchPlatformSteps> platformSteps;

    @Autowired
    MessageCatalog messages;

    @Autowired
    Clock clockBean;

    @Autowired
    JsonMapper json;

    @Autowired
    com.jabiz.runtime.firstlaunch.FirstLaunchPowers powers;

    @Autowired
    com.jabiz.runtime.firstlaunch.FirstLaunchFiles files;

    private final List<Integer> exits = new CopyOnWriteArrayList<>();

    private FirstLaunchRunner runner(MockEnvironment environment, FirstLaunchGate gate) {
        ObjectProvider<FirstLaunchExit> exit = new org.springframework.beans.factory.support.StaticListableBeanFactory(
            java.util.Map.of("exit", (FirstLaunchExit) exits::add)).getBeanProvider(FirstLaunchExit.class);
        return new FirstLaunchRunner(environment, runs, gate, processes, handlers, platformSteps, messages, clockBean,
            exit, json, powers, files);
    }

    @Test
    void aDatabaseThatHadUsersIsNeverTouched() {
        // The first administrator was created at startup (BootstrapAdmin), so the database has a user.
        FirstLaunchGate gate = new FirstLaunchGate();
        MockEnvironment environment = new MockEnvironment()
            .withProperty("jabiz.first-launch.config", FirstLaunchIT.configFile("version: 1\n"));

        runner(environment, gate).afterSingletonsInstantiated();

        assertThat(gate.isOpen()).isTrue();
        assertThat(query("SELECT count(*) AS runs FROM sys_first_launch_run").getFirst().get("runs")).isEqualTo(0L);
    }

    @Test
    void everyProblemStopsTheStartAtOnce() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("jabiz.first-launch.config", FirstLaunchIT.configFile("""
                version: 2
                site:
                  language: zh
                allowedEmailDomains: [example.co.jp]
                accounts:
                  - key: A
                    email: a@elsewhere.example
                    name: 鈴木
                    roles: [ADMIN]
                    password: hunter2
                  - key: A
                    email: not-an-address
                    roles: []
                """))
            .withProperty("jabiz.first-launch.dry-run", "true")
            .withProperty("jabiz.security.bootstrap-admin.user-name", "root");

        assertThatThrownBy(() -> runner(environment, new FirstLaunchGate()).afterSingletonsInstantiated())
            .isInstanceOf(FirstLaunchRunner.FirstLaunchFailedException.class)
            .hasMessageContaining("version")
            .hasMessageContaining("accounts[0].email | is not under allowedEmailDomains")
            .hasMessageContaining("accounts[0].password | looks like a secret")
            .hasMessageContaining("accounts[1].key | repeats the key A")
            .hasMessageContaining("accounts[1].email | is not an e-mail address")
            .hasMessageContaining("accounts[1].name | is required")
            .hasMessageContaining("accounts[1].roles")
            .hasMessageContaining("no account has report: true")
            .hasMessageContaining("JABIZ_BOOTSTRAP_ADMIN_USER")
            .hasMessageContaining("首发配置有");
    }

    @Test
    void protectedGrantsAlwaysOnLeaveNoSetupWindowForTheFirstLaunch() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("jabiz.first-launch.config", FirstLaunchIT.configFile("version: 1\n"))
            .withProperty("jabiz.first-launch.dry-run", "true")
            .withProperty("jabiz.security.protected-grants.always", "true");

        assertThatThrownBy(() -> runner(environment, new FirstLaunchGate()).afterSingletonsInstantiated())
            .isInstanceOf(FirstLaunchRunner.FirstLaunchFailedException.class)
            .hasMessageContaining("jabiz.security.protected-grants.always");
    }

    @Test
    void aDryRunValidatesAndWritesNothing() {
        FirstLaunchGate gate = new FirstLaunchGate();
        MockEnvironment environment = new MockEnvironment()
            .withProperty("jabiz.first-launch.config", FirstLaunchIT.configFile("version: 1\n"))
            .withProperty("jabiz.first-launch.dry-run", "true");
        FirstLaunchRunner runner = runner(environment, gate);

        runner.afterSingletonsInstantiated();
        runner.onApplicationEvent(null);

        assertThat(exits).containsExactly(0);
        assertThat(query("SELECT count(*) AS runs FROM sys_first_launch_run").getFirst().get("runs")).isEqualTo(0L);
    }

    @Test
    void aRestoreVerificationIgnoresTheConfiguration() {
        FirstLaunchGate gate = new FirstLaunchGate();
        MockEnvironment environment = new MockEnvironment()
            .withProperty("jabiz.first-launch.config", "/nowhere/first-launch.yml")
            .withProperty("jabiz.ops.verify-restore.enabled", "true");

        runner(environment, gate).afterSingletonsInstantiated();

        assertThat(gate.isOpen()).isTrue();
    }
}
