package com.urbantransport.customer.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RefundRequestedEvent(UUID eventId, RefundRequestedPayload payload) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RefundRequestedPayload(
            UUID ticketId,
            UUID customerId,
            String customerNit,
            BigDecimal amount) {
    }
}