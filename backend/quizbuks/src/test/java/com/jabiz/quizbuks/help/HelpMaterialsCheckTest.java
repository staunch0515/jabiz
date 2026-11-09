package com.jabiz.quizbuks.help;

import com.jabiz.runtime.check.CheckProblem;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-C10: the materials of one entry and language together above {@code qb.help.manual-max-bytes} are a startup error;
 * without a reader of materials (part b registers them) nothing is read.
 */
class HelpMaterialsCheckTest {

    @Test
    void materialsAboveTheLimitAreAnErrorPerEntryAndLanguage() {
        List<CheckProblem> problems = HelpMaterialsCheck.problems(List.of(
            new HelpMaterialsCheck.Materials("app", "zh", 150_000), new HelpMaterialsCheck.Materials("app", "zh", 60_000),
            new HelpMaterialsCheck.Materials("app", "ja", 150_000),
            new HelpMaterialsCheck.Materials("sponsor", "zh", 204_800)), 204_800);
        assertThat(problems).singleElement().satisfies(problem -> {
            assertThat(problem.severity()).isEqualTo(CheckProblem.Severity.ERROR);
            assertThat(problem.location()).contains("app/zh");
            assertThat(problem.message()).contains("210000");
        });
        assertThat(HelpMaterialsCheck.problems(List.of(), 1)).isEmpty();
    }

    @Test
    void withoutReadersNothingIsRead() {
        MockEnvironment environment = new MockEnvironment().withProperty("spring.flyway.url",
            "jdbc:postgresql://nowhere.invalid:1/none");
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        HelpMaterialsCheck check = new HelpMaterialsCheck(environment,
            beans.getBeanProvider(HelpMaterialsCheck.HelpMaterials.class));
        assertThat(check.check()).isEmpty();
    }
}
