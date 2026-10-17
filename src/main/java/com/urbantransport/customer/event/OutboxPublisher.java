package com.urbantransport.customer.event;

import com.urbantransport.customer.config.CustomerKafkaProperties;
import com.urbantransport.customer.domain.OutboxEventEntity;
import com.urbantransport.customer.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Envía a Kafka los eventos pendientes del outbox, en orden. Entrega al menos una vez; pensado para una sola instancia. */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPublisher {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final CustomerKafkaProperties kafkaProperties;

    @Scheduled(fixedDelayString = "${customer.outbox.poll-interval-ms:1000}")
    public void publishPending() {
        List<OutboxEventEntity> pending = outboxEventRepository.findTop50ByPublishedAtIsNullOrderByCreatedAtAsc();
        for (OutboxEventEntity event : pending) {
            try {
                kafkaTemplate.send(topicFor(event.getAggregateType()), event.getAggregateId().toString(), event.getPayload())
                        .get(5, TimeUnit.SECONDS);
                outboxEventRepository.markPublished(event.getId(), LocalDateTime.now());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception exception) {
                // Se corta el lote para conservar el orden; el siguiente ciclo reintenta desde este evento.
                log.warn("Outbox: no se pudo publicar {} ({}), se reintenta", event.getId(), exception.toString());
                return;
            }
        }
    }

    private String topicFor(String aggregateType) {
        return switch (aggregateType) {
            case EventTypes.AGGREGATE_CUSTOMER -> kafkaProperties.customerEventsTopic();
            case EventTypes.AGGREGATE_PAYMENT -> kafkaProperties.paymentEventsTopic();
            default -> throw new IllegalStateException("Agregado sin tópico asignado: " + aggregateType);
        };
    }
}