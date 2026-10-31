package com.urbantransport.customer.support;

import com.urbantransport.customer.exception.ApiException;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public final class ApiAssertions {

    private ApiAssertions() {
    }

    /** Verifica que se lance ApiException con el status y mensaje esperados. */
    public static void assertApiError(ThrowingCallable call, HttpStatus status, String message) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, exception -> {
            assertThat(exception.getStatus()).isEqualTo(status);
            assertThat(exception.getMessage()).isEqualTo(message);
        });
    }
}