package com.urbantransport.customer.web;

import com.urbantransport.customer.domain.TransactionType;
import com.urbantransport.customer.dto.CreateCustomerRequest;
import com.urbantransport.customer.dto.CustomerResponse;
import com.urbantransport.customer.dto.CustomerSummaryResponse;
import com.urbantransport.customer.dto.PageResponse;
import com.urbantransport.customer.dto.RechargeRequest;
import com.urbantransport.customer.dto.RegisterCustomerRequest;
import com.urbantransport.customer.dto.UpdateProfileRequest;
import com.urbantransport.customer.dto.WalletResponse;
import com.urbantransport.customer.dto.WalletTransactionResponse;
import com.urbantransport.customer.service.CustomerRegistrationService;
import com.urbantransport.customer.service.CustomerService;
import com.urbantransport.customer.service.WalletService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static com.urbantransport.customer.support.ApiAssertions.assertApiError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ControllersTest {

    @Mock
    private CustomerRegistrationService registrationService;
    @Mock
    private CustomerService customerService;
    @Mock
    private WalletService walletService;

    private final UUID customerId = UUID.randomUUID();
    private final Jwt jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject(UUID.randomUUID().toString())
            .claim("customerId", customerId.toString()).build();

    private CustomerResponse customerResponse() {
        return new CustomerResponse(customerId, "12345678", null, "Ana", null, null, null, null, LocalDateTime.now());
    }

    @Test
    @DisplayName("CustomerController delega en los servicios usando el customerId del token")
    void customerController() {
        CustomerController controller = new CustomerController(registrationService, customerService);
        RegisterCustomerRequest register = new RegisterCustomerRequest("a@b.com", "Ana", "12345678",
                "1234567890123", "55512345", "Zona 1");
        UpdateProfileRequest update = new UpdateProfileRequest("Ana", "12345678", "1234567890123", "55512345", "Zona 1");
        CreateCustomerRequest create = new CreateCustomerRequest("12345678", "Ana", null, null, null);
        CustomerResponse profile = customerResponse();
        CustomerSummaryResponse summary = new CustomerSummaryResponse(customerId, "12345678", "Ana", false, false);
        when(registrationService.register(register)).thenReturn(profile);
        when(customerService.getProfile(customerId)).thenReturn(profile);
        when(customerService.updateProfile(customerId, update)).thenReturn(profile);
        when(customerService.findSummaryByNit("12345678")).thenReturn(summary);
        when(customerService.createWithoutAccount(create)).thenReturn(summary);

        assertThat(controller.register(register)).isSameAs(profile);
        assertThat(controller.me(jwt)).isSameAs(profile);
        assertThat(controller.updateMe(jwt, update)).isSameAs(profile);
        assertThat(controller.findByNit("12345678")).isSameAs(summary);
        assertThat(controller.create(create)).isSameAs(summary);
    }

    @Test
    @DisplayName("un token sin customerId no llega al servicio: 403")
    void tokenWithoutCustomerId() {
        CustomerController controller = new CustomerController(registrationService, customerService);
        Jwt staff = Jwt.withTokenValue("t").header("alg", "RS256").subject(UUID.randomUUID().toString()).build();

        assertApiError(() -> controller.me(staff), HttpStatus.FORBIDDEN, "El token no pertenece a un cliente");
        verifyNoInteractions(customerService);
    }

    @Test
    @DisplayName("WalletController delega en WalletService con el customerId del token")
    void walletController() {
        WalletController controller = new WalletController(walletService);
        WalletResponse wallet = new WalletResponse(customerId, new BigDecimal("10.00"));
        RechargeRequest recharge = new RechargeRequest(new BigDecimal("10.00"), null);
        PageResponse<WalletTransactionResponse> page = new PageResponse<>(
                List.of(new WalletTransactionResponse(UUID.randomUUID(), TransactionType.RECHARGE, BigDecimal.TEN,
                        BigDecimal.TEN, null, null, null, null)), 0, 20, 1, 1);
        when(walletService.getWallet(customerId)).thenReturn(wallet);
        when(walletService.recharge(customerId, recharge)).thenReturn(wallet);
        when(walletService.getTransactions(customerId, 2, 50)).thenReturn(page);

        assertThat(controller.getWallet(jwt)).isSameAs(wallet);
        assertThat(controller.recharge(jwt, recharge)).isSameAs(wallet);
        assertThat(controller.transactions(jwt, 2, 50)).isSameAs(page);
        verify(walletService).getTransactions(customerId, 2, 50);
    }
}