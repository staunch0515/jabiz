package com.jabiz.runtime.file;

import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.security.MaskedLink;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@code FILE_FETCH} (docs/design/14-files.md section 12, decision D75): downloads the image a link gives, once, and
 * stores it through the upload pipeline as a file of the given policy uploaded by the process's actor, who must hold
 * the policy's upload permission. {@code subProcessOnly()}: business processes use the step
 * {@code FetchRemoteImage.of(policy, ctx -> link).into(key)}, which calls it; its operation record shows the link
 * without its query. The link is not kept anywhere else and never fetched again.
 */
@Configuration
public class FetchProcesses {

    public static final String FETCH = "FILE_FETCH";

    static final String INPUT = "input";
    static final String OUTPUT = "output";

    /**
     * @param policy    the file policy the image is stored under; it must accept an image type
     * @param imageLink the {@code https} link; recorded without its query ({@link MaskedLink})
     */
    public record FetchInput(String policy, @MaskedLink String imageLink) {

        @Override
        public String toString() {
            return "FetchInput[policy=" + policy + ", imageLink="
                + com.jabiz.file.RemoteImageLink.shown(imageLink) + "]";
        }
    }

    public static final ProcessDefinition<FetchInput, FileProcesses.FileInfo, ProcessContext> FETCH_PROCESS =
        ProcessDefinition.define(FETCH, 1, FetchInput.class, FileProcesses.FileInfo.class, ProcessContext.class,
            pb -> pb
                .description("Downloads the image a link gives and stores it as the actor's upload.")
                .permissions(FilePermissions.FETCH)
                .subProcessOnly()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get(OUTPUT, FileProcesses.FileInfo.class))
                .step("Download and store the image", FetchImage.class, NoMetadata.INSTANCE));

    @Bean
    ProcessDefinition<FetchInput, FileProcesses.FileInfo, ProcessContext> fileFetchProcess() {
        return FETCH_PROCESS;
    }
}
