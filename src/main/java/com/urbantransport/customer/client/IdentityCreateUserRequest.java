package com.urbantransport.customer.client;

import java.util.UUID;

public record IdentityCreateUserRequest(
        String email,
        String password,
        String fullName,
        UUID customerId,
        String nit) {
}