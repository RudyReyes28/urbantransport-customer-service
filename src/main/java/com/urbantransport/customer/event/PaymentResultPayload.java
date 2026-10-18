package com.urbantransport.customer.event;

import lombok.Value;

import java.util.UUID;

@Value
public class PaymentResultPayload {
    String referenceType;
    UUID referenceId;
    String reason;   // null cuando el pago se completó
}