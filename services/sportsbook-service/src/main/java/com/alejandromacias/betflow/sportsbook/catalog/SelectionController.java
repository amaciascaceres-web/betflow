package com.alejandromacias.betflow.sportsbook.catalog;

import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sportsbook's side of the check that protects a bet.
 *
 * <p>It answers "what is this priced at", not "may this bet be accepted". Deciding whether a
 * price is still acceptable belongs to whoever owns the bet, along with how much drift it
 * tolerates; this service owns the price and nothing else. Keeping the question that narrow is
 * what lets the rule change without this endpoint changing.
 *
 * <p>ADR-004 promised that the money-critical check asks this service directly rather than
 * trusting a consumer's copy of the price. This is the endpoint that promise refers to.
 */
@RestController
public class SelectionController {

    private final SelectionRepository selections;

    SelectionController(SelectionRepository selections) {
        this.selections = selections;
    }

    @GetMapping("/markets/{marketId}/selections/{selectionId}/odds")
    public ResponseEntity<CurrentOdds> currentOdds(@PathVariable UUID marketId,
            @PathVariable UUID selectionId) {
        return selections.findCurrentOdds(selectionId, marketId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
