package com.urbantransport.customer.client;

public interface IdentityClient {

    /**
     * Crea el usuario CUSTOMER en identity.
     *
     * @throws com.urbantransport.customer.exception.ApiException 409 si el correo o el cliente ya tienen cuenta,
     *         503 si identity no está disponible o el circuito está abierto
     */
    IdentityUserResponse createCustomerUser(IdentityCreateUserRequest request);
}