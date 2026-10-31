package com.urbantransport.customer.dto;


import com.urbantransport.customer.domain.TransactionType;
import com.urbantransport.customer.domain.WalletTransactionEntity;
import lombok.Value;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Value
public class WalletTransactionResponse {
    UUID id;
    TransactionType type;
    BigDecimal amount;
    BigDecimal balanceAfter;
    LocalDate paymentDate;
    String referenceType;
    UUID referenceId;
    LocalDateTime createdAt;

    public static WalletTransactionResponse fromEntity(WalletTransactionEntity tx) {
        return new WalletTransactionResponse(tx.getId(), tx.getType(), tx.getAmount(), tx.getBalanceAfter(),
                tx.getPaymentDate(), tx.getReferenceType(), tx.getReferenceId(), tx.getCreatedAt());
    }
}