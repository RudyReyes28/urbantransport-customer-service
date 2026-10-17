package com.urbantransport.customer.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {

    @Bean
    public NewTopic customerEventsTopic(CustomerKafkaProperties properties) {
        return TopicBuilder.name(properties.customerEventsTopic()).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic paymentEventsTopic(CustomerKafkaProperties properties) {
        return TopicBuilder.name(properties.paymentEventsTopic()).partitions(3).replicas(1).build();
    }
}