package com.urbantransport.customer.client;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.urbantransport.customer.config.IdentityClientProperties;
import com.urbantransport.customer.config.IdentityClientProperties.CircuitBreakerProperties;
import com.urbantransport.customer.exception.ApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.urbantransport.customer.support.ApiAssertions.assertApiError;
import static org.assertj.core.api.Assertions.assertThat;

class IdentityClientImplTest {

    private static final String UNAVAILABLE = "El servicio de autenticación no está disponible, intenta más tarde";

    private HttpServer server;
    private ExecutorService executor;
    private int port;

    private final AtomicInteger hits = new AtomicInteger();
    private final AtomicReference<String> lastMethod = new AtomicReference<>();
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<String> lastKey = new AtomicReference<>();
    private final AtomicReference<String> lastContentType = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private volatile int responseStatus = 201;
    private volatile String responseBody = "";
    private volatile long delayMillis = 0;

    private final UUID userId = UUID.randomUUID();
    private final UUID customerId = UUID.randomUUID();

    @BeforeEach
    void startFakeIdentity() throws IOException {
        executor = Executors.newFixedThreadPool(4);
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
        port = server.getAddress().getPort();
        responseBody = accountJson();
    }

    @AfterEach
    void stopFakeIdentity() {
        server.stop(0);
        executor.shutdownNow();
    }

    // ------------------------------------------------------------------ éxito

    @Test
    @DisplayName("201: devuelve la cuenta, envía la clave interna y el cuerpo esperado y ignora campos desconocidos")
    void createsTheAccount() {
        IdentityClientImpl client = client("secret-key", Duration.ofSeconds(2), 10, 5, Duration.ofSeconds(30));

        IdentityUserResponse response = client.createCustomerUser(request());

        assertThat(response.id()).isEqualTo(userId);
        assertThat(response.role()).isEqualTo("CUSTOMER");
        assertThat(response.customerId()).isEqualTo(customerId);
        assertThat(lastMethod.get()).isEqualTo("POST");
        assertThat(lastPath.get()).isEqualTo("/internal/users/customer");
        assertThat(lastKey.get()).isEqualTo("secret-key");
        assertThat(lastContentType.get()).contains("application/json");
        assertThat((String) JsonPath.read(lastBody.get(), "$.email")).isEqualTo("ana@mail.com");
        assertThat((String) JsonPath.read(lastBody.get(), "$.nit")).isEqualTo("12345678");
        assertThat((String) JsonPath.read(lastBody.get(), "$.customerId")).isEqualTo(customerId.toString());
    }

    @Test
    @DisplayName("sin api key configurada se envía la cabecera vacía (identity la rechazará)")
    void blankApiKeySendsEmptyHeader() {
        IdentityClientImpl client = client(null, Duration.ofSeconds(2), 10, 5, Duration.ofSeconds(30));

        client.createCustomerUser(request());

        assertThat(lastKey.get()).isNullOrEmpty();
    }

    // ------------------------------------------------------------------ errores de identity

    @Test
    @DisplayName("409: se relanza como 409 con el mensaje real que devolvió identity")
    void conflictKeepsIdentityMessage() {
        responseStatus = 409;
        responseBody = "{\"status\":409,\"message\":\"Ya existe un usuario con el correo ana@mail.com\"}";

        assertApiError(() -> defaultClient().createCustomerUser(request()), HttpStatus.CONFLICT,
                "Ya existe un usuario con el correo ana@mail.com");
    }

    @Test
    @DisplayName("409 con cuerpo vacío o no JSON: mensaje genérico")
    void conflictWithUnreadableBody() {
        responseStatus = 409;
        responseBody = "";
        assertApiError(() -> defaultClient().createCustomerUser(request()), HttpStatus.CONFLICT,
                "No se pudo crear la cuenta: los datos ya están en uso");

        responseBody = "<html>conflicto</html>";
        assertApiError(() -> defaultClient().createCustomerUser(request()), HttpStatus.CONFLICT,
                "No se pudo crear la cuenta: los datos ya están en uso");
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404})
    @DisplayName("otros 4xx (p. ej. clave interna incorrecta) -> 502 con mensaje genérico")
    void otherClientErrorsBecomeBadGateway(int status) {
        responseStatus = status;
        responseBody = "{}";

        assertApiError(() -> defaultClient().createCustomerUser(request()), HttpStatus.BAD_GATEWAY,
                "No se pudo crear la cuenta, intenta más tarde");
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 502, 503})
    @DisplayName("5xx de identity -> 503")
    void serverErrorsBecomeUnavailable(int status) {
        responseStatus = status;
        responseBody = "{}";

        assertApiError(() -> defaultClient().createCustomerUser(request()), HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE);
    }

    @Test
    @DisplayName("identity no acepta conexiones -> 503")
    void connectionRefused() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        IdentityClientImpl client = new IdentityClientImpl(new IdentityClientProperties(
                "http://localhost:" + closedPort, "k", Duration.ofSeconds(1), Duration.ofSeconds(1),
                new CircuitBreakerProperties(10, 5, 50f, Duration.ofSeconds(30))), JsonMapper.builder().build());

        assertApiError(() -> client.createCustomerUser(request()), HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE);
    }

    @Test
    @DisplayName("identity responde más lento que el read-timeout -> 503")
    void readTimeout() {
        delayMillis = 1500;
        IdentityClientImpl client = client("k", Duration.ofMillis(300), 10, 5, Duration.ofSeconds(30));

        assertApiError(() -> client.createCustomerUser(request()), HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE);
    }

    // ------------------------------------------------------------------ circuit breaker

    @Test
    @DisplayName("tras suficientes fallos 5xx el circuito se abre y deja de llamar a identity")
    void circuitOpensAfterRepeatedFailures() {
        responseStatus = 500;
        IdentityClientImpl client = client("k", Duration.ofSeconds(2), 2, 2, Duration.ofSeconds(30));

        assertApiError(() -> client.createCustomerUser(request()), HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE);
        assertApiError(() -> client.createCustomerUser(request()), HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE);
        assertThat(hits).hasValue(2);

        // Circuito abierto: responde 503 sin llegar al servidor
        assertApiError(() -> client.createCustomerUser(request()), HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE);
        assertThat(hits).hasValue(2);
    }

    @Test
    @DisplayName("los 409 y 4xx NO cuentan como fallo: el circuito sigue cerrado")
    void businessErrorsDoNotOpenTheCircuit() {
        responseStatus = 409;
        responseBody = "{\"message\":\"duplicado\"}";
        IdentityClientImpl client = client("k", Duration.ofSeconds(2), 2, 2, Duration.ofSeconds(30));
        for (int i = 0; i < 5; i++) {
            assertApiError(() -> client.createCustomerUser(request()), HttpStatus.CONFLICT, "duplicado");
        }

        responseStatus = 201;
        responseBody = accountJson();

        assertThat(client.createCustomerUser(request()).id()).isEqualTo(userId);
        assertThat(hits).hasValue(6);
    }

    @Test
    @DisplayName("después del tiempo en estado abierto el circuito deja pasar una llamada y se recupera")
    void circuitRecoversAfterWaitDuration() throws Exception {
        responseStatus = 500;
        IdentityClientImpl client = client("k", Duration.ofSeconds(2), 2, 2, Duration.ofMillis(300));
        for (int i = 0; i < 2; i++) {
            assertApiError(() -> client.createCustomerUser(request()), HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE);
        }

        Thread.sleep(600);
        responseStatus = 201;
        responseBody = accountJson();

        assertThat(client.createCustomerUser(request()).id()).isEqualTo(userId);
    }

    // ------------------------------------------------------------------ helpers

    private void handle(HttpExchange exchange) throws IOException {
        hits.incrementAndGet();
        lastMethod.set(exchange.getRequestMethod());
        lastPath.set(exchange.getRequestURI().getPath());
        lastKey.set(exchange.getRequestHeaders().getFirst("X-Internal-Key"));
        lastContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
        lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        if (delayMillis > 0) {
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(responseStatus, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
        exchange.close();
    }

    private IdentityClientImpl defaultClient() {
        return client("k", Duration.ofSeconds(2), 10, 5, Duration.ofSeconds(30));
    }

    private IdentityClientImpl client(String apiKey, Duration readTimeout, int window, int minCalls, Duration waitOpen) {
        return new IdentityClientImpl(new IdentityClientProperties("http://localhost:" + port, apiKey,
                Duration.ofSeconds(2), readTimeout, new CircuitBreakerProperties(window, minCalls, 50f, waitOpen)),
                JsonMapper.builder().build());
    }

    private IdentityCreateUserRequest request() {
        return new IdentityCreateUserRequest("ana@mail.com", "Ana Pérez", customerId, "12345678");
    }

    private String accountJson() {
        return "{\"id\":\"" + userId + "\",\"email\":\"ana@mail.com\",\"fullName\":\"Ana Pérez\",\"role\":\"CUSTOMER\","
                + "\"customerId\":\"" + customerId + "\",\"nit\":\"12345678\",\"campoNuevo\":\"se ignora\"}";
    }
}