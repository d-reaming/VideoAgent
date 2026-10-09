package com.videoagent.middleware.event;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Kafka Topic 自动创建配置。
 */
@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic uploadedTopic() {
        return TopicBuilder.name(Topics.VIDEO_UPLOADED).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic transcribedTopic() {
        return TopicBuilder.name(Topics.VIDEO_TRANSCRIBED).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic framedTopic() {
        return TopicBuilder.name(Topics.VIDEO_FRAMED).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic readyTopic() {
        return TopicBuilder.name(Topics.VIDEO_READY).partitions(3).replicas(1).build();
    }
}
