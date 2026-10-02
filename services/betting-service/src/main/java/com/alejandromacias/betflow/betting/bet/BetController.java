package com.alejandromacias.betflow.betting.bet;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/bets")
public class BetController {

    private final BettingSagaOrchestrator orchestrator;

    BetController(BettingSagaOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @PostMapping
    public ResponseEntity<BetResponse> placeBet(@RequestBody BetRequest request) {
        Bet bet = orchestrator.placeBet(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(BetResponse.of(bet));
    }
}
