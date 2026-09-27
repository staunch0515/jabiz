package com.jabiz.runtime.process;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.ResolvableType;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Startup checks of processes declared as actions on an entity (docs/design/16-content-authoring.md section 3). */
class ActsOnChecksTest {

    record StoryInput(UUID storyId) {}
    record TextInput(String storyId) {}
    record NumberInput(Long storyId) {}
    record Out(String result) {}

    private static final EntityDefinition STORY = EntityDefinition.define("Story", eb -> {
        eb.physicalTable("t_story");
        eb.primaryKey("storyId");
        eb.field("storyId", f -> f.physicalColumn("story_id").asSemanticIdentity("urn:story"));
        eb.field("status", f -> f.physicalColumn("status").asCode("urn:status", "DRAFT", "PUBLISHED"));
        eb.field("featured", f -> f.physicalColumn("featured").asBool());
        eb.field("title", f -> f.physicalColumn("title").asText(10));
        eb.temporal();
    });

    private static final EntityDefinition TAG = EntityDefinition.define("Tag", eb -> {
        eb.physicalTable("t_tag");
        eb.primaryKey("code");
        eb.field("code", f -> f.physicalColumn("code").asText(10));
    });

    private static <I> ProcessDefinition<I, Out, ProcessContext> process(String name, Class<I> input) {
        return ProcessDefinition.single(name, 1, input, Out.class, (in, ctx) -> new Out("ok"));
    }

    private static List<CheckProblem> check(ProcessDefinition<?, ?, ?>... definitions) {
        StaticListableBeanFactory declared = new StaticListableBeanFactory();
        for (int i = 0; i < definitions.length; i++) {
            declared.addBean("process" + i, definitions[i]);
        }
        ProcessRegistry processes = new ProcessRegistry(
            declared.getBeanProvider(ResolvableType.forClass(ProcessDefinition.class)));
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("story", STORY);
        beans.registerSingleton("tag", TAG);
        return new ActsOnChecks(processes, new EntityDefinitionRegistry(beans.getBeanProvider(EntityDefinition.class)))
            .check();
    }

    @Test
    void wellFormedActionsPass() {
        assertThat(check(
            process("SUBMIT", StoryInput.class).actsOn("Story", "storyId", a -> a.whenField("status", "DRAFT")),
            process("FEATURE", TextInput.class).actsOn("Story", "storyId", a -> a.whenField("featured", "false")),
            process("RENAME", TextInput.class).actsOn("Tag", "storyId"),
            process("PLAIN", StoryInput.class))).isEmpty();
    }

    @Test
    void everyProblemIsReported() {
        List<CheckProblem> problems = check(
            process("GHOST", StoryInput.class).actsOn("Ghost", "storyId"),
            process("MISSING_INPUT", StoryInput.class).actsOn("Story", "id"),
            process("WRONG_TYPE", NumberInput.class).actsOn("Story", "storyId"),
            process("NOT_A_RECORD", String.class).actsOn("Story", "storyId"),
            process("NO_FIELD", StoryInput.class).actsOn("Story", "storyId", a -> a.whenField("state", "DRAFT")),
            process("TEXT_FIELD", StoryInput.class).actsOn("Story", "storyId", a -> a.whenField("title", "x")),
            process("UNKNOWN_VALUE", StoryInput.class).actsOn("Story", "storyId", a -> a.whenField("status", "GONE")),
            process("NOT_A_FLAG", StoryInput.class).actsOn("Story", "storyId", a -> a.whenField("featured", "yes")),
            process("TAG_BY_UUID", StoryInput.class).actsOn("Tag", "storyId"));

        assertThat(problems).allSatisfy(p -> {
            assertThat(p.category()).isEqualTo("PROCESS");
            assertThat(p.severity()).isEqualTo(CheckProblem.Severity.ERROR);
        });
        assertThat(problems).extracting(p -> p.location() + ": " + p.message()).containsExactlyInAnyOrder(
            "GHOST v1: acts on unknown entity Ghost",
            "MISSING_INPUT v1: acts on Story through input 'id', which StoryInput does not have",
            "WRONG_TYPE v1: input 'storyId' is a Long, which cannot hold the primary key of Story",
            "NOT_A_RECORD v1: acts on Story but its input String is not a record",
            "NO_FIELD v1: condition field 'state' is not a field of Story",
            "TEXT_FIELD v1: condition field 'title' must be a Code or Bool field",
            "UNKNOWN_VALUE v1: condition values [GONE] are not values of Story.status [DRAFT, PUBLISHED]",
            "NOT_A_FLAG v1: condition values [yes] are not values of Story.featured [true, false]",
            "TAG_BY_UUID v1: input 'storyId' is a UUID, which cannot hold the primary key of Tag");
    }
}
