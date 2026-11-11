package com.jabiz.quizbuks.setup;

import static org.assertj.core.api.Assertions.assertThat;

import com.jabiz.security.ProtectedPermissions;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ByteArrayResource;

/**
 * F-C8, F-C9: {@code mail.text.write} is a protected permission (decision D48): whoever holds it saves, proposes and
 * publishes the texts and the letterhead of every mail, so one administrator cannot give it to themselves or to anyone
 * alone; another administrator approves (the finance administrator, for a content administrator). Setting
 * {@code jabiz.security.protected-grants.permissions} replaces the platform's list, so the list here must hold all of
 * the platform's default: a permission the platform adds to it later must not be dropped by this configuration.
 */
class MailProtectedGrantsTest {

    private static List<String> configured() throws IOException {
        try (InputStream in = MailProtectedGrantsTest.class.getResourceAsStream("/application.yml")) {
            YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
            factory.setResources(new ByteArrayResource(in.readAllBytes()));
            Properties properties = factory.getObject();
            return Arrays.stream(properties.getProperty("jabiz.security.protected-grants.permissions", "").split(","))
                .map(String::strip).filter(code -> !code.isEmpty()).toList();
        }
    }

    @Test
    void theConfiguredListHoldsEveryPermissionTheDefaultHoldsAndTheMailTexts() throws IOException {
        List<String> configured = configured();
        assertThat(configured).containsAll(ProtectedPermissions.DEFAULT).contains("mail.text.write");
        assertThat(ProtectedPermissions.problems(configured)).isEmpty();
        ProtectedPermissions permissions = ProtectedPermissions.of(configured);
        assertThat(permissions.isProtected("mail.text.write")).isTrue();
        // Reading them is not protected.
        assertThat(permissions.isProtected("mail.text.read")).isFalse();
    }
}
