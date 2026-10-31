package com.urbantransport.customer.integration;

import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import com.urbantransport.customer.support.FakeIdentityServer;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Levanta customer completo: PostgreSQL real embebido (Flyway), Kafka embebido, identity falso en proceso.
 * Sin @Transactional a propósito: se prueba el comportamiento transaccional real.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("it")
@EmbeddedKafka(
        partitions = 1,
        bootstrapServersProperty = "spring.kafka.bootstrap-servers",
        topics = {
                AbstractCustomerIT.TICKET_TOPIC, AbstractCustomerIT.RENTAL_TOPIC,
                AbstractCustomerIT.TICKET_TOPIC + ".DLT", AbstractCustomerIT.RENTAL_TOPIC + ".DLT"})
public abstract class AbstractCustomerIT {

    public static final String CUSTOMER_TOPIC = "customer-events-it";
    public static final String PAYMENT_TOPIC = "payment-events-it";
    public static final String TICKET_TOPIC = "ticket-events-it";
    public static final String RENTAL_TOPIC = "rental-events-it";

    protected static final String API_KEY = "it-internal-key";
    protected static final UUID GENERIC_CUSTOMER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    protected static final FakeIdentityServer IDENTITY = new FakeIdentityServer(API_KEY);
    private static final EmbeddedPostgres POSTGRES = startPostgres();

    protected record Account(UUID customerId, UUID userId, String nit) {
    }

    @Autowired
    protected MockMvc mockMvc;
    @Autowired
    protected JsonMapper jsonMapper;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected KafkaTemplate<String, String> kafkaTemplate;
    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @DynamicPropertySource
    static void dynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("customer.kafka.customer-events-topic", () -> CUSTOMER_TOPIC);
        registry.add("customer.kafka.payment-events-topic", () -> PAYMENT_TOPIC);
        registry.add("customer.kafka.ticket-events-topic", () -> TICKET_TOPIC);
        registry.add("customer.kafka.rental-events-topic", () -> RENTAL_TOPIC);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", IDENTITY::jwksUri);
        registry.add("customer.identity.base-url", IDENTITY::baseUrl);
        registry.add("customer.identity.api-key", () -> API_KEY);
    }

    /** Limpia la base (conservando al Consumidor Final de la migración) y el identity falso. */
    @BeforeEach
    protected void resetState() {
        jdbc.update("DELETE FROM wallet_transactions");
        jdbc.update("DELETE FROM wallets");
        jdbc.update("DELETE FROM outbox_events");
        jdbc.update("DELETE FROM processed_events");
        jdbc.update("DELETE FROM customers WHERE is_generic = FALSE");
        IDENTITY.reset();
    }

    // ------------------------------------------------------------------ HTTP

    protected ResultActions send(MockHttpServletRequestBuilder request, String token, Object body) throws Exception {
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(jsonMapper.writeValueAsString(body));
        }
        return mockMvc.perform(request);
    }

    protected static String responseBody(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString();
    }

    protected static <T> T read(String json, String path) {
        return JsonPath.read(json, path);
    }

    /** Como read, pero devuelve null si la ruta no existe o su valor es null. */
    protected static <T> T readOrNull(String json, String path) {
        try {
            return JsonPath.read(json, path);
        } catch (PathNotFoundException exception) {
            return null;
        }
    }

    protected static BigDecimal decimal(String json, String path) {
        return new BigDecimal(String.valueOf((Object) read(json, path)));
    }

    protected int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    // ------------------------------------------------------------------ datos de prueba

    protected static String dpi(int n) {
        return String.format("%013d", n);
    }

    protected static Map<String, Object> registerBody(String nit, String dpi, String email) {
        return Map.of("email", email, "password", "Password123", "fullName", "Ana Pérez", "nit", nit,
                "dpi", dpi, "phone", "55512345", "address", "Zona 1");
    }

    /** Registra un cliente con cuenta por el endpoint público (pasa por el identity falso). */
    protected Account registerCustomer(String nit, String dpi, String email) throws Exception {
        String response = responseBody(send(post("/customers/register"), null, registerBody(nit, dpi, email))
                .andExpect(status().isCreated()));
        return new Account(UUID.fromString(read(response, "$.id")), UUID.fromString(read(response, "$.userId")),
                read(response, "$.nit"));
    }

    protected String staffToken() {
        return IDENTITY.token("CASHIER", null);
    }

    protected void fundWallet(UUID customerId, String amount) throws Exception {
        send(post("/wallet/recharge"), IDENTITY.customerToken(customerId), Map.of("amount", new BigDecimal(amount)))
                .andExpect(status().isOk());
    }

    protected BigDecimal balanceOf(UUID customerId) {
        return jdbc.queryForObject("SELECT balance FROM wallets WHERE customer_id = ?", BigDecimal.class, customerId);
    }

    // ------------------------------------------------------------------ Kafka

    protected String event(String type, UUID eventId, Map<String, Object> payload) {
        return jsonMapper.writeValueAsString(Map.of("eventId", eventId.toString(), "eventType", type, "payload", payload));
    }

    protected void publish(String topic, String key, String message) throws Exception {
        kafkaTemplate.send(topic, key, message).get(10, TimeUnit.SECONDS);
    }

    protected void awaitProcessed(UUID eventId) {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(count("SELECT count(*) FROM processed_events WHERE event_id = ?", eventId)).isEqualTo(1));
    }

    /** Lee el tópico desde el inicio con un grupo nuevo hasta juntar los mensajes esperados o agotar el tiempo. */
    protected List<ConsumerRecord<String, String>> awaitRecords(String topic,
                                                                Predicate<ConsumerRecord<String, String>> filter,
                                                                int expected, Duration timeout) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "it-probe-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (filter.test(record)) {
                        found.add(record);
                    }
                }
                if (found.size() >= expected) {
                    return found;
                }
            }
        }
        throw new AssertionError("En " + topic + " llegaron " + found.size() + " de " + expected + " mensajes esperados");
    }

    // ------------------------------------------------------------------ concurrencia

    /** Ejecuta las tareas a la vez; cada resultado es el valor devuelto o la excepción lanzada. */
    protected <T> List<Object> runInParallel(List<Callable<T>> tasks) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (Callable<T> task : tasks) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    return task.call();
                } catch (Throwable failure) {
                    return failure;
                }
            }));
        }
        ready.await();
        go.countDown();
        List<Object> results = new ArrayList<>();
        for (Future<Object> future : futures) {
            results.add(future.get(60, TimeUnit.SECONDS));
        }
        executor.shutdown();
        return results;
    }

    // ------------------------------------------------------------------ PostgreSQL embebido

    private static EmbeddedPostgres startPostgres() {
        try {
            EmbeddedPostgres postgres = EmbeddedPostgres.builder().start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    postgres.close();
                } catch (IOException ignored) {
                    // se cierra al terminar la JVM
                }
            }));
            return postgres;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}