package com.urbantransport.customer.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentRequestedEvent(UUID eventId, PaymentRequestedPayload payload) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PaymentRequestedPayload(
            String referenceType,
            UUID referenceId,
            UUID customerId,
            String customerNit,
            BigDecimal amount,
            LocalDate paymentDate) {
    }
}