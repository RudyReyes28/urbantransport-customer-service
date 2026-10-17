package com.urbantransport.customer.dto;

import com.urbantransport.customer.domain.CustomerEntity;
import lombok.Value;

import java.util.UUID;

@Value
public class CustomerSummaryResponse {
    UUID id;
    String nit;
    String fullName;
    boolean generic;
    boolean hasAccount;

    public static CustomerSummaryResponse fromEntity(CustomerEntity customer) {
        return new CustomerSummaryResponse(customer.getId(), customer.getNit(), customer.getFullName(),
                customer.isGeneric(), customer.getUserId() != null);
    }
}