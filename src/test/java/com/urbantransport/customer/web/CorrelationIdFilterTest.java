package com.urbantransport.customer.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/customers/me");
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @AfterEach
    void cleanMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("sin cabecera: genera un UUID, lo publica en la respuesta y en el MDC durante la petición")
    void generatesCorrelationId() throws Exception {
        AtomicReference<String> insideChain = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> insideChain.set(MDC.get(CorrelationIdFilter.MDC_KEY)));

        String header = response.getHeader(CorrelationIdFilter.HEADER);
        assertThat(UUID.fromString(header)).isNotNull();
        assertThat(insideChain.get()).isEqualTo(header);
    }

    @Test
    @DisplayName("con cabecera: la respeta")
    void keepsProvidedCorrelationId() throws Exception {
        request.addHeader(CorrelationIdFilter.HEADER, "corr-123");
        AtomicReference<String> insideChain = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> insideChain.set(MDC.get(CorrelationIdFilter.MDC_KEY)));

        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo("corr-123");
        assertThat(insideChain.get()).isEqualTo("corr-123");
    }

    @Test
    @DisplayName("cabecera en blanco: se reemplaza por un UUID")
    void blankHeaderIsReplaced() throws Exception {
        request.addHeader(CorrelationIdFilter.HEADER, "   ");

        filter.doFilter(request, response, (req, res) -> { });

        assertThat(UUID.fromString(response.getHeader(CorrelationIdFilter.HEADER))).isNotNull();
    }

    @Test
    @DisplayName("el MDC se limpia al terminar, incluso si la cadena falla")
    void mdcIsClearedEvenOnFailure() {
        assertThatThrownBy(() -> filter.doFilter(request, response, (req, res) -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }
}