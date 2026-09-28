package com.jabiz.culture;

import com.jabiz.entity.Violation;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.LoadEntity;
import com.jabiz.runtime.publicread.FileAccess;
import jakarta.validation.constraints.NotBlank;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

import static com.jabiz.culture.Culture.*;

/** Teaching resources go public after a check that they say who and how long they are for (section 3.8). */
@Configuration
public class CultureResources {

    public static final String PUBLISH = "CULTURE_RESOURCE_PUBLISH";
    public static final String UNPUBLISH = "CULTURE_RESOURCE_UNPUBLISH";

    public record ResourceInput(@NotBlank String resourceId) {}

    public record ResourceOutput(String resourceId, String status) {}

    private static final String RESOURCE_ID = "resourceId";
    private static final String ROW = "resource";
    private static final String STATUS = "status";
    private static final String OFFLINE_FILES = "offlineFiles";

    private static ProcessDefinition<ResourceInput, ResourceOutput, ProcessContext> define(String name,
        String description, String from, String to, boolean check) {
        return ProcessDefinition.define(name, 1, ResourceInput.class, ResourceOutput.class, ProcessContext.class,
            pb -> pb
                .description(description)
                .permissions(RESOURCE_PUBLISH)
                .actsOn(RESOURCE, RESOURCE_ID, a -> a.whenField(STATUS, from))
                .contextFactory((start, in) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(RESOURCE_ID, in.resourceId());
                    return ctx;
                })
                .outputMapper(ctx -> {
                    EntityInstance resource = ctx.get(ROW, EntityInstance.class);
                    return new ResourceOutput(String.valueOf(resource.id()),
                        ctx.hasViolations() ? String.valueOf((Object) resource.get(STATUS)) : to);
                })
                .step("Load the resource", LoadEntity.by(dataset(RESOURCE), RESOURCE_ID, ROW))
                .compute("Change the status", (metadata, ctx) -> {
                    EntityInstance resource = ctx.get(ROW, EntityInstance.class);
                    if (!Workflow.inState(ctx, resource, STATUS, from)) {
                        return;
                    }
                    if (check) {
                        checkPublishable(ctx, resource);
                        if (ctx.hasViolations()) {
                            return;
                        }
                    }
                    ctx.changes().update(RESOURCE, resource.id(), resource.version(), Workflow.mapOf(STATUS, to));
                    if (!check) {
                        ctx.put(OFFLINE_FILES, Workflow.files(List.of(resource), "pdfFileId"));
                    }
                })
                // Taking a resource offline stops its PDF at once; publishing leaves nothing to invalidate.
                .afterCommit("Stop serving the PDF publicly",
                    FileAccess.invalidate(ctx -> Workflow.collected(ctx, OFFLINE_FILES))));
    }

    private static void checkPublishable(ProcessContext ctx, EntityInstance resource) {
        if (!CultureEntities.hasEnglish(resource.get("description"))) {
            ctx.reject(violation(resource, "description", "ENGLISH_REQUIRED", "The English description is missing"));
        }
        if (resource.get("ageGroup") == null) {
            ctx.reject(violation(resource, "ageGroup", "AGE_GROUP_REQUIRED", "The age group is missing"));
        }
        if (resource.get("durationMinutes") == null) {
            ctx.reject(violation(resource, "durationMinutes", "DURATION_REQUIRED", "The duration is missing"));
        }
    }

    private static Violation violation(EntityInstance on, String field, String code, String message) {
        return new Violation(RESOURCE + "." + field, code, message,
            Map.of("entity", RESOURCE, "id", String.valueOf(on.id())));
    }

    public static final ProcessDefinition<ResourceInput, ResourceOutput, ProcessContext> PUBLISH_PROCESS =
        define(PUBLISH, "Publishes a teaching resource that names its age group and duration.", DRAFT, PUBLISHED,
            true);

    public static final ProcessDefinition<ResourceInput, ResourceOutput, ProcessContext> UNPUBLISH_PROCESS =
        define(UNPUBLISH, "Takes a teaching resource offline.", PUBLISHED, DRAFT, false);

    @Bean
    ProcessDefinition<ResourceInput, ResourceOutput, ProcessContext> cultureResourcePublishProcess() {
        return PUBLISH_PROCESS;
    }

    @Bean
    ProcessDefinition<ResourceInput, ResourceOutput, ProcessContext> cultureResourceUnpublishProcess() {
        return UNPUBLISH_PROCESS;
    }
}
