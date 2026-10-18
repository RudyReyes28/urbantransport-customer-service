package com.urbantransport.customer.service;

import com.urbantransport.customer.event.PaymentRequestedEvent;
import com.urbantransport.customer.event.RefundRequestedEvent;

import java.util.UUID;

public interface PaymentProcessingService {

    void handlePaymentRequested(UUID eventId, PaymentRequestedEvent.PaymentRequestedPayload payload);

    void handleRefundRequested(UUID eventId, RefundRequestedEvent.RefundRequestedPayload payload);
}