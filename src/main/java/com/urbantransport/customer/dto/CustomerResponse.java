package com.urbantransport.customer.dto;


import com.urbantransport.customer.domain.CustomerEntity;
import lombok.Value;

import java.time.LocalDateTime;
import java.util.UUID;

@Value
public class CustomerResponse {
    UUID id;
    String nit;
    String dpi;
    String fullName;
    String phone;
    String address;
    String email;
    UUID userId;
    LocalDateTime createdAt;

    public static CustomerResponse fromEntity(CustomerEntity customer) {
        return new CustomerResponse(
                customer.getId(),
                customer.getNit(),
                customer.getDpi(),
                customer.getFullName(),
                customer.getPhone(),
                customer.getAddress(),
                customer.getEmail(),
                customer.getUserId(),
                customer.getCreatedAt());
    }
}