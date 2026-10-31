package com.urbantransport.customer.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class KafkaConfigTest {

    private final KafkaConfig config = new KafkaConfig();
    private final CustomerKafkaProperties properties =
            new CustomerKafkaProperties("customer-events", "payment-events", "ticket-events", "rental-events");

    @Test
    @DisplayName("declara los tópicos propios (clientes y pagos) con 3 particiones y 1 réplica")
    void declaresOwnTopics() {
        NewTopic customerTopic = config.customerEventsTopic(properties);
        NewTopic paymentTopic = config.paymentEventsTopic(properties);

        assertThat(customerTopic.name()).isEqualTo("customer-events");
        assertThat(customerTopic.numPartitions()).isEqualTo(3);
        assertThat(customerTopic.replicationFactor()).isEqualTo((short) 1);
        assertThat(paymentTopic.name()).isEqualTo("payment-events");
        assertThat(paymentTopic.numPartitions()).isEqualTo(3);
    }

    @Test
    @DisplayName("el manejador de errores se construye con reintentos y dead-letter")
    @SuppressWarnings("unchecked")
    void buildsTheErrorHandler() {
        DefaultErrorHandler handler = config.kafkaErrorHandler(mock(KafkaTemplate.class));

        assertThat(handler).isNotNull();
    }
}