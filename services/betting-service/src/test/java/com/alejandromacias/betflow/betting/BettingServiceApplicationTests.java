package com.alejandromacias.betflow.betting;

import com.alejandromacias.betflow.betting.support.PostgresBackedTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;

/**
 * Since day 4 this test brings its own Postgres and its own broker. Without them it would start
 * the context against whatever happens to be listening on localhost — running Flyway against a
 * developer's real database, and passing or failing depending on which containers were up. A test
 * that asserts the context starts on its own has to own what it starts against.
 */
@SpringBootTest(properties = "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}")
@EmbeddedKafka(partitions = 6, topics = "odds-changed")
class BettingServiceApplicationTests extends PostgresBackedTest {

    /** The context must start on its own, without depending on any other service. */
    @Test
    void contextLoads() {
    }
}
