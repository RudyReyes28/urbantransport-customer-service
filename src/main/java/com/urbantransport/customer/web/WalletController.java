package com.urbantransport.customer.web;

import com.urbantransport.customer.security.CurrentUser;
import com.urbantransport.customer.service.WalletService;
import com.urbantransport.customer.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/wallet")
@RequiredArgsConstructor
@PreAuthorize("hasRole('CUSTOMER')")
public class WalletController {

    private final WalletService walletService;

    @GetMapping
    public WalletResponse getWallet(@AuthenticationPrincipal Jwt jwt) {
        return walletService.getWallet(CurrentUser.customerId(jwt));
    }

    @PostMapping("/recharge")
    public WalletResponse recharge(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody RechargeRequest request) {
        return walletService.recharge(CurrentUser.customerId(jwt), request);
    }

    @GetMapping("/transactions")
    public PageResponse<WalletTransactionResponse> transactions(@AuthenticationPrincipal Jwt jwt,
                                                                @RequestParam(defaultValue = "0") int page,
                                                                @RequestParam(defaultValue = "20") int size) {
        return walletService.getTransactions(CurrentUser.customerId(jwt), page, size);
    }
}