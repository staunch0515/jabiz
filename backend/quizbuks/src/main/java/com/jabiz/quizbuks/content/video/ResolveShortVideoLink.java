package com.jabiz.quizbuks.content.video;

import com.jabiz.external.ExternalHttp;
import com.jabiz.external.ExternalServices;
import com.jabiz.process.BlockingStep;
import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessContext;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Asks b23.tv where a short link leads ({@code QB_VIDEO_LINK_RESOLVE}, Q99b): one {@code GET} of the code, whose
 * answer must be a redirect (the platform never follows one) to one Bilibili video. Anything else is a violation of
 * the link. The call reads and changes nothing here or there.
 */
@Component
public class ResolveShortVideoLink implements BlockingStep<NoMetadata, ProcessContext> {

    static final Duration TIMEOUT = Duration.ofSeconds(8);

    private final ExternalServices services;

    public ResolveShortVideoLink(ExternalServices services) {
        this.services = services;
    }

    @Override
    public void run(NoMetadata metadata, ProcessContext ctx) {
        String url = ctx.get(VideoLinkProcesses.INPUT, VideoLinkProcesses.ResolveInput.class).url();
        Optional<String> code = ShortVideoLinks.code(url);
        if (code.isEmpty()) {
            ctx.reject(VideoLinkProcesses.notResolved("This is not a short link to one video"));
            return;
        }
        ExternalHttp.Response response = services.http(ShortVideoLinks.SERVICE)
            .send(ExternalHttp.Request.get("/" + code.get()).timeout(TIMEOUT));
        List<String> locations = response.status() / 100 == 3 ? response.headers().get("Location") : null;
        Optional<String> video = locations == null || locations.isEmpty() ? Optional.empty()
            : ShortVideoLinks.videoOf(locations.getFirst());
        if (video.isEmpty()) {
            ctx.reject(VideoLinkProcesses.notResolved("The short link does not lead to one Bilibili video"));
            return;
        }
        ctx.put(VideoLinkProcesses.RESOLVED, video.get());
    }

}
