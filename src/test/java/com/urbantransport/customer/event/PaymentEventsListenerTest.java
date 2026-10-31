package com.urbantransport.customer.event;

import com.urbantransport.customer.service.PaymentProcessingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class PaymentEventsListenerTest {

    @Mock
    private PaymentProcessingService processingService;

    private PaymentEventsListener listener() {
        return new PaymentEventsListener(JsonMapper.builder().build(), processingService);
    }

    private final UUID eventId = UUID.randomUUID();
    private final UUID referenceId = UUID.randomUUID();
    private final UUID customerId = UUID.randomUUID();

    @Test
    @DisplayName("PaymentRequested: parsea el sobre y delega con el eventId y el payload (ignorando campos desconocidos)")
    void paymentRequested() {
        String message = """
                {"eventId":"%s","eventType":"PaymentRequested","occurredAt":"2026-10-27T10:00:00Z","version":1,
                 "payload":{"referenceType":"TICKET","referenceId":"%s","customerId":"%s","customerNit":"1234567-8",
                            "amount":25.50,"paymentDate":"2026-10-27","campoNuevo":"x"}}
                """.formatted(eventId, referenceId, customerId);

        listener().onEvent(message);

        ArgumentCaptor<PaymentRequestedEvent.PaymentRequestedPayload> payload =
                ArgumentCaptor.forClass(PaymentRequestedEvent.PaymentRequestedPayload.class);
        verify(processingService).handlePaymentRequested(eq(eventId), payload.capture());
        assertThat(payload.getValue().referenceType()).isEqualTo("TICKET");
        assertThat(payload.getValue().referenceId()).isEqualTo(referenceId);
        assertThat(payload.getValue().customerId()).isEqualTo(customerId);
        assertThat(payload.getValue().customerNit()).isEqualTo("1234567-8");
        assertThat(payload.getValue().amount()).isEqualByComparingTo("25.50");
        assertThat(payload.getValue().paymentDate()).isEqualTo(LocalDate.of(2026, 10, 27));
    }

    @Test
    @DisplayName("RefundRequested: parsea y delega")
    void refundRequested() {
        String message = """
                {"eventId":"%s","eventType":"RefundRequested",
                 "payload":{"ticketId":"%s","customerNit":"12345678","amount":10}}
                """.formatted(eventId, referenceId);

        listener().onEvent(message);

        ArgumentCaptor<RefundRequestedEvent.RefundRequestedPayload> payload =
                ArgumentCaptor.forClass(RefundRequestedEvent.RefundRequestedPayload.class);
        verify(processingService).handleRefundRequested(eq(eventId), payload.capture());
        assertThat(payload.getValue().ticketId()).isEqualTo(referenceId);
        assertThat(payload.getValue().customerNit()).isEqualTo("12345678");
        assertThat(payload.getValue().amount()).isEqualByComparingTo("10");
        assertThat(payload.getValue().customerId()).isNull();
    }

    @Test
    @DisplayName("eventos de otro tipo, o sin tipo, se ignoran")
    void otherEventsAreIgnored() {
        listener().onEvent("{\"eventId\":\"%s\",\"eventType\":\"TicketCreated\",\"payload\":{}}".formatted(eventId));
        listener().onEvent("{\"eventId\":\"%s\",\"payload\":{}}".formatted(eventId));

        verifyNoInteractions(processingService);
    }

    @Test
    @DisplayName("JSON inválido lanza excepción (para que el error handler reintente y mande a DLT)")
    void invalidJsonFails() {
        assertThatThrownBy(() -> listener().onEvent("esto no es json")).isInstanceOf(RuntimeException.class);

        verifyNoInteractions(processingService);
    }

    @Test
    @DisplayName("si el servicio falla, la excepción se propaga")
    void serviceFailureIsPropagated() {
        doThrow(new IllegalArgumentException("sin referencia")).when(processingService)
                .handlePaymentRequested(any(), any());
        String message = "{\"eventId\":\"%s\",\"eventType\":\"PaymentRequested\",\"payload\":{}}".formatted(eventId);

        assertThatThrownBy(() -> listener().onEvent(message)).isInstanceOf(IllegalArgumentException.class);
    }
}