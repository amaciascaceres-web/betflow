package com.alejandromacias.betflow.wallet.funds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.alejandromacias.betflow.wallet.support.PostgresBackedTest;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The three operations, one caller at a time. Contention is {@link WalletConcurrencyTest}'s
 * subject; this class is about what each operation does to the balance, the ledger and the
 * reservation, and about the invariant that ties them together.
 *
 * <p>Opts out of the rollback-per-test that {@code @DataJpaTest} provides: the service's own
 * transaction boundaries are part of what is under test, and a surrounding transaction would
 * swallow them. Each test uses its own wallet instead.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(WalletService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WalletServiceTest extends PostgresBackedTest {

    @Autowired
    private WalletService walletService;

    @Autowired
    private WalletRepository wallets;

    @Autowired
    private LedgerEntryRepository ledger;

    @Test
    void reservingTakesTheMoneyOutOfReachAndLeavesTheHoldPending() {
        UUID walletId = givenWalletWith("100.00");

        FundsReservationDto reservation =
                walletService.reserveFunds(walletId, UUID.randomUUID(), new BigDecimal("30.00"));

        assertThat(reservation.status()).isEqualTo(FundsReservationStatus.PENDING);
        assertThat(balanceOf(walletId)).isEqualByComparingTo("70.00");

        List<LedgerEntryDto> entries = ledger.findViewsByWalletId(walletId);
        assertThat(entries).extracting(LedgerEntryDto::type)
                .containsExactly(LedgerEntryType.TOPUP, LedgerEntryType.RESERVATION);
        assertThat(entries.get(1).amount()).isEqualByComparingTo("-30.00");
    }

    /** Asking for more than there is has an answer, and the answer is no. Nothing is written. */
    @Test
    void refusingForWantOfFundsLeavesNothingBehind() {
        UUID walletId = givenWalletWith("20.00");

        assertThatThrownBy(() ->
                walletService.reserveFunds(walletId, UUID.randomUUID(), new BigDecimal("30.00")))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(balanceOf(walletId)).isEqualByComparingTo("20.00");
        assertThat(ledger.findViewsByWalletId(walletId))
                .extracting(LedgerEntryDto::type)
                .containsExactly(LedgerEntryType.TOPUP);   // only the opening balance
    }

    /**
     * Confirming seals the hold without moving money: the balance already fell when the funds
     * were reserved, so a second deduction here would charge twice for one bet.
     */
    @Test
    void confirmingSealsTheHoldWithoutTouchingTheBalance() {
        UUID walletId = givenWalletWith("100.00");
        FundsReservationDto reservation =
                walletService.reserveFunds(walletId, UUID.randomUUID(), new BigDecimal("30.00"));

        walletService.confirmFunds(reservation.id());

        assertThat(balanceOf(walletId)).isEqualByComparingTo("70.00");
        assertThat(ledger.findViewsByWalletId(walletId))
                .extracting(LedgerEntryDto::type)
                .containsExactly(LedgerEntryType.TOPUP, LedgerEntryType.RESERVATION,
                        LedgerEntryType.CONFIRMATION);
    }

    @Test
    void releasingHandsAnUnspentHoldBack() {
        UUID walletId = givenWalletWith("100.00");
        FundsReservationDto reservation =
                walletService.reserveFunds(walletId, UUID.randomUUID(), new BigDecimal("30.00"));

        walletService.releaseFunds(reservation.id());

        assertThat(balanceOf(walletId)).isEqualByComparingTo("100.00");
        assertThat(ledger.findViewsByWalletId(walletId))
                .extracting(LedgerEntryDto::type)
                .containsExactly(LedgerEntryType.TOPUP, LedgerEntryType.RESERVATION,
                        LedgerEntryType.RELEASE);
    }

    /**
     * The guarded transition earning its place: a second confirmation writes nothing and adds no
     * entry. It matters because this operation will one day arrive as a message that can be
     * delivered twice, and nothing about the number of deliveries should reach the balance.
     */
    @Test
    void confirmingTwiceChangesNothingTheSecondTime() {
        UUID walletId = givenWalletWith("100.00");
        FundsReservationDto reservation =
                walletService.reserveFunds(walletId, UUID.randomUUID(), new BigDecimal("30.00"));
        walletService.confirmFunds(reservation.id());

        walletService.confirmFunds(reservation.id());

        assertThat(balanceOf(walletId)).isEqualByComparingTo("70.00");
        assertThat(ledger.findViewsByWalletId(walletId)).hasSize(3);
    }

    /** Repeating a transition is allowed and silent; driving one that cannot happen is not. */
    @Test
    void refusesToConfirmAReservationThatWasReleased() {
        UUID walletId = givenWalletWith("100.00");
        FundsReservationDto reservation =
                walletService.reserveFunds(walletId, UUID.randomUUID(), new BigDecimal("30.00"));
        walletService.releaseFunds(reservation.id());

        assertThatThrownBy(() -> walletService.confirmFunds(reservation.id()))
                .isInstanceOf(IllegalReservationStateException.class);
    }

    /**
     * The mirror of the case above, and the one a late compensation would take: the saga gave up
     * and released after the confirmation had already landed. Refusing loudly is the point —
     * returning quietly would hand the money back for a bet that is confirmed elsewhere.
     */
    @Test
    void refusesToReleaseAReservationThatWasConfirmed() {
        UUID walletId = givenWalletWith("100.00");
        FundsReservationDto reservation =
                walletService.reserveFunds(walletId, UUID.randomUUID(), new BigDecimal("30.00"));
        walletService.confirmFunds(reservation.id());

        assertThatThrownBy(() -> walletService.releaseFunds(reservation.id()))
                .isInstanceOf(IllegalReservationStateException.class);
        assertThat(balanceOf(walletId)).isEqualByComparingTo("70.00");
    }

    /**
     * The third way a guarded transition writes nothing, and the only one that never reaches the
     * question "is it already where I wanted it?" — there is no row to be anywhere.
     */
    @Test
    void refusesToActOnAReservationThatDoesNotExist() {
        assertThatThrownBy(() -> walletService.confirmFunds(UUID.randomUUID()))
                .isInstanceOf(ReservationNotFoundException.class);
        assertThatThrownBy(() -> walletService.releaseFunds(UUID.randomUUID()))
                .isInstanceOf(ReservationNotFoundException.class);
    }

    /**
     * The invariant the whole ledger exists for: the balance is not asserted, it is derived. Any
     * operation that moved money without writing its entry, or wrote an entry with the wrong sign,
     * shows up here and nowhere else.
     */
    @Test
    void theLedgerAlwaysSumsToTheBalance() {
        UUID walletId = givenWalletWith("100.00");
        FundsReservationDto confirmed =
                walletService.reserveFunds(walletId, UUID.randomUUID(), new BigDecimal("30.00"));
        FundsReservationDto released =
                walletService.reserveFunds(walletId, UUID.randomUUID(), new BigDecimal("25.00"));
        walletService.confirmFunds(confirmed.id());
        walletService.releaseFunds(released.id());

        assertThat(balanceOf(walletId)).isEqualByComparingTo("70.00");
        assertThat(wallets.ledgerTotalFor(walletId)).isEqualByComparingTo(balanceOf(walletId));
    }

    private UUID givenWalletWith(String balance) {
        // The opening balance is a TOPUP, so the ledger accounts for every euro in the wallet
        // and not merely for the ones that moved after it was created.
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
