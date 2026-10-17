package com.urbantransport.customer.service;

import java.util.UUID;

public interface OutboxService {

    /** Registra un evento en la misma transacción del cambio de negocio (falla si no hay transacción activa). */
    void record(String aggregateType, UUID aggregateId, String eventType, Object payload);
}