package com.alejandromacias.betflow.sportsbook.odds;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Manual trigger, so a specific burst can be produced on demand while debugging. */
@RestController
public class OddsController {

    private final OddsSimulator simulator;

    OddsController(OddsSimulator simulator) {
        this.simulator = simulator;
    }

    @PostMapping("/simulate-odds-change")
    public ResponseEntity<Map<String, Object>> simulate(@RequestParam(defaultValue = "100") int count) {
        int published = simulator.changeRandomOdds(count);
        return published == 0
                ? ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(Map.of("error", "no selections in the catalogue"))
                : ResponseEntity.ok(Map.of("published", published));
    }
}
