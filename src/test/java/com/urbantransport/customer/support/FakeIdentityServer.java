package com.urbantransport.customer.support;

import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Identity falso en proceso: publica el JWKS con el que se validan los tokens de prueba, firma esos tokens y
 * atiende POST /internal/users/customer con las mismas reglas de unicidad del identity real.
 */
public final class FakeIdentityServer {

    public static final String KEY_ID = "it-key";

    public record Received(String apiKey, String body) {
    }

    private final String expectedApiKey;
    private final RSAKey signingKey;
    private final JwtEncoder encoder;
    private final HttpServer server;
    private final ExecutorService executor = Executors.newFixedThreadPool(8);
    private final Set<String> emails = ConcurrentHashMap.newKeySet();
    private final Set<String> customerIds = ConcurrentHashMap.newKeySet();
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final AtomicInteger accounts = new AtomicInteger();
    private volatile int forcedStatus;
    private volatile long delayMillis;
    private JwtEncoder foreignEncoder;

    public FakeIdentityServer(String expectedApiKey) {
        this.expectedApiKey = expectedApiKey;
        this.signingKey = generateKey();
        this.encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(signingKey)));
        try {
            this.server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        server.setExecutor(executor);
        server.createContext("/.well-known/jwks.json", this::serveJwks);
        server.createContext("/internal/users/customer", this::createAccount);
        server.start();
    }

    public String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    public String jwksUri() {
        return baseUrl() + "/.well-known/jwks.json";
    }

    // ------------------------------------------------------------------ control desde los tests

    public void reset() {
        emails.clear();
        customerIds.clear();
        received.clear();
        accounts.set(0);
        forcedStatus = 0;
        delayMillis = 0;
    }

    /** Todas las creaciones de cuenta responderán con este status (0 = comportamiento normal). */
    public void failWith(int status) {
        this.forcedStatus = status;
    }

    public void delay(long millis) {
        this.delayMillis = millis;
    }

    public List<Received> received() {
        return received;
    }

    public int accountsCreated() {
        return accounts.get();
    }

    // ------------------------------------------------------------------ tokens

    public String customerToken(UUID customerId) {
        return token("CUSTOMER", customerId);
    }

    public String token(String role, UUID customerId) {
        return sign(encoder, role, customerId, Instant.now().plusSeconds(600));
    }

    public String expiredToken(String role, UUID customerId) {
        // Spring Security tolera 60 s de desfase de reloj: se vence con 10 minutos de margen
        return sign(encoder, role, customerId, Instant.now().minusSeconds(600));
    }

    /** Mismo kid pero firmado con otra llave: debe ser rechazado. */
    public synchronized String foreignToken(String role, UUID customerId) {
        if (foreignEncoder == null) {
            foreignEncoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(generateKey())));
        }
        return sign(foreignEncoder, role, customerId, Instant.now().plusSeconds(600));
    }

    private static String sign(JwtEncoder jwtEncoder, String role, UUID customerId, Instant expiresAt) {
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .subject(UUID.randomUUID().toString())
                .issuedAt(Instant.now().minusSeconds(3600))
                .expiresAt(expiresAt)
                .claim("roles", List.of(role));
        if (customerId != null) {
            claims.claim("customerId", customerId.toString());
        }
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(KEY_ID).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }

    // ------------------------------------------------------------------ endpoints

    private void serveJwks(HttpExchange exchange) throws IOException {
        reply(exchange, 200, new JWKSet(signingKey.toPublicJWK()).toString());
    }

    private void createAccount(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String apiKey = exchange.getRequestHeaders().getFirst("X-Internal-Key");
        received.add(new Received(apiKey, body));
        if (delayMillis > 0) {
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        if (!expectedApiKey.equals(apiKey)) {
            reply(exchange, 401, "{\"message\":\"Clave interna inválida\"}");
            return;
        }
        if (forcedStatus > 0) {
            reply(exchange, forcedStatus, "{\"message\":\"error simulado\"}");
            return;
        }
        String email = JsonPath.<String>read(body, "$.email").toLowerCase(Locale.ROOT);
        String customerId = JsonPath.read(body, "$.customerId");
        if (!emails.add(email)) {
            reply(exchange, 409, "{\"message\":\"Ya existe un usuario con el correo " + email + "\"}");
            return;
        }
        if (!customerIds.add(customerId)) {
            emails.remove(email);
            reply(exchange, 409, "{\"message\":\"El cliente ya tiene una cuenta\"}");
            return;
        }
        accounts.incrementAndGet();
        String nit = JsonPath.read(body, "$.nit");
        String fullName = JsonPath.read(body, "$.fullName");
        reply(exchange, 201, "{\"id\":\"" + UUID.randomUUID() + "\",\"email\":\"" + email + "\",\"fullName\":\""
                + fullName + "\",\"role\":\"CUSTOMER\",\"customerId\":\"" + customerId + "\",\"nit\":\"" + nit + "\"}");
    }

    private static void reply(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
        exchange.close();
    }

    private static RSAKey generateKey() {
        try {
            return new RSAKeyGenerator(2048).keyID(KEY_ID).generate();
        } catch (JOSEException exception) {
            throw new IllegalStateException(exception);
        }
    }
}