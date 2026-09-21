package com.alejandromacias.betflow.wallet.funds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.alejandromacias.betflow.wallet.support.PostgresBackedTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

/**
 * What the guarded transition does on its own, and what the schema refuses. No service, no
 * threads: a wrong answer here can only be the statement's or the constraint's fault.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class FundsReservationRepositoryTest extends PostgresBackedTest {

    @Autowired
    private FundsReservationRepository reservations;

    @Autowired
    private EntityManager entityManager;

    @Test
    void movesAReservationOutOfTheStateItExpects() {
        UUID reservationId = givenPendingReservation();

        int rowsWritten = reservations.transition(
                reservationId, FundsReservationStatus.PENDING, FundsReservationStatus.CONFIRMED);

        assertThat(rowsWritten).isOne();
        assertThat(reload(reservationId).status()).isEqualTo(FundsReservationStatus.CONFIRMED);
    }

    /**
     * The second confirmation of the same reservation. Writing nothing is the whole mechanism:
     * it is what lets the caller tell "done now" from "already done" without reading first, and
     * therefore without a window in which two callers both see PENDING.
     */
    @Test
    void writesNothingWhenTheReservationHasAlreadyMoved() {
        UUID reservationId = givenPendingReservation();
        reservations.transition(reservationId, FundsReservationStatus.PENDING, FundsReservationStatus.CONFIRMED);

        int rowsWritten = reservations.transition(
                reservationId, FundsReservationStatus.PENDING, FundsReservationStatus.CONFIRMED);

        assertThat(rowsWritten).isZero();
        assertThat(reload(reservationId).status()).isEqualTo(FundsReservationStatus.CONFIRMED);
    }

    /** Zero again, and indistinguishable from the case above — which is why the caller asks twice. */
    @Test
    void writesNothingWhenTheReservationWentTheOtherWay() {
        UUID reservationId = givenPendingReservation();
        reservations.transition(reservationId, FundsReservationStatus.PENDING, FundsReservationStatus.RELEASED);

        int rowsWritten = reservations.transition(
                reservationId, FundsReservationStatus.PENDING, FundsReservationStatus.CONFIRMED);

        assertThat(rowsWritten).isZero();
        assertThat(reload(reservationId).status()).isEqualTo(FundsReservationStatus.RELEASED);
    }

    /**
     * A bet reserves funds once. Stated in the schema so that a request arriving twice cannot
     * take the money twice, however it arrives — and so that the deduction rolls back with the
     * insert, since both are in one transaction.
     */
    @Test
    void refusesASecondReservationForTheSameBet() {
        UUID walletId = givenWallet();
        UUID betId = UUID.randomUUID();
        reservations.save(new FundsReservation(walletId, betId, new BigDecimal("20.00")));
        entityManager.flush();

        // The provider's exception rather than Spring's DataIntegrityViolationException: the
        // translation happens at the repository proxy, and this flush is called on the
        // EntityManager directly. In WalletService the same collision arrives translated.
        assertThatThrownBy(() -> {
            reservations.save(new FundsReservation(walletId, betId, new BigDecimal("20.00")));
            entityManager.flush();
        }).isInstanceOf(PersistenceException.class)
                .hasMessageContaining("funds_reservation_bet_id_key");
    }

    private UUID givenWallet() {
        Wallet wallet = new Wallet(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("100.00"));
        entityManager.persist(wallet);
        entityManager.flush();
        return wallet.getId();
    }

    private UUID givenPendingReservation() {
        FundsReservation reservation =
                new FundsReservation(givenWallet(), UUID.randomUUID(), new BigDecimal("20.00"));
        entityManager.persist(reservation);
        entityManager.flush();
        return reservation.getId();
    }

    /**
     * A value read by the query. No {@code clear()} needed: the guarded update writes straight to
     * the database, and a projection has no cached instance to prefer over the row.
     */
    private FundsReservationDto reload(UUID reservationId) {
        return reservations.findViewById(reservationId).orElseThrow();
    }
}
