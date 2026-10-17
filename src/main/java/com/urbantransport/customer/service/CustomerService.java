package com.urbantransport.customer.service;

import com.urbantransport.customer.dto.CustomerResponse;
import com.urbantransport.customer.dto.CustomerSummaryResponse;
import com.urbantransport.customer.dto.UpdateProfileRequest;

import java.util.UUID;

public interface CustomerService {

    CustomerResponse getProfile(UUID customerId);

    CustomerResponse updateProfile(UUID customerId, UpdateProfileRequest request);

    CustomerSummaryResponse findSummaryByNit(String nit);
}