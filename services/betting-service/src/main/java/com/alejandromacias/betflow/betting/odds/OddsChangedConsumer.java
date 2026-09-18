package com.alejandromacias.betflow.betting.odds;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public class OddsChangedConsumer {

    /**
     * The topic name is declared here rather than imported from sportsbook-service. A shared
     * compile-time constant would be a build dependency on the producer for the sake of a string
     * that is already fixed by a published contract.
     */
    static final String ODDS_CHANGED_TOPIC = "odds-changed";

    private static final Logger log = LoggerFactory.getLogger(OddsChangedConsumer.class);

    private final OddsProjectionService projection;

    OddsChangedConsumer(OddsProjectionService projection) {
        this.projection = projection;
    }

    /**
     * The group is configured in {@code application.yml}, not on this annotation. The course's
     * outline puts it in both; with both, the annotation wins and the configured value becomes
     * decoration that looks authoritative and is not. One source, and it is the one an operator
     * can change without a rebuild.
     *
     * <p>Acknowledgement is manual and comes after the projection's transaction has committed.
     * The order matters: acknowledging first would mean a crash between the two loses the change
     * with the offset already advanced, and nothing would ever redeliver it.
     *
     * <p>Committing the offset and writing the row are still two systems with no transaction
     * spanning them, so a crash in between replays this record. That is fine here precisely
     * because replaying it changes nothing — see ADR-004.
     */
    @KafkaListener(topics = ODDS_CHANGED_TOPIC)
    public void onOddsChanged(ConsumerRecord<String, OddsChangedEvent> record, Acknowledgment ack) {
        OddsChangedEvent event = record.value();
        boolean applied = projection.apply(event);

        if (applied) {
            log.info("Applied OddsChanged eventId={} selectionId={} odds={} <- partition={} offset={}",
                    event.eventId(), event.selectionId(), event.odds(),
                    record.partition(), record.offset());
        } else {
            // Not an error and not a duplicate that was caught: the write simply had nothing to
            // do, because the stored row is already this event's or a later one's.
            log.info("Ignored OddsChanged eventId={} selectionId={} — not newer than stored "
                            + "<- partition={} offset={}",
                    event.eventId(), event.selectionId(), record.partition(), record.offset());
        }

        ack.acknowledge();
    }
}
