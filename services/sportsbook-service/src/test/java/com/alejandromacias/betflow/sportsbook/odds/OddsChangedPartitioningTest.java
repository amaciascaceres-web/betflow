package com.alejandromacias.betflow.sportsbook.odds;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.condition.EmbeddedKafkaCondition;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;

/**
 * Automates what day 2's verification did by hand with {@code kafka-console-consumer.sh}: that
 * every event for one {@code marketId} lands on the same partition, and arrives in the order it
 * was sent.
 *
 * <p>Deliberately not a {@code @SpringBootTest}: this only exercises {@link OddsChangedProducer}
 * against a real (embedded) broker, so it needs neither Postgres nor the rest of the
 * application context — {@link EmbeddedKafkaCondition} runs as a plain JUnit 5 extension and
 * hands the broker to the test directly.
 *
 * <p>An embedded broker is a real Kafka broker running in-process, not a stub — the partitioning
 * behaviour under test is the actual client hashing a key against a real partition count, the
 * same mechanism a producer talking to the broker in {@code docker-compose.yml} would go
 * through.
 */
@EmbeddedKafka(partitions = 6, topics = "odds-changed")
class OddsChangedPartitioningTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final EmbeddedKafkaBroker broker = EmbeddedKafkaCondition.getBroker();

    private KafkaTemplate<String, OddsChangedEvent> template;
    private OddsChangedProducer producer;
    private Consumer<String, String> consumer;

    @BeforeEach
    void setUp() {
        Map<String, Object> producerProps = KafkaTestUtils.producerProps(broker);
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        DefaultKafkaProducerFactory<String, OddsChangedEvent> producerFactory =
                new DefaultKafkaProducerFactory<>(producerProps, new StringSerializer(), new JsonSerializer<>());
        template = new KafkaTemplate<>(producerFactory);
        producer = new OddsChangedProducer(template);

        Map<String, Object> consumerProps =
                KafkaTestUtils.consumerProps("partitioning-test-group", "true", broker);
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumer = new DefaultKafkaConsumerFactory<String, String>(consumerProps).createConsumer();
        broker.consumeFromAnEmbeddedTopic(consumer, "odds-changed");
    }

    @AfterEach
    void tearDown() {
        consumer.close();
        ((DefaultKafkaProducerFactory<String, OddsChangedEvent>) template.getProducerFactory()).destroy();
    }

    @Test
    void oneMarketAlwaysLandsOnTheSamePartitionAcrossManyPublishes() {
        UUID marketId = UUID.randomUUID();
        int publishedCount = 20;
        for (int i = 0; i < publishedCount; i++) {
            producer.publish(OddsChangedEvent.of(marketId, UUID.randomUUID(), new BigDecimal("2.00")));
        }
        template.flush();

        List<ConsumerRecord<String, String>> forThisMarket = recordsForKey(marketId.toString());

        assertThat(forThisMarket).hasSize(publishedCount);
        Set<Integer> partitionsUsed = new HashSet<>();
        forThisMarket.forEach(record -> partitionsUsed.add(record.partition()));
        assertThat(partitionsUsed).hasSize(1);
    }

    @Test
    void eventsForOneMarketArriveInTheOrderTheyWereSent() {
        UUID marketId = UUID.randomUUID();
        int publishedCount = 15;
        for (int i = 0; i < publishedCount; i++) {
            producer.publish(OddsChangedEvent.of(marketId, UUID.randomUUID(), new BigDecimal(i + ".00")));
        }
        template.flush();

        List<Integer> receivedOrder = orderedOddsForKey(
                KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(10)), marketId.toString());
        List<Integer> sentOrder = List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14);

        assertThat(receivedOrder).containsExactlyElementsOf(sentOrder);
    }

    @Test
    void twoDifferentMarketsCanEndUpOnTheSamePartitionWithoutInterleavingEachOther() {
        UUID marketA = UUID.randomUUID();
        UUID marketB = UUID.randomUUID();
        for (int i = 0; i < 10; i++) {
            producer.publish(OddsChangedEvent.of(marketA, UUID.randomUUID(), new BigDecimal(i + ".00")));
            producer.publish(OddsChangedEvent.of(marketB, UUID.randomUUID(), new BigDecimal(i + ".00")));
        }
        template.flush();

        // One poll, filtered twice: recordsForKey() would re-poll the same shared consumer,
        // and the first poll already advances past everything the topic has to offer.
        ConsumerRecords<String, String> allRecords = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(10));
        List<Integer> orderForA = orderedOddsForKey(allRecords, marketA.toString());
        List<Integer> orderForB = orderedOddsForKey(allRecords, marketB.toString());

        List<Integer> expected = List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);
        assertThat(orderForA).containsExactlyElementsOf(expected);
        assertThat(orderForB).containsExactlyElementsOf(expected);
    }

    private List<ConsumerRecord<String, String>> recordsForKey(String key) {
        return orderedRecordsForKey(KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(10)), key);
    }

    private static List<ConsumerRecord<String, String>> orderedRecordsForKey(
            ConsumerRecords<String, String> records, String key) {
        List<ConsumerRecord<String, String>> matching = new ArrayList<>();
        for (ConsumerRecord<String, String> record : records.records("odds-changed")) {
            if (key.equals(record.key())) {
                matching.add(record);
            }
        }
        matching.sort((r1, r2) -> Long.compare(r1.offset(), r2.offset()));
        return matching;
    }

    private static List<Integer> orderedOddsForKey(ConsumerRecords<String, String> records, String key) {
        return orderedRecordsForKey(records, key).stream()
                .map(record -> readOddsFieldAsInt(record.value()))
                .toList();
    }

    private static int readOddsFieldAsInt(String json) {
        try {
            JsonNode node = JSON.readTree(json);
            return node.get("odds").decimalValue().intValue();
        } catch (Exception e) {
            throw new AssertionError("could not parse odds field from: " + json, e);
        }
    }
}
