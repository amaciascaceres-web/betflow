package com.alejandromacias.betflow.sportsbook.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * The topic is declared here rather than created by hand because it is part of this service's
 * public contract, and a contract belongs in version control.
 *
 * <p>Automatic topic creation is disabled on the broker on purpose: the partition count is a
 * design decision, and letting a broker default decide it silently is how that decision gets
 * skipped.
 */
@Configuration
public class KafkaTopicConfig {

    public static final String ODDS_CHANGED_TOPIC = "odds-changed";

    /**
     * Six partitions: the ceiling on how many consumers of one group can work in parallel. Chosen
     * for the parallelism a live event could need, not for today's volume — raising it later
     * changes {@code hash(key) % partitions}, which moves existing keys to different partitions
     * and breaks the ordering guarantee at that point in history.
     *
     * <p>One replica is a local-only concession. In production this would be at least three,
     * which is also what makes {@code acks=all} mean anything: with a single replica there is
     * only one acknowledgement to wait for.
     */
    @Bean
    NewTopic oddsChangedTopic() {
        return TopicBuilder.name(ODDS_CHANGED_TOPIC)
                .partitions(6)
                .replicas(1)
                .build();
    }
}
