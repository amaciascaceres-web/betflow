package com.alejandromacias.betflow.wallet.funds;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Wallet's first caller from outside its own tests.
 *
 * <p>Deliberately thin: it goes through {@link WalletOperations} rather than {@link WalletService},
 * so the retry on an optimistic lock collision is applied here too. Calling the service directly
 * would work until two bets arrived at once, and then start refusing legitimate requests.
 *
 * <p>Only the two operations the happy path needs. Releasing a reservation is compensation, and
 * nothing compensates yet.
 */
@RestController
@RequestMapping("/reservations")
public class ReservationController {

    private final WalletOperations operations;

    ReservationController(WalletOperations operations) {
        this.operations = operations;
    }

    @PostMapping
    public ResponseEntity<FundsReservationDto> reserve(@RequestBody ReserveFundsRequest request) {
        FundsReservationDto reservation =
                operations.reserveFunds(request.walletId(), request.betId(), request.amount());
        return ResponseEntity.status(HttpStatus.CREATED).body(reservation);
    }

    @PostMapping("/{reservationId}/confirm")
    public ResponseEntity<Void> confirm(@PathVariable UUID reservationId) {
        operations.confirmFunds(reservationId);
        return ResponseEntity.noContent().build();
    }
}
