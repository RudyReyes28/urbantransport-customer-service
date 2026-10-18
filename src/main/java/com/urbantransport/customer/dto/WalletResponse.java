package com.urbantransport.customer.dto;

import com.urbantransport.customer.domain.WalletEntity;
import lombok.Value;

import java.math.BigDecimal;
import java.util.UUID;

@Value
public class WalletResponse {
    UUID customerId;
    BigDecimal balance;

    public static WalletResponse fromEntity(WalletEntity wallet) {
        return new WalletResponse(wallet.getCustomerId(), wallet.getBalance());
    }
}