package com.jabiz.quizbuks.content.video;

import com.jabiz.entity.Violation;
import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.StepSpec;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.content.ContentCodes;
import com.jabiz.quizbuks.identity.CallerChecks;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * {@code QB_VIDEO_LINK_RESOLVE} (Q99b): the full address of a video a short link leads to, for the sponsor console to
 * put in the material before saving it. It writes nothing; the material is saved as typed by hand.
 */
public final class VideoLinkProcesses {

    public static final String RESOLVE = "QB_VIDEO_LINK_RESOLVE";

    static final String INPUT = "videoLink.input";
    static final String RESOLVED = "videoLink.resolved";

    public record ResolveInput(@NotBlank @Size(max = 2_000) String url) {}

    /** @param url the video's full address */
    public record ResolveOutput(String url) {}

    private VideoLinkProcesses() {}

    public static ProcessDefinition<ResolveInput, ResolveOutput, ProcessContext> resolve() {
        return ProcessDefinition.define(RESOLVE, 1, ResolveInput.class, ResolveOutput.class, ProcessContext.class,
            pb -> pb
                .description("Gives the full address of the video a short link (b23.tv) leads to.")
                .permissions(QbPermissions.CONTENT_WRITE)
                .requiresVerifiedEmail()
                .steps(CallerChecks::refuseBanned)
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(ctx -> new ResolveOutput(ctx.get(RESOLVED, String.class)))
                .step("Ask where the short link leads", StepSpec.of(ResolveShortVideoLink.class, NoMetadata.INSTANCE)));
    }

    static Violation notResolved(String message) {
        return new Violation("url", ContentCodes.VIDEO_LINK_NOT_RESOLVED, message, Map.of());
    }
}
