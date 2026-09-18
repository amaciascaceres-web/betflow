package com.alejandromacias.betflow.betting.odds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.alejandromacias.betflow.betting.support.PostgresBackedTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;

/**
 * One test, for the one thing no other test can reach: the path from the wire to the column.
 *
 * <p>The projection's three properties live in {@link OddsProjectionServiceTest} and the query's
 * semantics in {@link SelectionOddsRepositoryTest}, both without a broker. What is left over is
 * everything between them and the topic — that the listener is registered at all, that the
 * deserializer builds this service's own record from JSON carrying no type header, that a value
 * survives {@code Instant → JSON → parameter → TIMESTAMPTZ} and comes back the same, and that the
 * transaction commits before the offset is acknowledged.
 *
 * <p>That list is not theoretical: day 2 published timestamps as epoch decimals and day 3 shipped
 * a Java class name in a header. Both would pass every test above this one.
 */
@SpringBootTest(properties = "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}")
@EmbeddedKafka(partitions = 6, topics = OddsChangedEndToEndTest.TOPIC)
class OddsChangedEndToEndTest extends PostgresBackedTest {

    static final String TOPIC = "odds-changed";

    @Autowired
    private EmbeddedKafkaBroker broker;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SelectionOddsRepository repository;

    private KafkaTemplate<String, OddsChangedEvent> template;

    @BeforeEach
    void setUp() {
        Map<String, Object> producerProps = KafkaTestUtils.producerProps(broker);
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);

        // Publishes what sportsbook-service publishes: JSON with no type header. Sending one would
        // make this test stop resembling the producer it stands in for.
        JsonSerializer<OddsChangedEvent> valueSerializer = new JsonSerializer<>(objectMapper);
        valueSerializer.setAddTypeInfo(false);

        template = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(
                producerProps, new StringSerializer(), valueSerializer));
    }

    @AfterEach
    void tearDown() {
        ((DefaultKafkaProducerFactory<String, OddsChangedEvent>) template.getProducerFactory()).destroy();
    }

    @Test
    void anEventPublishedToTheTopicReachesTheProjectionIntact() {
        UUID selectionId = UUID.randomUUID();
        // Microseconds, because that is what a TIMESTAMPTZ keeps and what an Instant produced by
        // the running producer carries. Truncating here rather than after the round trip means a
        // column that quietly stored less would show up as a failure.
        Instant changedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        OddsChangedEvent event = new OddsChangedEvent(
                UUID.randomUUID(), UUID.randomUUID(), selectionId, new BigDecimal("2.350"), changedAt);

        publish(event);

        await().atMost(Duration.ofSeconds(20))
                .until(() -> repository.findViewBySelectionId(selectionId).isPresent());
        SelectionOddsDto stored = repository.findViewBySelectionId(selectionId).orElseThrow();

        assertThat(stored.marketId()).isEqualTo(event.marketId());
        assertThat(stored.odds()).isEqualByComparingTo("2.350");
        assertThat(stored.oddsUpdatedAt()).isEqualTo(changedAt);
    }

    private void publish(OddsChangedEvent event) {
        try {
            template.send(TOPIC, event.marketId().toString(), event).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while publishing", e);
        } catch (ExecutionException e) {
            throw new AssertionError("could not publish " + event, e);
        }
    }
}
