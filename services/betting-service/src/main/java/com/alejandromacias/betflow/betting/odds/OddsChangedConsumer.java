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

    /**
     * The group is configured in {@code application.yml}, not on this annotation. The course's
     * outline puts it in both; with both, the annotation wins and the configured value becomes
     * decoration that looks authoritative and is not. One source, and it is the one an operator
     * can change without a rebuild.
     *
     * <p>Acknowledgement is manual: the offset advances because this method said so, after the
     * work succeeded, not because the method returned. Today the difference is invisible — the
     * work is a log line and cannot fail — but the acknowledgement point is where the guarantee
     * lives, so it is placed explicitly from the start.
     *
     * <p>It buys ordering and at-least-once, and it does not buy exactly-once: the offset commit
     * and whatever this consumer eventually writes are still two systems with no shared
     * transaction, so a crash between them replays this record. See ADR-003.
     */
    @KafkaListener(topics = ODDS_CHANGED_TOPIC)
    public void onOddsChanged(ConsumerRecord<String, OddsChangedEvent> record, Acknowledgment ack) {
        OddsChangedEvent event = record.value();
        log.info("Consumed OddsChanged eventId={} marketId={} selectionId={} odds={} "
                        + "<- partition={} offset={} key={}",
                event.eventId(), event.marketId(), event.selectionId(), event.odds(),
                record.partition(), record.offset(), record.key());

        ack.acknowledge();
    }
}
