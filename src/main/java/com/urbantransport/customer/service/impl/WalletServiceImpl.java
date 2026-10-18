package com.urbantransport.customer.service.impl;

import com.urbantransport.customer.domain.CustomerEntity;
import com.urbantransport.customer.domain.TransactionType;
import com.urbantransport.customer.domain.WalletEntity;
import com.urbantransport.customer.domain.WalletTransactionEntity;
import com.urbantransport.customer.event.EventTypes;
import com.urbantransport.customer.event.WalletRechargedPayload;
import com.urbantransport.customer.exception.ApiException;
import com.urbantransport.customer.repository.CustomerRepository;
import com.urbantransport.customer.repository.WalletRepository;
import com.urbantransport.customer.repository.WalletTransactionRepository;
import com.urbantransport.customer.service.OutboxService;
import com.urbantransport.customer.service.TransactionOutcome;
import com.urbantransport.customer.service.WalletService;
import com.urbantransport.customer.dto.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WalletServiceImpl implements WalletService {

    private static final int MAX_PAGE_SIZE = 100;

    private final CustomerRepository customerRepository;
    private final WalletRepository walletRepository;
    private final WalletTransactionRepository walletTransactionRepository;
    private final OutboxService outboxService;

    @Override
    @Transactional
    public WalletResponse getWallet(UUID customerId) {
        requireAccountCustomer(customerId);
        walletRepository.insertIfAbsent(customerId);
        WalletEntity wallet = walletRepository.findById(customerId)
                .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "No se pudo obtener la cartera"));
        return WalletResponse.fromEntity(wallet);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<WalletTransactionResponse> getTransactions(UUID customerId, int page, int size) {
        requireAccountCustomer(customerId);
        PageRequest pageable = PageRequest.of(
                Math.max(page, 0),
                Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return PageResponse.of(walletTransactionRepository.findByWalletId(customerId, pageable)
                .map(WalletTransactionResponse::fromEntity));
    }

    @Override
    @Transactional
    public WalletResponse recharge(UUID customerId, RechargeRequest request) {
        requireAccountCustomer(customerId);
        LocalDate paymentDate = request.paymentDate() != null ? request.paymentDate() : LocalDate.now();

        walletRepository.insertIfAbsent(customerId);
        BigDecimal balance = walletRepository.credit(customerId, request.amount())
                .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "No se pudo acreditar la recarga"));
        saveTransaction(customerId, TransactionType.RECHARGE, request.amount(), balance, paymentDate, null, null);

        outboxService.record(EventTypes.AGGREGATE_PAYMENT, customerId, EventTypes.WALLET_RECHARGED,
                new WalletRechargedPayload(customerId, request.amount(), paymentDate));
        return new WalletResponse(customerId, balance);
    }

    @Override
    @Transactional
    public TransactionOutcome debit(UUID customerId, BigDecimal amount, String referenceType, UUID referenceId,
                                    LocalDate paymentDate) {
        if (walletTransactionRepository.existsByReferenceTypeAndReferenceIdAndType(
                referenceType, referenceId, TransactionType.DEBIT)) {
            return TransactionOutcome.ALREADY_APPLIED;
        }
        walletRepository.insertIfAbsent(customerId);
        Optional<BigDecimal> balance = walletRepository.debitIfEnough(customerId, amount);
        if (balance.isEmpty()) {
            return TransactionOutcome.INSUFFICIENT_FUNDS;
        }
        saveTransaction(customerId, TransactionType.DEBIT, amount, balance.get(), paymentDate, referenceType, referenceId);
        return TransactionOutcome.APPLIED;
    }

    @Override
    @Transactional
    public TransactionOutcome refund(UUID customerId, BigDecimal amount, String referenceType, UUID referenceId,
                                     LocalDate paymentDate) {
        if (walletTransactionRepository.existsByReferenceTypeAndReferenceIdAndType(
                referenceType, referenceId, TransactionType.REFUND)) {
            return TransactionOutcome.ALREADY_APPLIED;
        }
        walletRepository.insertIfAbsent(customerId);
        BigDecimal balance = walletRepository.credit(customerId, amount)
                .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "No se pudo acreditar el reembolso"));
        saveTransaction(customerId, TransactionType.REFUND, amount, balance, paymentDate, referenceType, referenceId);
        return TransactionOutcome.APPLIED;
    }

    /** saveAndFlush: si dos operaciones chocan en el índice de idempotencia, falla aquí y se revierte todo, saldo incluido. */
    private void saveTransaction(UUID walletId, TransactionType type, BigDecimal amount, BigDecimal balanceAfter,
                                 LocalDate paymentDate, String referenceType, UUID referenceId) {
        walletTransactionRepository.saveAndFlush(WalletTransactionEntity.builder()
                .walletId(walletId)
                .type(type)
                .amount(amount)
                .balanceAfter(balanceAfter)
                .paymentDate(paymentDate)
                .referenceType(referenceType)
                .referenceId(referenceId)
                .build());
    }

    private CustomerEntity requireAccountCustomer(UUID customerId) {
        CustomerEntity customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Cliente no encontrado"));
        if (customer.isGeneric() || customer.getUserId() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "Solo los clientes con cuenta tienen cartera");
        }
        return customer;
    }
}