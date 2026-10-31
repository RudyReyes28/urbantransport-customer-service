package com.urbantransport.customer.event;

import com.urbantransport.customer.config.CustomerKafkaProperties;
import com.urbantransport.customer.domain.OutboxEventEntity;
import com.urbantransport.customer.repository.OutboxEventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxPublisherTest {

    @Mock
    private OutboxEventRepository repository;
    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private final CustomerKafkaProperties properties =
            new CustomerKafkaProperties("customer-events", "payment-events", "ticket-events", "rental-events");

    private OutboxPublisher publisher() {
        return new OutboxPublisher(repository, kafkaTemplate, properties);
    }

    @Test
    @DisplayName("sin pendientes no envía nada")
    void nothingPending() {
        when(repository.findTop50ByPublishedAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of());

        publisher().publishPending();

        verifyNoInteractions(kafkaTemplate);
        verify(repository, never()).markPublished(any(), any());
    }

    @Test
    @DisplayName("envía cada evento al tópico de su agregado, con el aggregateId como clave, y lo marca como publicado")
    void publishesToTheTopicOfEachAggregate() {
        OutboxEventEntity customerEvent = event("Customer", "{\"a\":1}");
        OutboxEventEntity paymentEvent = event("Payment", "{\"b\":2}");
        when(repository.findTop50ByPublishedAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of(customerEvent, paymentEvent));
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(sent());

        publisher().publishPending();

        InOrder order = inOrder(kafkaTemplate, repository);
        order.verify(kafkaTemplate).send("customer-events", customerEvent.getAggregateId().toString(), "{\"a\":1}");
        order.verify(repository).markPublished(eq(customerEvent.getId()), any(LocalDateTime.class));
        order.verify(kafkaTemplate).send("payment-events", paymentEvent.getAggregateId().toString(), "{\"b\":2}");
        order.verify(repository).markPublished(eq(paymentEvent.getId()), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("si falla un envío se corta el lote: se conservan el orden y los pendientes")
    void failureStopsTheBatch() {
        OutboxEventEntity first = event("Payment", "1");
        OutboxEventEntity second = event("Payment", "2");
        OutboxEventEntity third = event("Payment", "3");
        when(repository.findTop50ByPublishedAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of(first, second, third));
        when(kafkaTemplate.send(eq("payment-events"), eq(first.getAggregateId().toString()), anyString())).thenReturn(sent());
        when(kafkaTemplate.send(eq("payment-events"), eq(second.getAggregateId().toString()), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker caído")));

        publisher().publishPending();

        verify(repository).markPublished(eq(first.getId()), any());
        verify(repository, never()).markPublished(eq(second.getId()), any());
        verify(repository, never()).markPublished(eq(third.getId()), any());
        verify(kafkaTemplate, never()).send(eq("payment-events"), eq(third.getAggregateId().toString()), anyString());
    }

    @Test
    @DisplayName("agregado sin tópico: no se envía ni se marca (el lote se detiene en ese evento)")
    void unknownAggregateIsNotPublished() {
        OutboxEventEntity unknown = event("Otro", "x");
        when(repository.findTop50ByPublishedAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of(unknown));

        publisher().publishPending();

        verifyNoInteractions(kafkaTemplate);
        verify(repository, never()).markPublished(any(), any());
    }

    @Test
    @DisplayName("si el hilo es interrumpido, restablece la bandera de interrupción y termina")
    @SuppressWarnings("unchecked")
    void interruptionIsPropagated() throws Exception {
        OutboxEventEntity event = event("Payment", "x");
        CompletableFuture<SendResult<String, String>> future = mock(CompletableFuture.class);
        when(future.get(anyLong(), any(TimeUnit.class))).thenThrow(new InterruptedException());
        when(repository.findTop50ByPublishedAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of(event));
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(future);

        publisher().publishPending();

        assertThat(Thread.interrupted()).isTrue();   // lee y limpia la bandera para no afectar otros tests
        verify(repository, never()).markPublished(any(), any());
    }

    private static OutboxEventEntity event(String aggregateType, String payload) {
        return OutboxEventEntity.builder().id(UUID.randomUUID()).aggregateType(aggregateType)
                .aggregateId(UUID.randomUUID()).eventType("X").payload(payload).build();
    }

    private static CompletableFuture<SendResult<String, String>> sent() {
        return CompletableFuture.completedFuture(null);
    }
}