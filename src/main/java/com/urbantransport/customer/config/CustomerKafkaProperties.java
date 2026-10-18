package com.urbantransport.customer.config;


import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "customer.kafka")
public record CustomerKafkaProperties(
        String customerEventsTopic,
        String paymentEventsTopic,
        String ticketEventsTopic,
        String rentalEventsTopic) {
}