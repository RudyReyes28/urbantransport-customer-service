package com.urbantransport.customer.service;

import com.urbantransport.customer.dto.CustomerResponse;
import com.urbantransport.customer.dto.RegisterCustomerRequest;

public interface CustomerRegistrationService {

    CustomerResponse register(RegisterCustomerRequest request);
}