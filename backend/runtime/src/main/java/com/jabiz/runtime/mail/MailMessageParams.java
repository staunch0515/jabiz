package com.jabiz.runtime.mail;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.StepSpec;
import com.jabiz.query.BoundValue;
import com.jabiz.runtime.process.StepHandler;
import com.jabiz.runtime.storage.Rows;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Reads the parameters a recorded mail was sent with (docs/design/18-numbering-approvals-tasks.md section 5.6), for a
 * process that continues what the mail started: the link of the mail carries only its one-time token, and what the
 * sending process knew (such as the sign-in entry of a registration, decision D36) is read back from the message the
 * used token belongs to ({@link MailTokens.Consumed#messageId()}):
 * <pre>{@code
 * .step("Read the mail", MailMessageParams.of(ctx -> consumed(ctx).messageId(), "mailParams"))
 * }</pre>
 * The context receives a {@code Map<String, String>} under the target key: empty when no message is named (a token
 * that was not accepted) or the message does not exist.
 */
@Component
public class MailMessageParams<C extends ProcessContext> implements StepHandler<MailMessageParams.Metadata<C>, C> {

    private static final TypeReference<Map<String, Object>> PARAMS = new TypeReference<>() {};

    /**
     * @param messageId the message, computed on the context; null for none
     * @param targetKey context key receiving the parameters
     */
    public record Metadata<C>(Function<C, String> messageId, String targetKey) {
        public Metadata {
            Objects.requireNonNull(messageId, "messageId must not be null");
            Objects.requireNonNull(targetKey, "targetKey must not be null");
        }
    }

    public static <C extends ProcessContext> StepSpec<Metadata<C>, C> of(Function<C, String> messageId,
        String targetKey) {
        return StepSpec.of(MailMessageParams.class, new Metadata<>(messageId, targetKey));
    }

    private final StorageAdapterRegistry storages;
    private final JsonMapper json;
    private final String poolRef;

    public MailMessageParams(StorageAdapterRegistry storages, JsonMapper json,
        @Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.storages = storages;
        this.json = json;
        this.poolRef = poolRef;
    }

    @Override
    public Mono<Void> execute(Metadata<C> metadata, C ctx) {
        return Mono.defer(() -> {
            String messageId = metadata.messageId().apply(ctx);
            if (messageId == null) {
                ctx.put(metadata.targetKey(), Map.<String, String>of());
                return Mono.empty();
            }
            return storages.getEngine(poolRef)
                .select("SELECT params FROM sys_mail_message WHERE message_id = :id",
                    Map.of("id", BoundValue.of(messageId)))
                .next()
                .map(row -> {
                    Map<String, String> values = new LinkedHashMap<>();
                    json.readValue(Rows.string(row.get("params")), PARAMS).forEach((name, value) -> {
                        if (value != null) {
                            values.put(name, String.valueOf(value));
                        }
                    });
                    return Map.copyOf(values);
                })
                .defaultIfEmpty(Map.of())
                .doOnNext(values -> ctx.put(metadata.targetKey(), values))
                .then();
        });
    }
}
