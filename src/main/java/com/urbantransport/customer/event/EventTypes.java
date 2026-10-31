package com.urbantransport.customer.event;

public final class EventTypes {

    public static final String AGGREGATE_CUSTOMER = "Customer";
    public static final String AGGREGATE_PAYMENT = "Payment";

    public static final String CUSTOMER_NIT_CHANGED = "CustomerNitChanged";

    public static final String PAYMENT_REQUESTED = "PaymentRequested";
    public static final String REFUND_REQUESTED = "RefundRequested";
    public static final String PAYMENT_COMPLETED = "PaymentCompleted";
    public static final String PAYMENT_FAILED = "PaymentFailed";
    public static final String REFUND_COMPLETED = "RefundCompleted";
    public static final String WALLET_RECHARGED = "WalletRecharged";
    public static final String REFERENCE_TICKET = "TICKET";

    private EventTypes() {
    }
}