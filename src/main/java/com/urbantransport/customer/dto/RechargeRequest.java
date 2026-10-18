package com.urbantransport.customer.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;

public record RechargeRequest(
        @NotNull @DecimalMin("0.01") @DecimalMax("100000.00") @Digits(integer = 6, fraction = 2) BigDecimal amount,
        @PastOrPresent(message = "la fecha de pago no puede ser futura") LocalDate paymentDate) {
}