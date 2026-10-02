package com.alejandromacias.betflow.wallet.funds;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns this context's failures into status codes a caller can act on, which matters more than
 * usual here: the caller is a saga, and what it does next depends on telling apart "the answer is
 * no" from "ask me again".
 *
 * <table>
 *   <tr><td>{@link InsufficientFundsException}</td><td>409</td><td>a business answer, final</td></tr>
 *   <tr><td>{@link IllegalReservationStateException}</td><td>409</td><td>also final, and also not a fault of this request</td></tr>
 *   <tr><td>{@link WalletNotFoundException}</td><td>404</td><td>the caller named something that is not there</td></tr>
 *   <tr><td>{@link ReservationNotFoundException}</td><td>404</td><td>same</td></tr>
 *   <tr><td>{@link WalletBusyException}</td><td>503</td><td>nothing was decided; coming back later is reasonable</td></tr>
 * </table>
 *
 * <p>Deliberately a flat mapping rather than an exception hierarchy. The split between failures
 * that end a flow and failures worth repeating is a decision with consequences beyond this
 * service, and inventing half of it here would prejudge it.
 */
@RestControllerAdvice
public class WalletExceptionHandler {

    @ExceptionHandler({InsufficientFundsException.class, IllegalReservationStateException.class})
    ResponseEntity<Map<String, String>> conflict(RuntimeException failure) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", failure.getMessage()));
    }

    @ExceptionHandler({WalletNotFoundException.class, ReservationNotFoundException.class})
    ResponseEntity<Map<String, String>> notFound(RuntimeException failure) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", failure.getMessage()));
    }

    @ExceptionHandler(WalletBusyException.class)
    ResponseEntity<Map<String, String>> busy(WalletBusyException failure) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", failure.getMessage()));
    }
}
