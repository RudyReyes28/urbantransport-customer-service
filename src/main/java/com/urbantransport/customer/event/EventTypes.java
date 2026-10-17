package com.urbantransport.customer.event;

public final class EventTypes {

    public static final String AGGREGATE_CUSTOMER = "Customer";
    public static final String AGGREGATE_PAYMENT = "Payment";

    public static final String CUSTOMER_NIT_CHANGED = "CustomerNitChanged";

    private EventTypes() {
    }
}