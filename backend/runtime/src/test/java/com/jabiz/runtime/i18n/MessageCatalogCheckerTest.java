package com.jabiz.runtime.i18n;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.i18n.MessageCatalog;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessageCatalogCheckerTest {

    private static final MessageCatalog PLATFORM_ONLY = new MessagesConfig().messageCatalog(List.of(), Locale.ENGLISH);

    private static EntityDefinitionRegistry registryOf(EntityDefinition... definitions) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        for (int i = 0; i < definitions.length; i++) {
            beans.addBean("entity" + i, definitions[i]);
        }
        return new EntityDefinitionRegistry(beans.getBeanProvider(EntityDefinition.class));
    }

    private static final EntityDefinition WITH_RULES = EntityDefinition.define("Probe", eb -> {
        eb.physicalTable("t_probe");
        eb.primaryKey("id");
        eb.field("id", f -> f.physicalColumn("f_id"));
        eb.field("qty", f -> f.physicalColumn("f_qty")
            .rule("QTY_POSITIVE", "RANGE", Map.of("min", 1), v -> true)
            .rule("QTY_SERVER_ONLY", v -> true));
    });

    @Test
    void platformCodesAloneAreComplete() {
        assertThatCode(() -> new MessageCatalogChecker(PLATFORM_ONLY, registryOf()).afterSingletonsInstantiated())
            .doesNotThrowAnyException();
    }

    @Test
    void everyMissingRuleMessageIsReportedAtOnce() {
        MessageCatalogChecker checker = new MessageCatalogChecker(PLATFORM_ONLY, registryOf(WITH_RULES));

        assertThat(checker.requiredCodes()).contains("REQUIRED", "QTY_POSITIVE", "QTY_SERVER_ONLY");
        assertThatThrownBy(checker::afterSingletonsInstantiated)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Missing error messages (6)")
            .hasMessageContaining("QTY_POSITIVE [zh]")
            .hasMessageContaining("QTY_POSITIVE [ja]")
            .hasMessageContaining("QTY_SERVER_ONLY [en]");
    }
}
