package com.urbantransport.customer.service.impl;

import com.jayway.jsonpath.JsonPath;
import com.urbantransport.customer.domain.OutboxEventEntity;
import com.urbantransport.customer.event.CustomerNitChangedPayload;
import com.urbantransport.customer.repository.OutboxEventRepository;
import com.urbantransport.customer.web.CorrelationIdFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OutboxServiceImplTest {

    @Mock
    private OutboxEventRepository repository;

    @AfterEach
    void cleanMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("guarda el sobre del evento (id, tipo, versión, correlationId del MDC y payload) en el outbox")
    void recordsTheEnvelope() {
        OutboxServiceImpl outbox = new OutboxServiceImpl(repository, JsonMapper.builder().build());
        UUID aggregateId = UUID.randomUUID();
        MDC.put(CorrelationIdFilter.MDC_KEY, "corr-77");

        outbox.record("Customer", aggregateId, "CustomerNitChanged", new CustomerNitChangedPayload(aggregateId, "12345678"));

        OutboxEventEntity saved = savedEntity();
        assertThat(saved.getAggregateType()).isEqualTo("Customer");
        assertThat(saved.getAggregateId()).isEqualTo(aggregateId);
        assertThat(saved.getEventType()).isEqualTo("CustomerNitChanged");
        String json = saved.getPayload();
        assertThat((String) JsonPath.read(json, "$.eventId")).isEqualTo(saved.getId().toString());
        assertThat((String) JsonPath.read(json, "$.eventType")).isEqualTo("CustomerNitChanged");
        assertThat((String) JsonPath.read(json, "$.aggregateId")).isEqualTo(aggregateId.toString());
        assertThat((Integer) JsonPath.read(json, "$.version")).isEqualTo(1);
        assertThat((String) JsonPath.read(json, "$.correlationId")).isEqualTo("corr-77");
        assertThat((String) JsonPath.read(json, "$.payload.nit")).isEqualTo("12345678");
        assertThat((String) JsonPath.read(json, "$.payload.customerId")).isEqualTo(aggregateId.toString());
        assertThat((Object) JsonPath.read(json, "$.occurredAt")).isNotNull();
    }

    @Test
    @DisplayName("sin correlationId en el MDC (p. ej. un evento disparado desde Kafka) genera uno nuevo")
    void generatesCorrelationIdWhenMissing() {
        OutboxServiceImpl outbox = new OutboxServiceImpl(repository, JsonMapper.builder().build());

        outbox.record("Payment", UUID.randomUUID(), "PaymentCompleted", java.util.Map.of("ok", true));

        String correlationId = JsonPath.read(savedEntity().getPayload(), "$.correlationId");
        assertThat(UUID.fromString(correlationId)).isNotNull();
    }

    private OutboxEventEntity savedEntity() {
        ArgumentCaptor<OutboxEventEntity> captor = ArgumentCaptor.forClass(OutboxEventEntity.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }
}