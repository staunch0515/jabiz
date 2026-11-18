package com.jabiz.firstlaunch;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FirstLaunchTypesTest {

    private static final FirstLaunchConfig.Files NO_FILES = new FirstLaunchConfig.Files() {
        @Override
        public byte[] read(String relativePath) {
            throw new IllegalArgumentException("No file " + relativePath);
        }

        @Override
        public boolean exists(String relativePath) {
            return false;
        }
    };

    @Test
    void stepsAreBuiltByTheirFactories() {
        FirstLaunchStep step = FirstLaunchStep.run("op", "QB_OPERATOR_SAVE", results -> "input");
        assertThat(step.await()).isFalse();
        assertThat(step.asAccount()).isNull();
        assertThat(step.describe()).isEqualTo("op");

        FirstLaunchStep acting = FirstLaunchStep.await("ready", "QB_TREE_READY", results -> "x")
            .asAccount("B").describedAs("The tree is active");
        assertThat(acting.await()).isTrue();
        assertThat(acting.asAccount()).isEqualTo("B");
        assertThat(acting.describe()).isEqualTo("The tree is active");
        assertThat(FirstLaunchStep.accountStepKey("B")).isEqualTo("account:B");
        assertThatThrownBy(() -> FirstLaunchStep.run(null, "P", results -> null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void resultsGiveTypedOutputs() {
        FirstLaunchResults results = new FirstLaunchResults() {
            @Override
            public Optional<Object> output(String stepKey) {
                return Optional.ofNullable(Map.<String, Object>of("op", 42).get(stepKey));
            }

            @Override
            public String userId(String accountKey) {
                return "u-" + accountKey;
            }
        };

        assertThat(results.output("op", Integer.class)).isEqualTo(42);
        assertThatThrownBy(() -> results.output("none", Integer.class)).hasMessageContaining("none");
    }

    @Test
    void theConfigurationFindsAccountsAndHidesAddresses() {
        FirstLaunchAccount account = new FirstLaunchAccount("A", "a@example.com", "Suzuki", null,
            List.of("ADMIN"), true);
        FirstLaunchConfig config = new FirstLaunchConfig("hash", null, "zh", null, List.of(account), NO_FILES);

        assertThat(config.account("A")).contains(account);
        assertThat(config.account("B")).isEmpty();
        assertThat(config.document()).isEmpty();
        assertThat(config.toString()).doesNotContain("a@example.com");
        assertThat(account.toString()).doesNotContain("a@example.com").contains("A");
        assertThat(new FirstLaunchAccount("C", null, null, null, null, false).roles()).isEmpty();
    }

    @Test
    void handlersHaveDefaults() {
        FirstLaunchHandler handler = new FirstLaunchHandler() {
            @Override
            public String name() {
                return "app";
            }

            @Override
            public List<FirstLaunchProblem> validate(FirstLaunchConfig config) {
                return List.of();
            }

            @Override
            public List<FirstLaunchStep> plan(FirstLaunchConfig config) {
                return List.of();
            }
        };

        assertThat(handler.actingProcesses()).isEmpty();
        assertThat(handler.settableParams()).isEmpty();
        assertThat(handler.remaining(null)).isEmpty();
    }

    @Test
    void problemsFormatForTheLog() {
        assertThat(new FirstLaunchProblem("accounts[0].email", "is not an address", "fix it").format())
            .isEqualTo("accounts[0].email | is not an address | fix it");
        assertThat(new FirstLaunchProblem("version", "must be 1", null).format()).isEqualTo("version | must be 1");
    }
}
