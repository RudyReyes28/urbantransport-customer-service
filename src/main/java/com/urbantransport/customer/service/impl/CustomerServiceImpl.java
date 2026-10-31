package com.urbantransport.customer.service.impl;

import com.urbantransport.customer.domain.CustomerEntity;
import com.urbantransport.customer.dto.CreateCustomerRequest;
import com.urbantransport.customer.event.CustomerNitChangedPayload;
import com.urbantransport.customer.event.EventTypes;
import com.urbantransport.customer.exception.ApiException;
import com.urbantransport.customer.repository.CustomerRepository;
import com.urbantransport.customer.service.CustomerService;
import com.urbantransport.customer.service.OutboxService;
import com.urbantransport.customer.util.NitUtils;
import com.urbantransport.customer.dto.CustomerResponse;
import com.urbantransport.customer.dto.CustomerSummaryResponse;
import com.urbantransport.customer.dto.UpdateProfileRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CustomerServiceImpl implements CustomerService {

    private final CustomerRepository customerRepository;
    private final OutboxService outboxService;

    @Override
    @Transactional(readOnly = true)
    public CustomerResponse getProfile(UUID customerId) {
        return CustomerResponse.fromEntity(getOrThrow(customerId));
    }

    @Override
    @Transactional
    public CustomerResponse updateProfile(UUID customerId, UpdateProfileRequest request) {
        CustomerEntity customer = getOrThrow(customerId);

        String nit = NitUtils.normalize(request.nit());
        if (!NitUtils.isValid(nit)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "El NIT no tiene un formato válido");
        }
        boolean nitChanged = !nit.equals(customer.getNit());
        if (nitChanged && customerRepository.existsByNit(nit)) {
            throw new ApiException(HttpStatus.CONFLICT, "Ese NIT ya pertenece a otro cliente");
        }
        if (!request.dpi().equals(customer.getDpi()) && customerRepository.existsByDpi(request.dpi())) {
            throw new ApiException(HttpStatus.CONFLICT, "Ese DPI ya pertenece a otro cliente");
        }

        customer.setFullName(request.fullName().trim());
        customer.setNit(nit);
        customer.setDpi(request.dpi());
        customer.setPhone(request.phone());
        customer.setAddress(request.address().trim());

        if (nitChanged) {
            outboxService.record(EventTypes.AGGREGATE_CUSTOMER, customer.getId(),
                    EventTypes.CUSTOMER_NIT_CHANGED, new CustomerNitChangedPayload(customer.getId(), nit));
        }
        return CustomerResponse.fromEntity(customer);
    }

    @Override
    @Transactional(readOnly = true)
    public CustomerSummaryResponse findSummaryByNit(String nit) {
        return customerRepository.findByNit(NitUtils.normalize(nit))
                .map(CustomerSummaryResponse::fromEntity)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No existe un cliente con ese NIT"));
    }

    @Override
    @Transactional
    public CustomerSummaryResponse createWithoutAccount(CreateCustomerRequest request) {
        String nit = NitUtils.normalize(request.nit());
        if (customerRepository.existsByNit(nit)) {
            throw new ApiException(HttpStatus.CONFLICT, "Ya existe un cliente con ese NIT");
        }
        if (!NitUtils.isValid(nit)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "El NIT no tiene un formato válido");
        }
        if (request.dpi() != null && customerRepository.existsByDpi(request.dpi())) {
            throw new ApiException(HttpStatus.CONFLICT, "Ese DPI ya está registrado con otro NIT");
        }

        CustomerEntity saved = customerRepository.save(CustomerEntity.builder()
                .nit(nit)
                .dpi(request.dpi())
                .fullName(request.fullName().trim())
                .phone(request.phone())
                .address(request.address() == null ? null : request.address().trim())
                .build());
        return CustomerSummaryResponse.fromEntity(saved);
    }

    private CustomerEntity getOrThrow(UUID customerId) {
        return customerRepository.findById(customerId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Cliente no encontrado"));
    }
}