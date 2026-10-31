package com.urbantransport.customer.client;

import com.urbantransport.customer.config.IdentityClientProperties;
import com.urbantransport.customer.exception.ApiException;
import com.urbantransport.customer.exception.IdentityUnavailableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.util.Map;

@Slf4j
@Component
public class IdentityClientImpl implements IdentityClient {

    private static final String CREATE_CUSTOMER_PATH = "/internal/users/customer";
    private static final String INTERNAL_KEY_HEADER = "X-Internal-Key";
    private static final String UNAVAILABLE_MESSAGE =
            "El servicio de autenticación no está disponible, intenta más tarde";

    private final RestClient restClient;
    private final CircuitBreaker circuitBreaker;
    private final JsonMapper jsonMapper;
    private final String apiKey;

    public IdentityClientImpl(IdentityClientProperties properties, JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
        this.apiKey = properties.apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("customer.identity.api-key vacío: identity rechazará la creación de cuentas (defina INTERNAL_API_KEY)");
        }

        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();

        IdentityClientProperties.CircuitBreakerProperties settings = properties.circuitBreaker();
        this.circuitBreaker = CircuitBreaker.of("identity", CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(settings.slidingWindowSize())
                .minimumNumberOfCalls(settings.minimumCalls())
                .failureRateThreshold(settings.failureRateThreshold())
                .waitDurationInOpenState(settings.waitInOpenState())
                .recordExceptions(IdentityUnavailableException.class)
                .build());
        this.circuitBreaker.getEventPublisher()
                .onStateTransition(event -> log.warn("Circuit breaker de identity: {}", event.getStateTransition()));
    }

    @Override
    public IdentityUserResponse createCustomerUser(IdentityCreateUserRequest request) {
        try {
            return circuitBreaker.executeSupplier(() -> post(request));
        } catch (CallNotPermittedException | IdentityUnavailableException exception) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE_MESSAGE);
        }
    }

    private IdentityUserResponse post(IdentityCreateUserRequest request) {
        try {
            return restClient.post()
                    .uri(CREATE_CUSTOMER_PATH)
                    .header(INTERNAL_KEY_HEADER, apiKey == null ? "" : apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .onStatus(status -> status.is5xxServerError(), (req, res) -> {
                        throw new IdentityUnavailableException("Identity respondió " + res.getStatusCode());
                    })
                    .onStatus(status -> status.value() == HttpStatus.CONFLICT.value(), (req, res) -> {
                        throw new ApiException(HttpStatus.CONFLICT, extractMessage(res));
                    })
                    .onStatus(status -> status.is4xxClientError(), (req, res) -> {
                        log.error("Identity rechazó la creación de cuenta: {} (revise INTERNAL_API_KEY)", res.getStatusCode());
                        throw new ApiException(HttpStatus.BAD_GATEWAY, "No se pudo crear la cuenta, intenta más tarde");
                    })
                    .body(IdentityUserResponse.class);
        } catch (ResourceAccessException exception) {
            throw new IdentityUnavailableException("No se pudo contactar a identity", exception);
        }
    }

    /** Toma el "message" del error de identity para que el usuario vea el motivo real (correo repetido, etc.). */
    private String extractMessage(ClientHttpResponse response) {
        try {
            Map<?, ?> body = jsonMapper.readValue(response.getBody(), Map.class);
            if (body != null && body.get("message") != null) {
                return body.get("message").toString();
            }
        } catch (Exception ignored) {
            // si el cuerpo no es el esperado se usa el mensaje genérico
        }
        return "No se pudo crear la cuenta: los datos ya están en uso";
    }
}