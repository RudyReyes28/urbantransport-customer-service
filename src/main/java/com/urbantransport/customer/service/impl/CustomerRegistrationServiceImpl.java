package com.urbantransport.customer.service.impl;

import com.urbantransport.customer.client.IdentityClient;
import com.urbantransport.customer.client.IdentityCreateUserRequest;
import com.urbantransport.customer.client.IdentityUserResponse;
import com.urbantransport.customer.domain.CustomerEntity;
import com.urbantransport.customer.domain.WalletEntity;
import com.urbantransport.customer.exception.ApiException;
import com.urbantransport.customer.repository.CustomerRepository;
import com.urbantransport.customer.repository.WalletRepository;
import com.urbantransport.customer.service.CustomerRegistrationService;
import com.urbantransport.customer.util.NitUtils;
import com.urbantransport.customer.dto.CustomerResponse;
import com.urbantransport.customer.dto.RegisterCustomerRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
public class CustomerRegistrationServiceImpl implements CustomerRegistrationService {

    private final CustomerRepository customerRepository;
    private final WalletRepository walletRepository;
    private final IdentityClient identityClient;
    private final TransactionTemplate transactionTemplate;

    public CustomerRegistrationServiceImpl(CustomerRepository customerRepository,
                                           WalletRepository walletRepository,
                                           IdentityClient identityClient,
                                           PlatformTransactionManager transactionManager) {
        this.customerRepository = customerRepository;
        this.walletRepository = walletRepository;
        this.identityClient = identityClient;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public CustomerResponse register(RegisterCustomerRequest request) {
        String nit = NitUtils.normalize(request.nit());
        if (!NitUtils.isValid(nit)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "El NIT no tiene un formato válido");
        }
        String email = request.email().trim().toLowerCase(Locale.ROOT);

        // 1) Cliente: se crea o se verifica el existente (sin modificarlo todavía)
        Prepared prepared = transactionTemplate.execute(status -> prepare(request, nit, email));

        // 2) Cuenta en identity (fuera de transacción); si falla, se deshace lo creado en el paso 1
        IdentityUserResponse account;
        try {
            account = identityClient.createCustomerUser(new IdentityCreateUserRequest(
                    email, request.fullName().trim(), prepared.customerId(), nit));
        } catch (RuntimeException exception) {
            if (prepared.created()) {
                transactionTemplate.executeWithoutResult(status -> customerRepository.deleteById(prepared.customerId()));
            }
            throw exception;
        }

        // 3) Enlace con la cuenta, datos de contacto y cartera
        try {
            CustomerEntity customer = transactionTemplate.execute(status -> complete(prepared.customerId(), account, request, email));
            return CustomerResponse.fromEntity(customer);
        } catch (RuntimeException exception) {
            log.error("Cuenta {} creada en identity para el cliente {} pero no se pudo completar el registro local",
                    account.id(), prepared.customerId(), exception);
            throw exception;
        }
    }

    private Prepared prepare(RegisterCustomerRequest request, String nit, String email) {
        Optional<CustomerEntity> existing = customerRepository.findByNit(nit);
        if (existing.isPresent()) {
            CustomerEntity customer = existing.get();
            if (customer.getUserId() != null) {
                throw new ApiException(HttpStatus.CONFLICT, "Este NIT ya tiene una cuenta. Inicia sesión");
            }
            if (customer.getDpi() == null) {
                // Cliente de ventanilla sin DPI: no hay con qué compararlo. Se acepta y complete() guardará el DPI.
                if (customerRepository.existsByDpi(request.dpi())) {
                    throw new ApiException(HttpStatus.CONFLICT, "El DPI ya está registrado con otro NIT");
                }
            } else if (!customer.getDpi().equals(request.dpi())) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "Los datos no coinciden con el cliente registrado para este NIT");
            }
            return new Prepared(customer.getId(), false);
        }

        if (customerRepository.existsByDpi(request.dpi())) {
            throw new ApiException(HttpStatus.CONFLICT, "El DPI ya está registrado con otro NIT");
        }
        CustomerEntity created = customerRepository.save(CustomerEntity.builder()
                .nit(nit)
                .dpi(request.dpi())
                .fullName(request.fullName().trim())
                .phone(request.phone())
                .address(request.address().trim())
                .email(email)
                .build());
        return new Prepared(created.getId(), true);
    }

    private CustomerEntity complete(UUID customerId, IdentityUserResponse account,
                                    RegisterCustomerRequest request, String email) {
        CustomerEntity customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "No se pudo completar el registro"));
        customer.setFullName(request.fullName().trim());
        customer.setPhone(request.phone());
        customer.setAddress(request.address().trim());
        customer.setEmail(email);
        if (customer.getDpi() == null) {
            customer.setDpi(request.dpi());
        }
        customer.setUserId(account.id());
        if (!walletRepository.existsById(customerId)) {
            walletRepository.save(WalletEntity.builder().customerId(customerId).build());
        }
        return customer;
    }

    private record Prepared(UUID customerId, boolean created) {
    }
}