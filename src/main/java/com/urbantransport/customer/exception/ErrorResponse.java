package com.urbantransport.customer.exception;

import lombok.Value;

import java.time.Instant;

@Value
public class ErrorResponse {
    Instant timestamp;
    int status;
    String message;
    String path;
}