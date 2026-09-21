package com.alejandromacias.betflow.wallet.funds;

import static org.assertj.core.api.Assertions.assertThat;

import com.alejandromacias.betflow.wallet.support.PostgresBackedTest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The race a betting wallet actually has: two bets placed at the same moment against one balance.
 *
 * <p>Real threads and a real database, because the failure being prevented only exists when two
 * transactions overlap. Both tests assert the <em>outcome</em> rather than the interleaving —
 * whether the two attempts collided is up to the scheduler, and a test that demanded a collision
 * would fail on a quiet machine for no reason. What must hold every time is that the money adds
 * up.
 *
 * <p>How the retry behaves once a collision does happen is settled deterministically in
 * {@link WalletOperationsTest}.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({WalletService.class, WalletOperations.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WalletConcurrencyTest extends PostgresBackedTest {

    private static final BigDecimal STAKE = new BigDecimal("60.00");

    @Autowired
    private WalletOperations operations;

    @Autowired
    private WalletRepository wallets;

    @Autowired
    private LedgerEntryRepository ledger;

    /**
     * The overdraft this day exists to prevent. Both attempts read a balance that can afford 60;
     * only one may keep it. Without the version check both would write, and the wallet would have
     * spent 120 of the 100 it held.
     *
     * <p>The loser does not fail with a technical error either: it retries, reads what the winner
     * left, and is refused for want of funds — a business answer arrived at correctly, which is
     * the point of retrying rather than giving up.
     */
    @Test
    void twoBetsWhereOnlyOneFitsLeaveExactlyOneReservation() throws Exception {
        UUID walletId = givenWalletWith("100.00");

        List<Object> outcomes = twoSimultaneousReservations(walletId);

        assertThat(outcomes).filteredOn(FundsReservationDto.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(InsufficientFundsException.class::isInstance).hasSize(1);
        assertThat(balanceOf(walletId)).isEqualByComparingTo("40.00");
        assertThat(wallets.ledgerTotalFor(walletId)).isEqualByComparingTo("40.00");
    }

    /**
     * The other half, and the reason a retry is worth writing at all. The same collision happens,
     * but this time the loser's second look finds enough money — so both bets are taken. A retry
     * that discarded the loser's work would be safe and wrong.
     */
    @Test
    void twoBetsThatBothFitAreBothTaken() throws Exception {
        UUID walletId = givenWalletWith("200.00");

        List<Object> outcomes = twoSimultaneousReservations(walletId);

        assertThat(outcomes).allMatch(FundsReservationDto.class::isInstance);
        assertThat(balanceOf(walletId)).isEqualByComparingTo("80.00");
        assertThat(wallets.ledgerTotalFor(walletId)).isEqualByComparingTo("80.00");
    }

    /**
     * Confirming and releasing are two branches of one decision, so both should never run. "Should
     * never" is not "cannot": a saga that gives up and compensates while its confirmation is still
     * in flight produces exactly this, and from day 9 a redelivery can too.
     *
     * <p>Whichever commits first wins — the loser's guarded update re-evaluates against the row it
     * left and matches nothing. What matters is that the outcome is decided rather than mixed: one
     * of the two transitions happened, the loser was told so, and the ledger still agrees with the
     * balance.
     */
    @Test
    void aConfirmAndAReleaseRacingLeaveOneWinnerAndALoudLoser() throws Exception {
        UUID walletId = givenWalletWith("100.00");
        FundsReservationDto reservation =
                operations.reserveFunds(walletId, UUID.randomUUID(), STAKE);

        List<Object> outcomes = simultaneously(
                () -> {
                    operations.confirmFunds(reservation.id());
                    return "confirmed";
                },
                () -> {
                    operations.releaseFunds(reservation.id());
                    return "released";
                });

        assertThat(outcomes)
                .filteredOn(IllegalReservationStateException.class::isInstance)
                .hasSize(1);

        // Three entries, never four: the opening balance, the hold, and exactly one of the two
        // ways a hold can end. Four would mean both branches ran.
        assertThat(ledger.findViewsByWalletId(walletId)).hasSize(3);
        assertThat(wallets.ledgerTotalFor(walletId)).isEqualByComparingTo(balanceOf(walletId));
    }

    /**
     * Two reservations for two different bets, released together so they overlap as closely as
     * the machine allows. Returns what each attempt produced — a reservation or the exception it
     * failed with — so the test can assert on both without either one killing the other.
     */
    private List<Object> twoSimultaneousReservations(UUID walletId) throws Exception {
        return simultaneously(
                () -> operations.reserveFunds(walletId, UUID.randomUUID(), STAKE),
                () -> operations.reserveFunds(walletId, UUID.randomUUID(), STAKE));
    }

    /**
     * Releases both callers at once and returns what each produced — a result or the exception it
     * failed with — so one failing does not hide what the other did.
     */
    @SafeVarargs
    private List<Object> simultaneously(Supplier<Object>... callers) throws Exception {
        CountDownLatch startLine = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(callers.length);
        try {
            List<Future<Object>> attempts = new ArrayList<>();
            for (Supplier<Object> caller : callers) {
                attempts.add(threads.submit(() -> {
                    startLine.await();
                    try {
                        return caller.get();
                    } catch (RuntimeException failure) {
                        return failure;
                    }
                }));
            }
            startLine.countDown();

            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> attempt : attempts) {
                outcomes.add(attempt.get(20, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            threads.shutdownNow();
        }
    }

    private UUID givenWalletWith(String balance) {
        Wallet wallet = wallets.save(
                new Wallet(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal(balance)));
        ledger.save(new LedgerEntry(
                wallet.getId(), LedgerEntryType.TOPUP, new BigDecimal(balance), null));
        return wallet.getId();
    }

    private BigDecimal balanceOf(UUID walletId) {
        return wallets.findBalanceById(walletId).orElseThrow();
    }
}
