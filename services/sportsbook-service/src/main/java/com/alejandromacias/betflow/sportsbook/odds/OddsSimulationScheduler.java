package com.alejandromacias.betflow.sportsbook.odds;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives the simulator on a timer.
 *
 * <p>Separate from {@link OddsSimulator} so that turning the timer off does not also remove the
 * manual trigger: the endpoint stays usable with the scheduler disabled, which is what you want
 * while debugging a single change.
 */
@Component
@ConditionalOnProperty(name = "betflow.odds-simulator.enabled", havingValue = "true",
        matchIfMissing = true)
public class OddsSimulationScheduler {

    private final OddsSimulator simulator;

    OddsSimulationScheduler(OddsSimulator simulator) {
        this.simulator = simulator;
    }

    @Scheduled(fixedRateString = "${betflow.odds-simulator.interval-ms:2500}")
    public void tick() {
        simulator.changeRandomOdds(1);
    }
}
