package com.urbantransport.customer.event;

import lombok.Value;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Value
public class WalletRechargedPayload {
    UUID customerId;
    BigDecimal amount;
    LocalDate paymentDate;
}