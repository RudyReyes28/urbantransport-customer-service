package com.urbantransport.customer.event;

import lombok.Value;

import java.util.UUID;

@Value
public class CustomerNitChangedPayload {
    UUID customerId;
    String nit;
}