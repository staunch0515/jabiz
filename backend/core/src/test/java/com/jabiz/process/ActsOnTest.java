package com.jabiz.process;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Processes as actions on an entity (docs/design/16-content-authoring.md section 3). */
class ActsOnTest {

    record In(String storyId) {}
    record Out(String storyId) {}

    private static ProcessDefinition<In, Out, ProcessContext> submit() {
        return ProcessDefinition.single("STORY_SUBMIT", 1, In.class, Out.class, (in, ctx) -> new Out(in.storyId()));
    }

    @Test
    void aProcessActsOnNothingByDefault() {
        assertThat(submit().actsOn()).isNull();
    }

    @Test
    void theEntityAndConditionSurviveTheOtherCopies() {
        ProcessDefinition<In, Out, ProcessContext> def = submit()
            .actsOn("Story", "storyId", a -> a.whenField("status", "DRAFT", "REJECTED"))
            .withPermissions("story.submit")
            .asInternal();

        assertThat(def.actsOn()).isEqualTo(new ActsOn("Story", "storyId", "status", List.of("DRAFT", "REJECTED")));
        assertThat(def.permissions()).containsExactly("story.submit");
        assertThat(def.internal()).isTrue();
        assertThat(submit().actsOn("Story", "storyId").actsOn())
            .isEqualTo(new ActsOn("Story", "storyId", null, List.of()));
    }

    @Test
    void theBuilderDeclaresItToo() {
        ProcessDefinition<In, Out, ProcessContext> def = ProcessDefinition.define("STORY_PUBLISH", 1, In.class,
            Out.class, ProcessContext.class, pb -> pb
                .contextFactory((start, in) -> new ProcessContext(start))
                .outputMapper(ctx -> new Out("x"))
                .actsOn("Story", "storyId", a -> a.whenField("status", "APPROVED"))
                .compute("Publish", (metadata, ctx) -> { }));
        assertThat(def.actsOn().whenValues()).containsExactly("APPROVED");

        ProcessDefinition<In, Out, ProcessContext> plain = ProcessDefinition.define("STORY_VIEW", 1, In.class,
            Out.class, ProcessContext.class, pb -> pb
                .contextFactory((start, in) -> new ProcessContext(start))
                .outputMapper(ctx -> new Out("x"))
                .actsOn("Story", "storyId")
                .compute("View", (metadata, ctx) -> { }));
        assertThat(plain.actsOn().whenField()).isNull();
    }

    @Test
    void conditionsNeedAFieldAndValues() {
        assertThatThrownBy(() -> submit().actsOn("Story", "storyId", a -> a.whenField("status")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at least one value");
        assertThatThrownBy(() -> new ActsOn("Story", "storyId", null, List.of("DRAFT")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> submit().actsOn(" ", "storyId"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("entity must not be blank");
        assertThatThrownBy(() -> submit().actsOn("Story", null))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("input must not be blank");
    }
}
