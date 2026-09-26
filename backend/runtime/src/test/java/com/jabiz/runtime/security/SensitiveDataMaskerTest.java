package com.jabiz.runtime.security;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.ValidationException;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import com.jabiz.security.Sensitive;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SensitiveDataMaskerTest {

    record Pin(@Sensitive String code, String hint) {}

    record Input(String userName, Pin pin, List<Map<String, Object>> rows, String newPassword) {}

    record Output(String userId, @Sensitive String apiKey) {}

    static final EntityDefinition ACCOUNT = EntityDefinition.define("Account", eb -> {
        eb.physicalTable("t_account");
        eb.primaryKey("accountId");
        eb.field("accountId", f -> f.physicalColumn("account_id").asSemanticIdentity("urn:account"));
        eb.field("name", f -> f.physicalColumn("name").asText(50));
        eb.field("secretHash", f -> f.physicalColumn("secret_hash").asText(100).sensitive());
    });

    static final ProcessDefinition<Input, Output, ProcessContext> PROCESS = ProcessDefinition.single(
        "P", 1, Input.class, Output.class, (input, ctx) -> new Output("u", "k"));

    private final JsonMapper json = JsonMapper.builder().build();
    private final SensitiveDataMasker masker = masker();

    private SensitiveDataMasker masker() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("account", ACCOUNT);
        return new SensitiveDataMasker(json, new EntityDefinitionRegistry(beans.getBeanProvider(EntityDefinition.class)),
            List.of(PROCESS), List.of("password", "token"));
    }

    @Test
    void namesComeFromEntitiesAnnotationsAndFragments() {
        assertThat(masker.isSensitive("secretHash")).isTrue();
        assertThat(masker.isSensitive("SECRETHASH")).isTrue();
        assertThat(masker.isSensitive("code")).isTrue();
        assertThat(masker.isSensitive("apiKey")).isTrue();
        assertThat(masker.isSensitive("newPassword")).isTrue();
        assertThat(masker.isSensitive("refreshToken")).isTrue();
        assertThat(masker.isSensitive("hint")).isFalse();
        assertThat(masker.isSensitive(null)).isFalse();
    }

    @Test
    void summariesMaskSecretsAtAnyDepth() {
        String summary = masker.summary(new Input("alice", new Pin("1234", "birthday"),
            List.of(Map.of("secretHash", "$2a$xyz", "name", "n")), "S3cret!"));

        assertThat(summary).doesNotContain("1234", "$2a$xyz", "S3cret!")
            .contains("\"code\":\"***\"", "\"secretHash\":\"***\"", "\"newPassword\":\"***\"", "birthday", "alice");
        assertThat(masker.summary(null)).isNull();
    }

    @Test
    void longSummariesKeepOnlyTheirLength() {
        String summary = masker.summary(Map.of("text", "x".repeat(SensitiveDataMasker.MAX_SUMMARY_LENGTH)));
        assertThat(summary).startsWith("{\"truncated\":true,\"length\":");
    }

    @Test
    void outputsLoseTheirSecretsAsNulls() {
        assertThat(masker.withoutSecrets(new Output("u1", "key-123"))).isEqualTo("{\"userId\":\"u1\",\"apiKey\":null}");
        Map<String, Object> expected = new java.util.LinkedHashMap<>();
        expected.put("userId", "u1");
        expected.put("apiKey", null);
        assertThat(masker.toJsonWithoutSecrets(new Output("u1", "key-123"))).isEqualTo(expected);
        assertThat(masker.toJsonWithoutSecrets(null)).isNull();
    }

    @Test
    void readsLeaveSensitiveFieldsOut() {
        EntityInstance account = new EntityInstance("a1", "Account", 1, null,
            Map.of("accountId", "a1", "name", "n", "secretHash", "$2a$xyz"));

        assertThat(masker.hide(account).attributes()).containsOnlyKeys("accountId", "name");
        EntityInstance plain = new EntityInstance("a1", "Account", 1, null, Map.of("name", "n"));
        assertThat(masker.hide(plain)).isSameAs(plain);
        EntityInstance other = new EntityInstance("x", "Other", 1, null, Map.of("secretHash", "kept"));
        assertThat(masker.hide(other)).isSameAs(other);
        assertThat(masker.hide(ACCOUNT, Map.of("name", "n", "secretHash", "h"))).containsOnlyKeys("name");
        assertThat(masker.hide(null)).isNull();
        assertThat(account.toString()).doesNotContain("$2a$xyz");
    }

    @Test
    void sensitiveFieldsCannotBeWrittenThroughGenericApis() {
        assertThatThrownBy(() -> SensitiveDataMasker.rejectWrites(ACCOUNT, Map.of("secretHash", "h")))
            .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.violations())
                .singleElement().satisfies(v -> {
                    assertThat(v.field()).isEqualTo("secretHash");
                    assertThat(v.ruleCode()).isEqualTo("SENSITIVE_FIELD");
                }));
        SensitiveDataMasker.rejectWrites(ACCOUNT, Map.of("name", "n"));
    }
}
