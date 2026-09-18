package com.alejandromacias.betflow.betting.odds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.alejandromacias.betflow.betting.support.PostgresBackedTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Verifies the consumer side end to end against a real broker running in-process: the events for
 * one market reach the listener in the order they were published, and the offset moves because
 * the listener acknowledged it.
 *
 * <p>Unlike sportsbook-service's partitioning test, this one is a {@code @SpringBootTest} on
 * purpose. What is under test here is the wiring — the deserializer built with the application's
 * {@code ObjectMapper}, the acknowledgement mode read from {@code application.yml}, the listener
 * registration itself — and a container assembled by hand in the test would be re-declaring all
 * of it and proving only that the test can configure Kafka.
 *
 * <p>Postgres is real, from a container. Until day 4 this test excluded the datasource because
 * the listener's effect was a log line; now that it writes a row, that shortcut would be testing
 * a different application from the one that runs.
 */
@SpringBootTest(properties = "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}")
@EmbeddedKafka(partitions = 6, topics = OddsChangedConsumerTest.TOPIC)
class OddsChangedConsumerTest extends PostgresBackedTest {

    static final String TOPIC = "odds-changed";
    private static final String GROUP = "betting-service-group";
    private static final int PARTITIONS = 6;

    @Autowired
    private EmbeddedKafkaBroker broker;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * A spy, not a mock: the real listener still runs, acknowledgement included. The spy only
     * keeps a record of every call, which is how the test can see what the listener received
     * without adding a hook to production code for the benefit of a test.
     */
    @MockitoSpyBean
    private OddsChangedConsumer consumer;

    private KafkaTemplate<String, OddsChangedEvent> template;

    @BeforeEach
    void setUp() {
        Map<String, Object> producerProps = KafkaTestUtils.producerProps(broker);
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);

        // The test publishes what sportsbook-service publishes: JSON with no type header. If it
        // sent one, the consumer would ignore it anyway — and the test would stop resembling the
        // producer it stands in for.
        JsonSerializer<OddsChangedEvent> valueSerializer = new JsonSerializer<>(objectMapper);
        valueSerializer.setAddTypeInfo(false);

        template = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(
                producerProps, new StringSerializer(), valueSerializer));
    }

    @AfterEach
    void tearDown() {
        ((DefaultKafkaProducerFactory<String, OddsChangedEvent>) template.getProducerFactory()).destroy();
    }

    /**
     * The guarantee the whole partition key exists for: two prices for the same market are never
     * applied in the wrong order. Applying an old price after a newer one means quoting odds that
     * no longer exist, which is a business failure, not a technical one.
     *
     * <p>Confirmed to fail when it should: publishing each record under a random key instead of
     * the market's breaks it. Note that it only started failing once the partition assertion was
     * added — on an idle broker the arrival order survives a wrong key often enough that
     * asserting the order alone passed for the wrong reason.
     */
    @Test
    void eventsForOneMarketReachTheListenerInTheOrderTheyWereSent() {
        UUID marketId = UUID.randomUUID();
        int publishedCount = 15;
        for (int i = 0; i < publishedCount; i++) {
            publish(marketId, new BigDecimal(i + ".00"));
        }

        await().atMost(Duration.ofSeconds(20))
                .until(() -> recordsReceivedFor(marketId).size() == publishedCount);
        List<ConsumerRecord<String, OddsChangedEvent>> received = recordsReceivedFor(marketId);

        // The cause is asserted alongside the effect on purpose. One key means one partition, and
        // Kafka orders strictly within a partition — that is where the guarantee comes from. On an
        // idle broker the arrival order survives a wrong key often enough that asserting the order
        // alone would pass for the wrong reason; the partition count is what gives it away.
        assertThat(received).extracting(ConsumerRecord::partition)
                .containsOnly(received.get(0).partition());
        assertThat(received).extracting(ConsumerRecord::offset).isSorted();
        assertThat(received).extracting(record -> record.value().odds().intValue())
                .containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14);
    }

    /**
     * That {@code ack-mode: MANUAL} is genuinely in force, rather than being a line of YAML that
     * something later overrode. The question is asked of the broker, not of the application: the
     * group's committed offset is the only durable record of what has been consumed, and under
     * this acknowledgement mode the listener's own {@code acknowledge()} call is the only thing
     * that can move it.
     *
     * <p>Confirmed to fail when it should: deleting that call leaves the listener receiving every
     * record while the group still believes it has read nothing — lag that grows in silence, and
     * a full replay on the next restart.
     */
    @Test
    void theOffsetAdvancesOnlyOnceTheListenerAcknowledges() {
        UUID marketId = UUID.randomUUID();
        int publishedCount = 5;
        long committedBefore = quiescedCommittedOffset();

        for (int i = 0; i < publishedCount; i++) {
            publish(marketId, new BigDecimal("1.5" + i));
        }
        await().atMost(Duration.ofSeconds(20))
                .until(() -> recordsReceivedFor(marketId).size() == publishedCount);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(totalCommittedOffset() - committedBefore).isEqualTo(publishedCount));
    }

    /**
     * How far the group's bookmark has moved in total, across every partition.
     *
     * <p>Summed rather than read from the one partition under test, and measured as a delta rather
     * than as an absolute: the tests in this class share a broker and a topic, so which partition
     * a random market key lands on decides nothing, and an absolute offset carries other tests'
     * traffic in it.
     */
    private long totalCommittedOffset() throws Exception {
        long total = 0;
        for (int partition = 0; partition < PARTITIONS; partition++) {
            OffsetAndMetadata committed = KafkaTestUtils.getCurrentOffset(
                    broker.getBrokersAsString(), GROUP, TOPIC, partition);
            if (committed != null) {
                total += committed.offset();
            }
        }
        return total;
    }

    /**
     * The same figure, once it has stopped moving. A baseline taken while the previous test's
     * acknowledgements are still being committed would make this test's delta come out short —
     * which it did, until the baseline stopped being read in the middle of the traffic it was
     * meant to exclude.
     */
    private long quiescedCommittedOffset() {
        AtomicLong previousReading = new AtomicLong(-1);
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(300)).until(() -> {
            long reading = totalCommittedOffset();
            boolean unchanged = reading == previousReading.getAndSet(reading);
            return unchanged;
        });
        return previousReading.get();
    }

    private int publish(UUID marketId, BigDecimal odds) {
        OddsChangedEvent event = new OddsChangedEvent(
                UUID.randomUUID(), marketId, UUID.randomUUID(), odds, Instant.now());
        try {
            SendResult<String, OddsChangedEvent> result =
                    template.send(TOPIC, marketId.toString(), event).get();
            return result.getRecordMetadata().partition();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while publishing", e);
        } catch (ExecutionException e) {
            throw new AssertionError("could not publish " + event, e);
        }
    }

    /**
     * Reads back what the listener was actually called with, in call order, filtered to one
     * market — the two tests share a broker and a running container, so each has to look only at
     * its own traffic.
     */
    @SuppressWarnings("unchecked")
    private List<ConsumerRecord<String, OddsChangedEvent>> recordsReceivedFor(UUID marketId) {
        return Mockito.mockingDetails(consumer).getInvocations().stream()
                .filter(invocation -> "onOddsChanged".equals(invocation.getMethod().getName()))
                .map(invocation -> (ConsumerRecord<String, OddsChangedEvent>) invocation.getArgument(0))
                .filter(record -> marketId.equals(record.value().marketId()))
                .toList();
    }
}
