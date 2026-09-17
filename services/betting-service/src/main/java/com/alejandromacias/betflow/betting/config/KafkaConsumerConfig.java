package com.alejandromacias.betflow.betting.config;

import com.alejandromacias.betflow.betting.odds.OddsChangedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.support.serializer.JsonDeserializer;

/**
 * The mirror image of sportsbook-service's producer configuration: the deserializer is built here,
 * with the application's own {@link ObjectMapper}, instead of being named in configuration and
 * instantiated by the client through a no-argument constructor.
 *
 * <p>Both beans are needed, and the reason is worth recording because declaring only the first one
 * fails in the worst possible way — silently, into a configuration that starts and runs. Spring
 * Boot's auto-configured listener container factory asks for a
 * {@code ConsumerFactory<Object, Object>}, and that request is generic-aware: a factory typed to
 * this service's event does not match it, so Boot falls back to building one from properties
 * alone, where the default value deserializer is {@link StringDeserializer}. The listener then
 * receives a {@code String} where it declared an event — at runtime, on the first record, not at
 * startup.
 */
@Configuration
public class KafkaConsumerConfig {

    @Bean
    ConsumerFactory<String, OddsChangedEvent> oddsConsumerFactory(
            KafkaProperties properties, ObjectMapper objectMapper) {
        return new DefaultKafkaConsumerFactory<>(
                properties.buildConsumerProperties(null),
                new StringDeserializer(),
                oddsValueDeserializer(objectMapper));
    }

    /**
     * The acknowledgement mode is read out of {@code application.yml} rather than hard-coded here,
     * so that configuration stays the place it is decided. Boot's own
     * {@code ConcurrentKafkaListenerContainerFactoryConfigurer} would carry across every
     * {@code spring.kafka.listener.*} setting at once, but it is typed to
     * {@code <Object, Object>} and cannot configure a factory typed to an event; the one setting
     * this service actually makes is copied across explicitly instead.
     */
    @Bean
    ConcurrentKafkaListenerContainerFactory<String, OddsChangedEvent> kafkaListenerContainerFactory(
            KafkaProperties properties, ConsumerFactory<String, OddsChangedEvent> oddsConsumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, OddsChangedEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(oddsConsumerFactory);
        factory.getContainerProperties().setAckMode(properties.getListener().getAckMode());
        return factory;
    }

    /**
     * Type headers are ignored, matching the producer, which no longer sends them. The target type
     * is stated here instead: what arrives is JSON, and this service decides for itself what to
     * build out of it rather than being told by a class name travelling in a header.
     */
    private JsonDeserializer<OddsChangedEvent> oddsValueDeserializer(ObjectMapper objectMapper) {
        return new JsonDeserializer<>(OddsChangedEvent.class, objectMapper, false);
    }
}
