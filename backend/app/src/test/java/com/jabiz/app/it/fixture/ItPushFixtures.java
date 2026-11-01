package com.jabiz.app.it.fixture;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.push.PushTemplate;
import com.jabiz.runtime.push.SendPush;
import jakarta.validation.constraints.NotBlank;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * Push notifications of the tests (decision D69): a template for every entry, one for the portal only, and a process
 * that sends either to a user (and can fail after sending, to show that a rolled back process pushes nothing).
 */
public final class ItPushFixtures {

    public static final String PERMISSION = "it.push";

    public static final PushTemplate PING = PushTemplate.define("it.ping", t -> t.param("item"));
    public static final PushTemplate PORTAL = PushTemplate.define("it.portal-ping", t -> t.param("item")
        .entries("portal"));

    /**
     * @param template {@code ping} or {@code portal}
     * @param link     the page a tap opens, relative to the entry's frontend
     * @param fail     whether the process fails after the push step
     */
    public record SendInput(@NotBlank String template, @NotBlank String userId, String value, String link,
        Boolean fail) {}

    public record Done(String userId) {}

    private static final String INPUT = "input";

    public static final ProcessDefinition<SendInput, Done, ProcessContext> SEND =
        ProcessDefinition.define("IT_PUSH_SEND", 1, SendInput.class, Done.class, ProcessContext.class, pb -> pb
            .permissions(PERMISSION)
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(INPUT, input);
                return ctx;
            })
            .outputMapper(ctx -> new Done(input(ctx).userId()))
            .step("Push the ping", SendPush.when(ctx -> "ping".equals(input(ctx).template()), PING,
                ctx -> input(ctx).userId(), Map.of("item", ctx -> input(ctx).value()), ctx -> input(ctx).link()))
            .step("Push the portal's ping", SendPush.when(ctx -> "portal".equals(input(ctx).template()), PORTAL,
                ctx -> input(ctx).userId(), Map.of("item", ctx -> input(ctx).value()), ctx -> input(ctx).link()))
            .compute("Fail if asked", (metadata, ctx) -> {
                if (Boolean.TRUE.equals(input(ctx).fail())) {
                    throw new IllegalStateException("failing after the push, as asked");
                }
            }));

    private static SendInput input(ProcessContext ctx) {
        return (SendInput) ctx.get(INPUT);
    }

    private ItPushFixtures() {}

    @Configuration
    static class Beans {

        @Bean
        PushTemplate itPingPush() {
            return PING;
        }

        @Bean
        PushTemplate itPortalPingPush() {
            return PORTAL;
        }

        @Bean
        ProcessDefinition<SendInput, Done, ProcessContext> itPushSend() {
            return SEND;
        }
    }
}
