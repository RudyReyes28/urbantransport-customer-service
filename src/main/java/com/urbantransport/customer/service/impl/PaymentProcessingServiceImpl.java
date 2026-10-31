package com.urbantransport.customer.service.impl;

import com.urbantransport.customer.domain.CustomerEntity;
import com.urbantransport.customer.domain.ProcessedEventEntity;
import com.urbantransport.customer.event.*;
import com.urbantransport.customer.repository.CustomerRepository;
import com.urbantransport.customer.repository.ProcessedEventRepository;
import com.urbantransport.customer.service.OutboxService;
import com.urbantransport.customer.service.PaymentProcessingService;
import com.urbantransport.customer.service.TransactionOutcome;
import com.urbantransport.customer.service.WalletService;
import com.urbantransport.customer.util.NitUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * El efecto sobre la cartera, el evento de respuesta (outbox) y el registro del eventId se guardan en una sola
 * transacción: o ocurre todo o nada. Los errores de datos lanzan excepción para que el mensaje vaya al DLT.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentProcessingServiceImpl implements PaymentProcessingService {

    private final CustomerRepository customerRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final WalletService walletService;
    private final OutboxService outboxService;

    @Override
    @Transactional
    public void handlePaymentRequested(UUID eventId, PaymentRequestedEvent.PaymentRequestedPayload payload) {
        if (processedEventRepository.existsById(eventId)) {
            log.debug("Evento {} ya procesado, se ignora", eventId);
            return;
        }
        if (payload == null || payload.referenceType() == null || payload.referenceId() == null) {
            throw new IllegalArgumentException("PaymentRequested sin referenceType o referenceId");
        }

        Optional<CustomerEntity> customer = resolveCustomer(payload.customerId(), payload.customerNit());
        String failure = null;
        if (customer.isEmpty()) {
            failure = PaymentFailureReason.CUSTOMER_NOT_FOUND;
        } else if (customer.get().isGeneric() || customer.get().getUserId() == null) {
            failure = PaymentFailureReason.CUSTOMER_WITHOUT_ACCOUNT;
        } else if (payload.amount() == null || payload.amount().signum() <= 0) {
            failure = PaymentFailureReason.INVALID_AMOUNT;
        } else {
            TransactionOutcome outcome = walletService.debit(customer.get().getId(), scale(payload.amount()),
                    payload.referenceType(), payload.referenceId(), dateOrToday(payload.paymentDate()));
            if (outcome == TransactionOutcome.INSUFFICIENT_FUNDS) {
                failure = PaymentFailureReason.INSUFFICIENT_FUNDS;
            }
        }

        // aggregateId = cliente, para que los eventos de una misma cartera conserven el orden en Kafka
        UUID aggregateId = customer.map(CustomerEntity::getId).orElse(payload.referenceId());
        outboxService.record(EventTypes.AGGREGATE_PAYMENT, aggregateId,
                failure == null ? EventTypes.PAYMENT_COMPLETED : EventTypes.PAYMENT_FAILED,
                new PaymentResultPayload(payload.referenceType(), payload.referenceId(), failure));
        markProcessed(eventId);
    }

    @Override
    @Transactional
    public void handleRefundRequested(UUID eventId, RefundRequestedEvent.RefundRequestedPayload payload) {
        if (processedEventRepository.existsById(eventId)) {
            log.debug("Evento {} ya procesado, se ignora", eventId);
            return;
        }
        if (payload == null || payload.ticketId() == null || payload.amount() == null || payload.amount().signum() <= 0) {
            throw new IllegalArgumentException("RefundRequested sin ticketId o con monto inválido");
        }
        CustomerEntity customer = resolveCustomer(payload.customerId(), payload.customerNit())
                .orElseThrow(() -> new IllegalStateException(
                        "No se encontró el cliente del reembolso del boleto " + payload.ticketId()));

        BigDecimal amount = scale(payload.amount());
        walletService.refund(customer.getId(), amount, EventTypes.REFERENCE_TICKET, payload.ticketId(), LocalDate.now());

        outboxService.record(EventTypes.AGGREGATE_PAYMENT, customer.getId(), EventTypes.REFUND_COMPLETED,
                new RefundCompletedPayload(payload.ticketId(), amount));
        markProcessed(eventId);
    }

    private Optional<CustomerEntity> resolveCustomer(UUID customerId, String customerNit) {
        if (customerId != null) {
            return customerRepository.findById(customerId);
        }
        if (customerNit != null && !customerNit.isBlank()) {
            return customerRepository.findByNit(NitUtils.normalize(customerNit));
        }
        return Optional.empty();
    }

    private BigDecimal scale(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    private LocalDate dateOrToday(LocalDate date) {
        return date != null ? date : LocalDate.now();
    }

    private void markProcessed(UUID eventId) {
        processedEventRepository.save(ProcessedEventEntity.builder().eventId(eventId).build());
    }
}