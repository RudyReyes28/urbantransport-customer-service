package com.urbantransport.customer.service;

import com.urbantransport.customer.dto.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public interface WalletService {

    WalletResponse getWallet(UUID customerId);

    PageResponse<WalletTransactionResponse> getTransactions(UUID customerId, int page, int size);

    WalletResponse recharge(UUID customerId, RechargeRequest request);

    /** Débito idempotente por (referenceType, referenceId); INSUFFICIENT_FUNDS si el saldo no alcanza. */
    TransactionOutcome debit(UUID customerId, BigDecimal amount, String referenceType, UUID referenceId, LocalDate paymentDate);

    /** Reembolso idempotente por (referenceType, referenceId). */
    TransactionOutcome refund(UUID customerId, BigDecimal amount, String referenceType, UUID referenceId, LocalDate paymentDate);
}