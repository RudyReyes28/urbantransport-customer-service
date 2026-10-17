package com.urbantransport.customer.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record IdentityUserResponse(UUID id, String email, String fullName, String role, UUID customerId, String nit) {
}