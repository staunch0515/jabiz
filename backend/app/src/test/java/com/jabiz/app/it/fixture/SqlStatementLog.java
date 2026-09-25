package com.jabiz.app.it.fixture;

import io.r2dbc.proxy.ProxyConnectionFactory;
import io.r2dbc.proxy.core.QueryInfo;
import io.r2dbc.spi.ConnectionFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Records every SQL statement the application sends through R2DBC, for tests that assert what was (not) executed.
 * Active only in contexts started with {@code it.sql-log.enabled=true}.
 */
@Configuration
@ConditionalOnProperty(name = "it.sql-log.enabled", havingValue = "true")
public class SqlStatementLog {

    public static final List<String> STATEMENTS = new CopyOnWriteArrayList<>();

    @Bean
    static BeanPostProcessor recordingConnectionFactory() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof ConnectionFactory factory) {
                    return ProxyConnectionFactory.builder(factory)
                        .onAfterQuery(execution -> execution.getQueries().stream()
                            .map(QueryInfo::getQuery)
                            .forEach(STATEMENTS::add))
                        .build();
                }
                return bean;
            }
        };
    }
}
