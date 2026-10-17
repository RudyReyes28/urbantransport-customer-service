package com.urbantransport.customer.exception;

/** Identity no respondió o respondió con error de servidor: es el único fallo que cuenta para abrir el circuito. */
public class IdentityUnavailableException extends RuntimeException {

    public IdentityUnavailableException(String message) {
        super(message);
    }

    public IdentityUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}