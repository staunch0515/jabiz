package com.jabiz.runtime.mail;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.StepSpec;
import com.jabiz.runtime.process.StepHandler;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;

/**
 * Platform step: puts the mail layout of a language in effect ({@link MailLayouts#effective}; decision D72) into the
 * context as a {@link MailLayouts.Chosen}, for the platform's processes that record mail in their own transaction and
 * send it after the commit ({@code TASK_NOTIFY}): they record the version and put the mail on it when sending. Not for
 * business processes, which send mail with {@code SendMail}.
 */
@Component
public class LoadMailLayout<C extends ProcessContext> implements StepHandler<LoadMailLayout.Metadata<C>, C> {

    public record Metadata<C>(Function<C, Locale> locale, String targetKey) {
        public Metadata {
            Objects.requireNonNull(locale, "locale must not be null");
            Objects.requireNonNull(targetKey, "targetKey must not be null");
        }
    }

    public static <C extends ProcessContext> StepSpec<Metadata<C>, C> of(Function<C, Locale> locale,
        String targetKey) {
        return StepSpec.of(LoadMailLayout.class, new Metadata<>(locale, targetKey));
    }

    private final MailLayouts layouts;

    LoadMailLayout(MailLayouts layouts) {
        this.layouts = layouts;
    }

    @Override
    public Mono<Void> execute(Metadata<C> metadata, C ctx) {
        return Mono.defer(() -> layouts.effective(metadata.locale().apply(ctx))
            .doOnNext(chosen -> ctx.put(metadata.targetKey(), chosen))
            .then());
    }
}
