package com.urbantransport.customer.event;

import lombok.Value;

import java.time.Instant;
import java.util.UUID;

@Value
public class EventEnvelope {
    UUID eventId;
    String eventType;
    Instant occurredAt;
    UUID aggregateId;
    int version;
    String correlationId;
    Object payload;
}