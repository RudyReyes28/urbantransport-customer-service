package com.urbantransport.customer.event;

public final class PaymentFailureReason {

    public static final String INSUFFICIENT_FUNDS = "INSUFFICIENT_FUNDS";
    public static final String CUSTOMER_NOT_FOUND = "CUSTOMER_NOT_FOUND";
    public static final String CUSTOMER_WITHOUT_ACCOUNT = "CUSTOMER_WITHOUT_ACCOUNT";
    public static final String INVALID_AMOUNT = "INVALID_AMOUNT";

    private PaymentFailureReason() {
    }
}