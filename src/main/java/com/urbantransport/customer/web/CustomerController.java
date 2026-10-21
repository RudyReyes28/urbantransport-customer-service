package com.urbantransport.customer.web;

import com.urbantransport.customer.security.CurrentUser;
import com.urbantransport.customer.service.CustomerRegistrationService;
import com.urbantransport.customer.service.CustomerService;
import com.urbantransport.customer.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/customers")
@RequiredArgsConstructor
public class CustomerController {

    private final CustomerRegistrationService registrationService;
    private final CustomerService customerService;

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public CustomerResponse register(@Valid @RequestBody RegisterCustomerRequest request) {
        return registrationService.register(request);
    }

    @GetMapping("/me")
    @PreAuthorize("hasRole('CUSTOMER')")
    public CustomerResponse me(@AuthenticationPrincipal Jwt jwt) {
        return customerService.getProfile(CurrentUser.customerId(jwt));
    }

    @PutMapping("/me")
    @PreAuthorize("hasRole('CUSTOMER')")
    public CustomerResponse updateMe(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UpdateProfileRequest request) {
        return customerService.updateProfile(CurrentUser.customerId(jwt), request);
    }

    @GetMapping("/by-nit/{nit}")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','BRANCH_ADMIN','CASHIER')")
    public CustomerSummaryResponse findByNit(@PathVariable String nit) {
        return customerService.findSummaryByNit(nit);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','BRANCH_ADMIN','CASHIER')")
    public CustomerSummaryResponse create(@Valid @RequestBody CreateCustomerRequest request) {
        return customerService.createWithoutAccount(request);
    }
}