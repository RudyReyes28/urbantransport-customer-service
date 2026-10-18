package com.urbantransport.customer.event;

import lombok.Value;

import java.math.BigDecimal;
import java.util.UUID;

@Value
public class RefundCompletedPayload {
    UUID ticketId;
    BigDecimal amount;
}