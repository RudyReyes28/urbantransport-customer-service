package com.urbantransport.customer.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/customers/register");

    @Test
    @DisplayName("ApiException: usa su status y mensaje")
    void apiException() {
        ResponseEntity<ErrorResponse> response = handler.handleApi(
                new ApiException(HttpStatus.CONFLICT, "Este NIT ya tiene una cuenta"), request);

        assertBody(response, HttpStatus.CONFLICT, "Este NIT ya tiene una cuenta");
    }

    @Test
    @DisplayName("validación: une los errores de campo como 'campo: mensaje; campo: mensaje'")
    void validationErrors() throws Exception {
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "request");
        bindingResult.addError(new FieldError("request", "email", "debe ser un correo válido"));
        bindingResult.addError(new FieldError("request", "dpi", "el DPI debe tener 13 dígitos"));
        MethodParameter parameter = new MethodParameter(
                GlobalExceptionHandlerTest.class.getDeclaredMethod("dummy", String.class), 0);

        ResponseEntity<ErrorResponse> response = handler.handleValidation(
                new MethodArgumentNotValidException(parameter, bindingResult), request);

        assertBody(response, HttpStatus.BAD_REQUEST,
                "email: debe ser un correo válido; dpi: el DPI debe tener 13 dígitos");
    }

    @Test
    @DisplayName("cuerpo ilegible -> 400 con mensaje fijo")
    void unreadableBody() {
        ResponseEntity<ErrorResponse> response = handler.handleUnreadable(
                new HttpMessageNotReadableException("json roto", mock(HttpInputMessage.class)), request);

        assertBody(response, HttpStatus.BAD_REQUEST, "Cuerpo de la petición inválido");
    }

    @Test
    @DisplayName("violación de integridad -> 409 sin exponer detalles de la base")
    void dataIntegrityViolation() {
        ResponseEntity<ErrorResponse> response = handler.handleIntegrity(
                new DataIntegrityViolationException("duplicate key value violates unique constraint uq_customers_nit"),
                request);

        assertBody(response, HttpStatus.CONFLICT, "El registro ya existe o viola una restricción");
    }

    private void assertBody(ResponseEntity<ErrorResponse> response, HttpStatus status, String message) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getStatus()).isEqualTo(status.value());
        assertThat(body.getMessage()).isEqualTo(message);
        assertThat(body.getPath()).isEqualTo("/customers/register");
        assertThat(body.getTimestamp()).isNotNull();
    }

    @SuppressWarnings("unused")
    private void dummy(String value) {
    }
}