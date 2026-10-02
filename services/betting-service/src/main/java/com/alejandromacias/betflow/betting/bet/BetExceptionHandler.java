package com.alejandromacias.betflow.betting.bet;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The refusals this day can produce, all of them final answers rather than faults: a price that
 * moved, a selection that is not there, and a stake wallet would not hold.
 *
 * <p>Everything that leaves the question <em>open</em> is missing on purpose — a timeout above
 * all, where the reservation may or may not exist. Those need deciding along with what to undo,
 * and a status code chosen before that decision would be a guess that later has to be unpicked.
 */
@RestControllerAdvice
public class BetExceptionHandler {

    @ExceptionHandler({StaleOddsException.class, InsufficientFundsException.class})
    ResponseEntity<Map<String, String>> refused(RuntimeException refusal) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", refusal.getMessage()));
    }

    @ExceptionHandler(SelectionNotFoundException.class)
    ResponseEntity<Map<String, String>> unknownSelection(SelectionNotFoundException refusal) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", refusal.getMessage()));
    }
}
