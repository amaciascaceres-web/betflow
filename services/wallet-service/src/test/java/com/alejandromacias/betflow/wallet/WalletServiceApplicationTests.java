package com.alejandromacias.betflow.wallet;

import com.alejandromacias.betflow.wallet.support.PostgresBackedTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Brings its own Postgres since day 6. With Flyway on the classpath, a context test that starts
 * against whatever is listening on localhost migrates a real developer database and passes or
 * fails depending on which containers happen to be up.
 */
@SpringBootTest
class WalletServiceApplicationTests extends PostgresBackedTest {

    /** The context must start on its own, without depending on any other service. */
    @Test
    void contextLoads() {
    }
}
