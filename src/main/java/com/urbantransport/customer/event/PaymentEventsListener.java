package com.urbantransport.customer.event;

import com.urbantransport.customer.service.PaymentProcessingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventsListener {

    private final JsonMapper jsonMapper;
    private final PaymentProcessingService paymentProcessingService;

    @KafkaListener(topics = {"${customer.kafka.ticket-events-topic}", "${customer.kafka.rental-events-topic}"})
    public void onEvent(String message) {
        EventHeader header = jsonMapper.readValue(message, EventHeader.class);
        String type = header.eventType() == null ? "" : header.eventType();

        switch (type) {
            case EventTypes.PAYMENT_REQUESTED -> {
                PaymentRequestedEvent event = jsonMapper.readValue(message, PaymentRequestedEvent.class);
                paymentProcessingService.handlePaymentRequested(event.eventId(), event.payload());
            }
            case EventTypes.REFUND_REQUESTED -> {
                RefundRequestedEvent event = jsonMapper.readValue(message, RefundRequestedEvent.class);
                paymentProcessingService.handleRefundRequested(event.eventId(), event.payload());
            }
            default -> log.debug("Evento {} ignorado", type);
        }
    }
}