package com.urbantransport.customer.service.impl;

import com.urbantransport.customer.domain.CustomerEntity;
import com.urbantransport.customer.domain.ProcessedEventEntity;
import com.urbantransport.customer.event.EventTypes;
import com.urbantransport.customer.event.PaymentFailureReason;
import com.urbantransport.customer.event.PaymentRequestedEvent.PaymentRequestedPayload;
import com.urbantransport.customer.event.PaymentResultPayload;
import com.urbantransport.customer.event.RefundCompletedPayload;
import com.urbantransport.customer.event.RefundRequestedEvent.RefundRequestedPayload;
import com.urbantransport.customer.repository.CustomerRepository;
import com.urbantransport.customer.repository.ProcessedEventRepository;
import com.urbantransport.customer.service.OutboxService;
import com.urbantransport.customer.service.TransactionOutcome;
import com.urbantransport.customer.service.WalletService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentProcessingServiceImplTest {

    @Mock
    private CustomerRepository customerRepository;
    @Mock
    private ProcessedEventRepository processedEventRepository;
    @Mock
    private WalletService walletService;
    @Mock
    private OutboxService outboxService;
    @InjectMocks
    private PaymentProcessingServiceImpl service;

    private final UUID eventId = UUID.randomUUID();
    private final UUID customerId = UUID.randomUUID();
    private final UUID referenceId = UUID.randomUUID();
    private final LocalDate date = LocalDate.of(2026, 10, 27);

    // ------------------------------------------------------------------ PaymentRequested

    @Test
    @DisplayName("evento ya procesado: no hace nada")
    void paymentAlreadyProcessed() {
        when(processedEventRepository.existsById(eventId)).thenReturn(true);

        service.handlePaymentRequested(eventId, payment(customerId, null, "10.00"));

        verifyNoInteractions(customerRepository, walletService, outboxService);
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("payload nulo o sin referencia -> IllegalArgumentException (el mensaje irá al DLT)")
    void paymentInvalidPayload() {
        assertThatThrownBy(() -> service.handlePaymentRequested(eventId, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.handlePaymentRequested(eventId,
                new PaymentRequestedPayload(null, referenceId, customerId, null, BigDecimal.TEN, date)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.handlePaymentRequested(eventId,
                new PaymentRequestedPayload("TICKET", null, customerId, null, BigDecimal.TEN, date)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(outboxService);
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("pago exitoso: debita (monto a 2 decimales), emite PaymentCompleted y registra el evento")
    void paymentCompleted() {
        when(customerRepository.findById(customerId)).thenReturn(Optional.of(accountCustomer()));
        when(walletService.debit(customerId, new BigDecimal("10.50"), "TICKET", referenceId, date))
                .thenReturn(TransactionOutcome.APPLIED);

        service.handlePaymentRequested(eventId, payment(customerId, null, "10.5"));

        PaymentResultPayload result = emittedPayment(EventTypes.PAYMENT_COMPLETED, customerId);
        assertThat(result.getReason()).isNull();
        assertThat(result.getReferenceType()).isEqualTo("TICKET");
        assertThat(result.getReferenceId()).isEqualTo(referenceId);
        assertProcessed();
    }

    @Test
    @DisplayName("un pago repetido (ALREADY_APPLIED) también se confirma como completado")
    void paymentAlreadyAppliedIsStillCompleted() {
        when(customerRepository.findById(customerId)).thenReturn(Optional.of(accountCustomer()));
        when(walletService.debit(any(), any(), any(), any(), any())).thenReturn(TransactionOutcome.ALREADY_APPLIED);

        service.handlePaymentRequested(eventId, payment(customerId, null, "10.00"));

        emittedPayment(EventTypes.PAYMENT_COMPLETED, customerId);
        assertProcessed();
    }

    @Test
    @DisplayName("sin fecha de pago usa la de hoy")
    void paymentDateDefaultsToToday() {
        when(customerRepository.findById(customerId)).thenReturn(Optional.of(accountCustomer()));
        when(walletService.debit(any(), any(), any(), any(), any())).thenReturn(TransactionOutcome.APPLIED);

        service.handlePaymentRequested(eventId,
                new PaymentRequestedPayload("TICKET", referenceId, customerId, null, BigDecimal.TEN, null));

        verify(walletService).debit(eq(customerId), any(), eq("TICKET"), eq(referenceId), eq(LocalDate.now()));
    }

    @Test
    @DisplayName("saldo insuficiente: emite PaymentFailed(INSUFFICIENT_FUNDS) y registra el evento")
    void paymentInsufficientFunds() {
        when(customerRepository.findById(customerId)).thenReturn(Optional.of(accountCustomer()));
        when(walletService.debit(any(), any(), any(), any(), any())).thenReturn(TransactionOutcome.INSUFFICIENT_FUNDS);

        service.handlePaymentRequested(eventId, payment(customerId, null, "10.00"));

        assertThat(emittedPayment(EventTypes.PAYMENT_FAILED, customerId).getReason())
                .isEqualTo(PaymentFailureReason.INSUFFICIENT_FUNDS);
        assertProcessed();
    }

    @Test
    @DisplayName("el cliente se resuelve por NIT normalizado cuando no viene el id")
    void customerResolvedByNit() {
        when(customerRepository.findByNit("12345678")).thenReturn(Optional.of(accountCustomer()));
        when(walletService.debit(any(), any(), any(), any(), any())).thenReturn(TransactionOutcome.APPLIED);

        service.handlePaymentRequested(eventId, payment(null, "1234567-8", "10.00"));

        verify(walletService).debit(eq(customerId), any(), any(), any(), any());
        emittedPayment(EventTypes.PAYMENT_COMPLETED, customerId);
    }

    @Test
    @DisplayName("cliente inexistente o sin identificación: PaymentFailed(CUSTOMER_NOT_FOUND) agregado a la referencia")
    void customerNotFound() {
        when(customerRepository.findById(customerId)).thenReturn(Optional.empty());

        service.handlePaymentRequested(eventId, payment(customerId, null, "10.00"));
        assertThat(emittedPayment(EventTypes.PAYMENT_FAILED, referenceId).getReason())
                .isEqualTo(PaymentFailureReason.CUSTOMER_NOT_FOUND);

        UUID secondEvent = UUID.randomUUID();
        service.handlePaymentRequested(secondEvent, payment(null, "  ", "10.00"));
        verify(outboxService, org.mockito.Mockito.times(2)).record(eq(EventTypes.AGGREGATE_PAYMENT), eq(referenceId),
                eq(EventTypes.PAYMENT_FAILED), any());
        verify(walletService, never()).debit(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("cliente genérico o sin cuenta: PaymentFailed(CUSTOMER_WITHOUT_ACCOUNT) sin tocar la cartera")
    void customerWithoutAccount() {
        CustomerEntity generic = CustomerEntity.builder().id(customerId).nit("CF").fullName("CF").generic(true).build();
        when(customerRepository.findById(customerId)).thenReturn(Optional.of(generic));

        service.handlePaymentRequested(eventId, payment(customerId, null, "10.00"));
        assertThat(emittedPayment(EventTypes.PAYMENT_FAILED, customerId).getReason())
                .isEqualTo(PaymentFailureReason.CUSTOMER_WITHOUT_ACCOUNT);

        CustomerEntity counterOnly = CustomerEntity.builder().id(customerId).nit("1").fullName("A").build();
        when(customerRepository.findById(customerId)).thenReturn(Optional.of(counterOnly));
        service.handlePaymentRequested(UUID.randomUUID(), payment(customerId, null, "10.00"));

        verifyNoInteractions(walletService);
    }

    @Test
    @DisplayName("monto nulo, cero o negativo: PaymentFailed(INVALID_AMOUNT)")
    void invalidAmount() {
        when(customerRepository.findById(customerId)).thenReturn(Optional.of(accountCustomer()));

        for (String amount : new String[]{null, "0", "-5.00"}) {
            service.handlePaymentRequested(UUID.randomUUID(), payment(customerId, null, amount));
        }

        verify(outboxService, org.mockito.Mockito.times(3)).record(eq(EventTypes.AGGREGATE_PAYMENT), eq(customerId),
                eq(EventTypes.PAYMENT_FAILED), any());
        verifyNoInteractions(walletService);
    }

    // ------------------------------------------------------------------ RefundRequested

    @Test
    @DisplayName("reembolso: evento ya procesado no hace nada")
    void refundAlreadyProcessed() {
        when(processedEventRepository.existsById(eventId)).thenReturn(true);

        service.handleRefundRequested(eventId, refund(customerId, null, "10.00"));

        verifyNoInteractions(customerRepository, walletService, outboxService);
    }

    @Test
    @DisplayName("reembolso con payload inválido (nulo, sin ticket o monto <= 0) -> IllegalArgumentException")
    void refundInvalidPayload() {
        assertThatThrownBy(() -> service.handleRefundRequested(eventId, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.handleRefundRequested(eventId,
                new RefundRequestedPayload(null, customerId, null, BigDecimal.TEN)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.handleRefundRequested(eventId, refund(customerId, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.handleRefundRequested(eventId, refund(customerId, null, "0")))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(walletService, outboxService);
    }

    @Test
    @DisplayName("reembolso a un cliente que no existe -> IllegalStateException (va al DLT)")
    void refundCustomerNotFound() {
        when(customerRepository.findById(customerId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.handleRefundRequested(eventId, refund(customerId, null, "10.00")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(referenceId.toString());
        verifyNoInteractions(walletService, outboxService);
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("reembolso exitoso: acredita (2 decimales), emite RefundCompleted y registra el evento")
    void refundCompleted() {
        when(customerRepository.findById(customerId)).thenReturn(Optional.of(accountCustomer()));

        service.handleRefundRequested(eventId, refund(customerId, null, "12.345"));

        verify(walletService).refund(eq(customerId), eq(new BigDecimal("12.35")), eq(EventTypes.REFERENCE_TICKET),
                eq(referenceId), eq(LocalDate.now()));
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outboxService).record(eq(EventTypes.AGGREGATE_PAYMENT), eq(customerId),
                eq(EventTypes.REFUND_COMPLETED), payload.capture());
        RefundCompletedPayload completed = (RefundCompletedPayload) payload.getValue();
        assertThat(completed.getTicketId()).isEqualTo(referenceId);
        assertThat(completed.getAmount()).isEqualByComparingTo("12.35");
        assertProcessed();
    }

    @Test
    @DisplayName("reembolso: el cliente también se resuelve por NIT")
    void refundResolvedByNit() {
        when(customerRepository.findByNit("12345678")).thenReturn(Optional.of(accountCustomer()));

        service.handleRefundRequested(eventId, refund(null, "1234567-8", "10.00"));

        verify(walletService).refund(eq(customerId), any(), any(), any(), any());
    }

    // ------------------------------------------------------------------ helpers

    private CustomerEntity accountCustomer() {
        return CustomerEntity.builder().id(customerId).nit("12345678").fullName("Ana")
                .userId(UUID.randomUUID()).build();
    }

    private PaymentRequestedPayload payment(UUID customer, String nit, String amount) {
        return new PaymentRequestedPayload("TICKET", referenceId, customer, nit,
                amount == null ? null : new BigDecimal(amount), date);
    }

    private RefundRequestedPayload refund(UUID customer, String nit, String amount) {
        return new RefundRequestedPayload(referenceId, customer, nit, amount == null ? null : new BigDecimal(amount));
    }

    private PaymentResultPayload emittedPayment(String eventType, UUID aggregateId) {
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outboxService, org.mockito.Mockito.atLeastOnce()).record(eq(EventTypes.AGGREGATE_PAYMENT),
                eq(aggregateId), eq(eventType), payload.capture());
        return (PaymentResultPayload) payload.getValue();
    }

    private void assertProcessed() {
        ArgumentCaptor<ProcessedEventEntity> saved = ArgumentCaptor.forClass(ProcessedEventEntity.class);
        verify(processedEventRepository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        assertThat(saved.getValue().getEventId()).isNotNull();
    }
}