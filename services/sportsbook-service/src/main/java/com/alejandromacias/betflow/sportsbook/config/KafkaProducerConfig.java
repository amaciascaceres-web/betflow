package com.alejandromacias.betflow.sportsbook.config;

import com.alejandromacias.betflow.sportsbook.odds.OddsChangedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

/**
 * Wires the value serializer with the application's own Jackson {@link ObjectMapper}.
 *
 * <p>Declaring {@code value-serializer: JsonSerializer} in configuration alone is not enough: the
 * client instantiates it by class name with a no-argument constructor, so it builds an
 * {@code ObjectMapper} of its own that has nothing to do with the one Spring Boot configured.
 * The visible symptom is an {@link java.time.Instant} going out as {@code 1789378312.540544000}
 * instead of an ISO-8601 string — which is a change to this service's published contract, made by
 * accident, and one every consumer would then have to be written around.
 */
@Configuration
public class KafkaProducerConfig {

    @Bean
    ProducerFactory<String, OddsChangedEvent> oddsProducerFactory(
            KafkaProperties properties, ObjectMapper objectMapper) {
        DefaultKafkaProducerFactory<String, OddsChangedEvent> factory =
                new DefaultKafkaProducerFactory<>(properties.buildProducerProperties(null));
        factory.setKeySerializer(new StringSerializer());
        factory.setValueSerializer(new JsonSerializer<>(objectMapper));
        return factory;
    }

    @Bean
    KafkaTemplate<String, OddsChangedEvent> oddsKafkaTemplate(
            ProducerFactory<String, OddsChangedEvent> oddsProducerFactory) {
        return new KafkaTemplate<>(oddsProducerFactory);
    }
}
