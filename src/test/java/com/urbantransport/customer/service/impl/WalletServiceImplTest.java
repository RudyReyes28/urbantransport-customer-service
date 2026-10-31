package com.urbantransport.customer.service.impl;

import com.urbantransport.customer.domain.CustomerEntity;
import com.urbantransport.customer.domain.TransactionType;
import com.urbantransport.customer.domain.WalletEntity;
import com.urbantransport.customer.domain.WalletTransactionEntity;
import com.urbantransport.customer.dto.PageResponse;
import com.urbantransport.customer.dto.RechargeRequest;
import com.urbantransport.customer.dto.WalletResponse;
import com.urbantransport.customer.dto.WalletTransactionResponse;
import com.urbantransport.customer.event.EventTypes;
import com.urbantransport.customer.event.WalletRechargedPayload;
import com.urbantransport.customer.repository.CustomerRepository;
import com.urbantransport.customer.repository.WalletRepository;
import com.urbantransport.customer.repository.WalletTransactionRepository;
import com.urbantransport.customer.service.OutboxService;
import com.urbantransport.customer.service.TransactionOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.urbantransport.customer.support.ApiAssertions.assertApiError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WalletServiceImplTest {

    @Mock
    private CustomerRepository customerRepository;
    @Mock
    private WalletRepository walletRepository;
    @Mock
    private WalletTransactionRepository walletTransactionRepository;
    @Mock
    private OutboxService outboxService;
    @InjectMocks
    private WalletServiceImpl service;

    private final UUID customerId = UUID.randomUUID();
    private final UUID referenceId = UUID.randomUUID();
    private final LocalDate date = LocalDate.of(2026, 10, 27);

    // ------------------------------------------------------------------ getWallet y reglas de acceso

    @Test
    @DisplayName("getWallet crea la cartera si falta y devuelve el saldo")
    void getWallet() {
        accountCustomer();
        when(walletRepository.findById(customerId)).thenReturn(Optional.of(
                WalletEntity.builder().customerId(customerId).balance(new BigDecimal("75.50")).build()));

        WalletResponse response = service.getWallet(customerId);

        verify(walletRepository).insertIfAbsent(customerId);
        assertThat(response.getCustomerId()).isEqualTo(customerId);
        assertThat(response.getBalance()).isEqualByComparingTo("75.50");
    }

    @Test
    @DisplayName("getWallet: si la cartera no aparece tras crearla -> 500")
    void getWalletMissingAfterInsert() {
        accountCustomer();
        when(walletRepository.findById(customerId)).thenReturn(Optional.empty());

        assertApiError(() -> service.getWallet(customerId), HttpStatus.INTERNAL_SERVER_ERROR,
                "No se pudo obtener la cartera");
    }

    @Test
    @DisplayName("solo los clientes con cuenta tienen cartera: inexistente 404; genérico o sin cuenta 409")
    void onlyAccountCustomersHaveWallets() {
        when(customerRepository.findById(customerId)).thenReturn(Optional.empty());
        assertApiError(() -> service.getWallet(customerId), HttpStatus.NOT_FOUND, "Cliente no encontrado");

        CustomerEntity generic = CustomerEntity.builder().id(customerId).nit("CF").fullName("CF").generic(true).build();
        when(customerRepository.findById(customerId)).thenReturn(Optional.of(generic));
        assertApiError(() -> service.getWallet(customerId), HttpStatus.CONFLICT,
                "Solo los clientes con cuenta tienen cartera");

        CustomerEntity counterOnly = CustomerEntity.builder().id(customerId).nit("1").fullName("A").build();
        when(customerRepository.findById(customerId)).thenReturn(Optional.of(counterOnly));
        assertApiError(() -> service.recharge(customerId, new RechargeRequest(BigDecimal.TEN, null)),
                HttpStatus.CONFLICT, "Solo los clientes con cuenta tienen cartera");
        verifyNoInteractions(walletRepository);
    }

    // ------------------------------------------------------------------ getTransactions

    @Test
    @DisplayName("getTransactions: ordena por fecha desc, limita page>=0 y size entre 1 y 100")
    void getTransactionsPagination() {
        accountCustomer();
        WalletTransactionEntity tx = WalletTransactionEntity.builder().id(UUID.randomUUID()).walletId(customerId)
                .type(TransactionType.RECHARGE).amount(BigDecimal.TEN).balanceAfter(BigDecimal.TEN)
                .paymentDate(date).build();
        when(walletTransactionRepository.findByWalletId(eq(customerId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(tx), PageRequest.of(0, 20), 1));

        PageResponse<WalletTransactionResponse> response = service.getTransactions(customerId, -5, 1000);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(walletTransactionRepository).findByWalletId(eq(customerId), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isZero();
        assertThat(pageable.getValue().getPageSize()).isEqualTo(100);
        assertThat(pageable.getValue().getSort().getOrderFor("createdAt").getDirection())
                .isEqualTo(Sort.Direction.DESC);
        assertThat(response.getContent()).hasSize(1);
        assertThat(response.getContent().get(0).getType()).isEqualTo(TransactionType.RECHARGE);
    }

    @Test
    @DisplayName("getTransactions: size 0 se convierte en 1")
    void getTransactionsMinimumSize() {
        accountCustomer();
        when(walletTransactionRepository.findByWalletId(eq(customerId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        service.getTransactions(customerId, 2, 0);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(walletTransactionRepository).findByWalletId(eq(customerId), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ recharge

    @Test
    @DisplayName("recarga: acredita, registra la transacción, emite WalletRecharged y devuelve el saldo nuevo")
    void recharge() {
        accountCustomer();
        BigDecimal amount = new BigDecimal("100.00");
        when(walletRepository.credit(customerId, amount)).thenReturn(Optional.of(new BigDecimal("150.00")));

        WalletResponse response = service.recharge(customerId, new RechargeRequest(amount, date));

        assertThat(response.getBalance()).isEqualByComparingTo("150.00");
        InOrder order = inOrder(walletRepository, walletTransactionRepository, outboxService);
        order.verify(walletRepository).insertIfAbsent(customerId);
        order.verify(walletRepository).credit(customerId, amount);
        ArgumentCaptor<WalletTransactionEntity> tx = ArgumentCaptor.forClass(WalletTransactionEntity.class);
        order.verify(walletTransactionRepository).saveAndFlush(tx.capture());
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        order.verify(outboxService).record(eq(EventTypes.AGGREGATE_PAYMENT), eq(customerId),
                eq(EventTypes.WALLET_RECHARGED), payload.capture());

        assertThat(tx.getValue().getType()).isEqualTo(TransactionType.RECHARGE);
        assertThat(tx.getValue().getAmount()).isEqualByComparingTo("100.00");
        assertThat(tx.getValue().getBalanceAfter()).isEqualByComparingTo("150.00");
        assertThat(tx.getValue().getPaymentDate()).isEqualTo(date);
        assertThat(tx.getValue().getReferenceType()).isNull();
        assertThat(tx.getValue().getReferenceId()).isNull();
        WalletRechargedPayload event = (WalletRechargedPayload) payload.getValue();
        assertThat(event.getCustomerId()).isEqualTo(customerId);
        assertThat(event.getAmount()).isEqualByComparingTo("100.00");
        assertThat(event.getPaymentDate()).isEqualTo(date);
    }

    @Test
    @DisplayName("recarga sin fecha: usa la de hoy")
    void rechargeDefaultsPaymentDateToToday() {
        accountCustomer();
        when(walletRepository.credit(any(), any())).thenReturn(Optional.of(BigDecimal.TEN));

        service.recharge(customerId, new RechargeRequest(BigDecimal.TEN, null));

        ArgumentCaptor<WalletTransactionEntity> tx = ArgumentCaptor.forClass(WalletTransactionEntity.class);
        verify(walletTransactionRepository).saveAndFlush(tx.capture());
        assertThat(tx.getValue().getPaymentDate()).isEqualTo(LocalDate.now());
    }

    @Test
    @DisplayName("si no se pudo acreditar -> 500 y no se registra nada")
    void rechargeWithoutCredit() {
        accountCustomer();
        when(walletRepository.credit(any(), any())).thenReturn(Optional.empty());

        assertApiError(() -> service.recharge(customerId, new RechargeRequest(BigDecimal.TEN, date)),
                HttpStatus.INTERNAL_SERVER_ERROR, "No se pudo acreditar la recarga");
        verify(walletTransactionRepository, never()).saveAndFlush(any());
        verifyNoInteractions(outboxService);
    }

    // ------------------------------------------------------------------ debit

    @Test
    @DisplayName("débito ya aplicado (misma referencia): ALREADY_APPLIED sin tocar la cartera")
    void debitAlreadyApplied() {
        when(walletTransactionRepository.existsByReferenceTypeAndReferenceIdAndType("TICKET", referenceId,
                TransactionType.DEBIT)).thenReturn(true);

        TransactionOutcome outcome = service.debit(customerId, BigDecimal.TEN, "TICKET", referenceId, date);

        assertThat(outcome).isEqualTo(TransactionOutcome.ALREADY_APPLIED);
        verifyNoInteractions(walletRepository);
        verify(walletTransactionRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("saldo insuficiente: INSUFFICIENT_FUNDS y no se registra transacción")
    void debitInsufficientFunds() {
        when(walletRepository.debitIfEnough(customerId, BigDecimal.TEN)).thenReturn(Optional.empty());

        TransactionOutcome outcome = service.debit(customerId, BigDecimal.TEN, "TICKET", referenceId, date);

        assertThat(outcome).isEqualTo(TransactionOutcome.INSUFFICIENT_FUNDS);
        verify(walletRepository).insertIfAbsent(customerId);
        verify(walletTransactionRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("débito aplicado: registra DEBIT con la referencia y el saldo posterior")
    void debitApplied() {
        BigDecimal amount = new BigDecimal("25.00");
        when(walletRepository.debitIfEnough(customerId, amount)).thenReturn(Optional.of(new BigDecimal("75.00")));

        TransactionOutcome outcome = service.debit(customerId, amount, "TICKET", referenceId, date);

        assertThat(outcome).isEqualTo(TransactionOutcome.APPLIED);
        ArgumentCaptor<WalletTransactionEntity> tx = ArgumentCaptor.forClass(WalletTransactionEntity.class);
        verify(walletTransactionRepository).saveAndFlush(tx.capture());
        assertThat(tx.getValue().getType()).isEqualTo(TransactionType.DEBIT);
        assertThat(tx.getValue().getWalletId()).isEqualTo(customerId);
        assertThat(tx.getValue().getAmount()).isEqualByComparingTo("25.00");
        assertThat(tx.getValue().getBalanceAfter()).isEqualByComparingTo("75.00");
        assertThat(tx.getValue().getReferenceType()).isEqualTo("TICKET");
        assertThat(tx.getValue().getReferenceId()).isEqualTo(referenceId);
        assertThat(tx.getValue().getPaymentDate()).isEqualTo(date);
    }

    // ------------------------------------------------------------------ refund

    @Test
    @DisplayName("reembolso ya aplicado: ALREADY_APPLIED sin tocar la cartera")
    void refundAlreadyApplied() {
        when(walletTransactionRepository.existsByReferenceTypeAndReferenceIdAndType("TICKET", referenceId,
                TransactionType.REFUND)).thenReturn(true);

        assertThat(service.refund(customerId, BigDecimal.TEN, "TICKET", referenceId, date))
                .isEqualTo(TransactionOutcome.ALREADY_APPLIED);
        verifyNoInteractions(walletRepository);
    }

    @Test
    @DisplayName("reembolso aplicado: acredita y registra REFUND con la referencia")
    void refundApplied() {
        when(walletRepository.credit(customerId, BigDecimal.TEN)).thenReturn(Optional.of(new BigDecimal("60.00")));

        TransactionOutcome outcome = service.refund(customerId, BigDecimal.TEN, "TICKET", referenceId, date);

        assertThat(outcome).isEqualTo(TransactionOutcome.APPLIED);
        ArgumentCaptor<WalletTransactionEntity> tx = ArgumentCaptor.forClass(WalletTransactionEntity.class);
        verify(walletTransactionRepository).saveAndFlush(tx.capture());
        assertThat(tx.getValue().getType()).isEqualTo(TransactionType.REFUND);
        assertThat(tx.getValue().getBalanceAfter()).isEqualByComparingTo("60.00");
        assertThat(tx.getValue().getReferenceId()).isEqualTo(referenceId);
    }

    @Test
    @DisplayName("reembolso sin poder acreditar -> 500")
    void refundWithoutCredit() {
        when(walletRepository.credit(any(), any())).thenReturn(Optional.empty());

        assertApiError(() -> service.refund(customerId, BigDecimal.TEN, "TICKET", referenceId, date),
                HttpStatus.INTERNAL_SERVER_ERROR, "No se pudo acreditar el reembolso");
        verify(walletTransactionRepository, never()).saveAndFlush(any());
    }

    // ------------------------------------------------------------------ helper

    private void accountCustomer() {
        when(customerRepository.findById(customerId)).thenReturn(Optional.of(CustomerEntity.builder()
                .id(customerId).nit("12345678").fullName("Ana").userId(UUID.randomUUID()).build()));
    }
}