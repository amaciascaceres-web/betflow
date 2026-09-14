package com.alejandromacias.betflow.sportsbook.odds;

import com.alejandromacias.betflow.sportsbook.config.KafkaTopicConfig;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class OddsChangedProducer {

    private static final Logger log = LoggerFactory.getLogger(OddsChangedProducer.class);

    private final KafkaTemplate<String, OddsChangedEvent> kafkaTemplate;

    OddsChangedProducer(KafkaTemplate<String, OddsChangedEvent> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Publishes with {@code marketId} as the partition key. That is the whole ordering guarantee:
     * the client hashes the key and takes it modulo the partition count, so every change to one
     * market lands on the same partition, and Kafka orders strictly within a partition. Without a
     * key the records would be spread round-robin and a consumer could apply an old price after a
     * newer one — which is accepting a bet at a price that no longer exists, not a technical
     * detail.
     *
     * <p>The send is asynchronous and the result is never ignored: an unobserved failure here
     * would be a silently dropped event, and the day's verification ("no errors in the producer
     * log") would be verifying nothing.
     */
    public void publish(OddsChangedEvent event) {
        kafkaTemplate.send(KafkaTopicConfig.ODDS_CHANGED_TOPIC, event.marketId().toString(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish OddsChanged eventId={} marketId={}",
                                event.eventId(), event.marketId(), ex);
                        return;
                    }
                    RecordMetadata metadata = result.getRecordMetadata();
                    log.info("Published OddsChanged eventId={} marketId={} selectionId={} odds={} "
                                    + "-> partition={} offset={}",
                            event.eventId(), event.marketId(), event.selectionId(), event.odds(),
                            metadata.partition(), metadata.offset());
                });
    }
}
