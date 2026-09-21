package com.alejandromacias.betflow.wallet.funds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * The retry policy on its own: how many attempts, and which failures deserve one.
 *
 * <p>Mocked deliberately, and it is the one place in this module where that is the right tool.
 * What is under test is a decision — this exception means try again, that one means stop — and a
 * decision has no state, no database and no timing. Reproducing a real collision here would take
 * two threads and a fair wind to assert something the branch already states.
 *
 * <p>That the mechanism works against a real database is {@link WalletConcurrencyTest}'s job.
 */
class WalletOperationsTest {

    private final WalletService walletService = mock(WalletService.class);
    private final WalletOperations operations = new WalletOperations(walletService);

    private final UUID walletId = UUID.randomUUID();
    private final UUID betId = UUID.randomUUID();
    private final BigDecimal amount = new BigDecimal("30.00");

    @Test
    void retriesAfterACollisionAndReturnsWhatTheSecondAttemptProduced() {
        FundsReservationDto reservation = new FundsReservationDto(
                UUID.randomUUID(), walletId, betId, amount, FundsReservationStatus.PENDING);
        when(walletService.reserveFunds(walletId, betId, amount))
                .thenThrow(new ObjectOptimisticLockingFailureException(Wallet.class, walletId))
                .thenReturn(reservation);

        assertThat(operations.reserveFunds(walletId, betId, amount)).isSameAs(reservation);
        verify(walletService, times(2)).reserveFunds(walletId, betId, amount);
    }

    /**
     * The distinction the whole class exists for. A collision says "your read was stale, look
     * again"; insufficient funds says "no". Retrying the second would ask an identical question
     * and get an identical answer, and would turn one refusal into three.
     */
    @Test
    void doesNotRetryARefusal() {
        when(walletService.reserveFunds(walletId, betId, amount))
                .thenThrow(new InsufficientFundsException(walletId, new BigDecimal("10.00"), amount));

        assertThatThrownBy(() -> operations.reserveFunds(walletId, betId, amount))
                .isInstanceOf(InsufficientFundsException.class);
        verify(walletService, times(1)).reserveFunds(walletId, betId, amount);
    }

    /**
     * Bounded, because an unbounded retry against sustained contention is a way of turning one
     * slow wallet into a thread pool with nothing left in it. Giving up is reported as a distinct
     * failure: nothing was decided, so the caller may reasonably come back later.
     */
    @Test
    void givesUpAfterThreeAttempts() {
        when(walletService.reserveFunds(walletId, betId, amount))
                .thenThrow(new ObjectOptimisticLockingFailureException(Wallet.class, walletId));

        assertThatThrownBy(() -> operations.reserveFunds(walletId, betId, amount))
                .isInstanceOf(WalletBusyException.class)
                .hasCauseInstanceOf(ObjectOptimisticLockingFailureException.class);
        verify(walletService, times(3)).reserveFunds(walletId, betId, amount);
    }

    /** Confirming touches no balance and carries no version, so there is nothing to retry. */
    @Test
    void passesConfirmationStraightThrough() {
        UUID reservationId = UUID.randomUUID();

        operations.confirmFunds(reservationId);

        verify(walletService, times(1)).confirmFunds(reservationId);
        verify(walletService, times(0)).reserveFunds(any(), any(), any());
    }
}
