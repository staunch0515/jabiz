package com.jabiz.quizbuks.topic;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.QbPermissions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The word list of topics (F-S9): its entity, the administrators' dataset (read with {@code qb.admin.topic.read},
 * written by the processes only) and headquarters' processes. Takers and sponsors read the words through their own
 * templates ({@code qb.app.topics}, {@code qb.sponsor.topics}).
 */
@Configuration
public class TopicConfig {

    private final String poolRef;

    TopicConfig(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.poolRef = poolRef;
    }

    @Bean
    EntityDefinition qbTopicEntity() {
        return QbTopics.ENTITY;
    }

    @Bean
    DatasetDefinition qbTopicDataset() {
        return DatasetDefinition.define(QbTopics.DEFAULT_DATASET, d -> d
            .targetEntityType(QbTopics.TOPIC)
            .asDefault()
            .permissions(QbPermissions.ADMIN_TOPIC_READ, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.processOnlyWrites())
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    @Bean
    ProcessDefinition<TopicProcesses.AddInput, TopicProcesses.TopicOutput, ProcessContext> qbTopicAdd() {
        return TopicProcesses.add();
    }

    @Bean
    ProcessDefinition<TopicProcesses.RenameInput, TopicProcesses.TopicOutput, ProcessContext> qbTopicRename() {
        return TopicProcesses.rename();
    }

    @Bean
    ProcessDefinition<TopicProcesses.TopicRef, TopicProcesses.TopicOutput, ProcessContext> qbTopicDeactivate() {
        return TopicProcesses.deactivate();
    }

    @Bean
    ProcessDefinition<TopicProcesses.TopicRef, TopicProcesses.TopicOutput, ProcessContext> qbTopicReactivate() {
        return TopicProcesses.reactivate();
    }

    @Bean
    ProcessDefinition<TopicProcesses.MergeInput, TopicProcesses.TopicOutput, ProcessContext> qbTopicMerge() {
        return TopicProcesses.merge();
    }
}
