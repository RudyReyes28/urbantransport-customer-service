package com.urbantransport.customer.service.impl;

import com.urbantransport.customer.client.IdentityClient;
import com.urbantransport.customer.client.IdentityCreateUserRequest;
import com.urbantransport.customer.client.IdentityUserResponse;
import com.urbantransport.customer.domain.CustomerEntity;
import com.urbantransport.customer.domain.WalletEntity;
import com.urbantransport.customer.dto.CustomerResponse;
import com.urbantransport.customer.dto.RegisterCustomerRequest;
import com.urbantransport.customer.exception.ApiException;
import com.urbantransport.customer.repository.CustomerRepository;
import com.urbantransport.customer.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.Optional;
import java.util.UUID;

import static com.urbantransport.customer.support.ApiAssertions.assertApiError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomerRegistrationServiceImplTest {

    private static final String DPI = "1234567890123";

    @Mock
    private CustomerRepository customerRepository;
    @Mock
    private WalletRepository walletRepository;
    @Mock
    private IdentityClient identityClient;
    @Mock
    private PlatformTransactionManager transactionManager;

    private CustomerRegistrationServiceImpl service;
    private final UUID customerId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());
        service = new CustomerRegistrationServiceImpl(customerRepository, walletRepository, identityClient,
                transactionManager);
    }

    // ------------------------------------------------------------------ camino feliz

    @Test
    @DisplayName("cliente nuevo: lo crea, crea la cuenta en identity con datos normalizados, enlaza el usuario y crea la cartera")
    void registersNewCustomer() {
        CustomerEntity stored = newCustomer();
        when(customerRepository.findByNit("12345678")).thenReturn(Optional.empty());
        when(customerRepository.existsByDpi(DPI)).thenReturn(false);
        when(customerRepository.save(any(CustomerEntity.class))).thenReturn(stored);
        when(identityClient.createCustomerUser(any())).thenReturn(identityAccount());
        when(customerRepository.findById(customerId)).thenReturn(Optional.of(stored));
        when(walletRepository.existsById(customerId)).thenReturn(false);

        CustomerResponse response = service.register(request("1234567-8", DPI));

        // Paso 1: el cliente se guarda con los datos normalizados
        ArgumentCaptor<CustomerEntity> created = ArgumentCaptor.forClass(CustomerEntity.class);
        verify(customerRepository).save(created.capture());
        assertThat(created.getValue().getNit()).isEqualTo("12345678");
        assertThat(created.getValue().getEmail()).isEqualTo("ana@mail.com");
        assertThat(created.getValue().getFullName()).isEqualTo("Ana Pérez");
        assertThat(created.getValue().getAddress()).isEqualTo("Zona 1");
        // Paso 2: la cuenta en identity
        ArgumentCaptor<IdentityCreateUserRequest> account = ArgumentCaptor.forClass(IdentityCreateUserRequest.class);
        verify(identityClient).createCustomerUser(account.capture());
        assertThat(account.getValue()).isEqualTo(new IdentityCreateUserRequest(
                "ana@mail.com", "Password123", "Ana Pérez", customerId, "12345678"));
        // Paso 3: enlace y cartera
        assertThat(stored.getUserId()).isEqualTo(userId);
        ArgumentCaptor<WalletEntity> wallet = ArgumentCaptor.forClass(WalletEntity.class);
        verify(walletRepository).save(wallet.capture());
        assertThat(wallet.getValue().getCustomerId()).isEqualTo(customerId);
        assertThat(response.getUserId()).isEqualTo(userId);
        // Dos transacciones separadas; identity se llama entre ellas
        verify(transactionManager, times(2)).commit(any());
    }

    @Test
    @DisplayName("si la cartera ya existe no se crea otra")
    void doesNotDuplicateTheWallet() {
        CustomerEntity stored = newCustomer();
        when(customerRepository.findByNit("12345678")).thenReturn(Optional.empty());
        when(customerRepository.save(any(CustomerEntity.class))).thenReturn(stored);
        when(identityClient.createCustomerUser(any())).thenReturn(identityAccount());
        when(customerRepository.findById(customerId)).thenReturn(Optional.of(stored));
        when(walletRepository.existsById(customerId)).thenReturn(true);

        service.register(request("12345678", DPI));

        verify(walletRepository, never()).save(any());
    }

    // ------------------------------------------------------------------ validaciones y conflictos

    @Test
    @DisplayName("NIT con formato inválido -> 400 antes de tocar nada")
    void invalidNit() {
        assertApiError(() -> service.register(request("ABC", DPI)), HttpStatus.BAD_REQUEST,
                "El NIT no tiene un formato válido");
        verifyNoInteractions(customerRepository, identityClient, walletRepository, transactionManager);
    }

    @Test
    @DisplayName("el NIT ya tiene cuenta -> 409 y no se llama a identity")
    void nitAlreadyHasAccount() {
        CustomerEntity withAccount = CustomerEntity.builder().id(customerId).nit("12345678").fullName("Ana")
                .userId(userId).build();
        when(customerRepository.findByNit("12345678")).thenReturn(Optional.of(withAccount));

        assertApiError(() -> service.register(request("12345678", DPI)), HttpStatus.CONFLICT,
                "Este NIT ya tiene una cuenta. Inicia sesión");
        verifyNoInteractions(identityClient);
    }

    @Test
    @DisplayName("cliente de ventanilla con DPI distinto al enviado -> 409 'no coinciden'")
    void existingCustomerWithDifferentDpi() {
        CustomerEntity counter = counterCustomer("9999999999999");
        when(customerRepository.findByNit("12345678")).thenReturn(Optional.of(counter));

        assertApiError(() -> service.register(request("12345678", DPI)), HttpStatus.CONFLICT,
                "Los datos no coinciden con el cliente registrado para este NIT");
        verifyNoInteractions(identityClient);
    }

    @Test
    @DisplayName("cliente de ventanilla sin DPI y DPI ya usado por otro NIT -> 409")
    void existingCustomerWithoutDpiAndDpiTaken() {
        when(customerRepository.findByNit("12345678")).thenReturn(Optional.of(counterCustomer(null)));
        when(customerRepository.existsByDpi(DPI)).thenReturn(true);

        assertApiError(() -> service.register(request("12345678", DPI)), HttpStatus.CONFLICT,
                "El DPI ya está registrado con otro NIT");
        verifyNoInteractions(identityClient);
    }

    @Test
    @DisplayName("NIT nuevo pero DPI ya registrado con otro NIT -> 409")
    void newCustomerWithTakenDpi() {
        when(customerRepository.findByNit("12345678")).thenReturn(Optional.empty());
        when(customerRepository.existsByDpi(DPI)).thenReturn(true);

        assertApiError(() -> service.register(request("12345678", DPI)), HttpStatus.CONFLICT,
                "El DPI ya está registrado con otro NIT");
        verify(customerRepository, never()).save(any());
        verifyNoInteractions(identityClient);
    }

    // ------------------------------------------------------------------ cliente de ventanilla existente

    @Test
    @DisplayName("cliente de ventanilla sin DPI: se acepta, se completa con DPI y contacto, y se enlaza la cuenta")
    void upgradesCounterCustomerWithoutDpi() {
        CustomerEntity counter = counterCustomer(null);
        when(customerRepository.findByNit("12345678")).thenReturn(Optional.of(counter));
        when(customerRepository.existsByDpi(DPI)).thenReturn(false);
        when(identityClient.createCustomerUser(any())).thenReturn(identityAccount());
        when(customerRepository.findById(customerId)).thenReturn(Optional.of(counter));
        when(walletRepository.existsById(customerId)).thenReturn(false);

        service.register(request("12345678", DPI));

        assertThat(counter.getDpi()).isEqualTo(DPI);
        assertThat(counter.getEmail()).isEqualTo("ana@mail.com");
        assertThat(counter.getPhone()).isEqualTo("55512345");
        assertThat(counter.getUserId()).isEqualTo(userId);
        verify(customerRepository, never()).save(any(CustomerEntity.class));
        verify(walletRepository).save(any(WalletEntity.class));
    }

    @Test
    @DisplayName("cliente de ventanilla con el mismo DPI: se acepta")
    void upgradesCounterCustomerWithSameDpi() {
        CustomerEntity counter = counterCustomer(DPI);
        when(customerRepository.findByNit("12345678")).thenReturn(Optional.of(counter));
        when(identityClient.createCustomerUser(any())).thenReturn(identityAccount());
        when(customerRepository.findById(customerId)).thenReturn(Optional.of(counter));
        when(walletRepository.existsById(customerId)).thenReturn(true);

        CustomerResponse response = service.register(request("12345678", DPI));

        assertThat(response.getUserId()).isEqualTo(userId);
        verify(customerRepository, never()).existsByDpi(any());
    }

    // ------------------------------------------------------------------ compensación cuando identity falla

    @Test
    @DisplayName("identity falla y el cliente se acababa de crear: se borra (compensación) y se relanza el mismo error")
    void identityFailureRollsBackCreatedCustomer() {
        ApiException failure = new ApiException(HttpStatus.CONFLICT, "Ya existe un usuario con el correo ana@mail.com");
        when(customerRepository.findByNit("12345678")).thenReturn(Optional.empty());
        when(customerRepository.save(any(CustomerEntity.class))).thenReturn(newCustomer());
        when(identityClient.createCustomerUser(any())).thenThrow(failure);

        assertThatThrownBy(() -> service.register(request("12345678", DPI))).isSameAs(failure);

        verify(customerRepository).deleteById(customerId);
        verify(walletRepository, never()).save(any());
        verify(transactionManager, times(2)).commit(any());   // crear + borrar
    }

    @Test
    @DisplayName("identity falla y el cliente ya existía: NO se borra")
    void identityFailureKeepsPreexistingCustomer() {
        ApiException unavailable = new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "no disponible");
        when(customerRepository.findByNit("12345678")).thenReturn(Optional.of(counterCustomer(DPI)));
        when(identityClient.createCustomerUser(any())).thenThrow(unavailable);

        assertThatThrownBy(() -> service.register(request("12345678", DPI))).isSameAs(unavailable);

        verify(customerRepository, never()).deleteById(any());
        verify(transactionManager, times(1)).commit(any());
    }

    // ------------------------------------------------------------------ fallo local después de crear la cuenta

    @Test
    @DisplayName("si el cliente desaparece antes de completar el registro -> 500 y no se crea cartera")
    void customerVanishesBeforeCompletion() {
        when(customerRepository.findByNit("12345678")).thenReturn(Optional.empty());
        when(customerRepository.save(any(CustomerEntity.class))).thenReturn(newCustomer());
        when(identityClient.createCustomerUser(any())).thenReturn(identityAccount());
        when(customerRepository.findById(customerId)).thenReturn(Optional.empty());

        assertApiError(() -> service.register(request("12345678", DPI)), HttpStatus.INTERNAL_SERVER_ERROR,
                "No se pudo completar el registro");
        verify(walletRepository, never()).save(any());
        verify(customerRepository, never()).deleteById(any());
    }

    // ------------------------------------------------------------------ helpers

    private RegisterCustomerRequest request(String nit, String dpi) {
        return new RegisterCustomerRequest("  Ana@Mail.COM ", "Password123", "  Ana Pérez ", nit, dpi,
                "55512345", "  Zona 1  ");
    }

    private CustomerEntity newCustomer() {
        return CustomerEntity.builder().id(customerId).nit("12345678").dpi(DPI).fullName("Ana Pérez")
                .address("Zona 1").build();
    }

    private CustomerEntity counterCustomer(String dpi) {
        return CustomerEntity.builder().id(customerId).nit("12345678").dpi(dpi).fullName("Ana (ventanilla)").build();
    }

    private IdentityUserResponse identityAccount() {
        return new IdentityUserResponse(userId, "ana@mail.com", "Ana Pérez", "CUSTOMER", customerId, "12345678");
    }
}