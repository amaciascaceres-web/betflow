package com.alejandromacias.betflow.betting.bet;

import com.alejandromacias.betflow.betting.remote.CurrentOdds;
import com.alejandromacias.betflow.betting.remote.FundsRefusedException;
import com.alejandromacias.betflow.betting.remote.SportsbookClient;
import com.alejandromacias.betflow.betting.remote.WalletClient;
import java.math.BigDecimal;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Placing a bet, which is the first operation in this project that crosses bounded contexts.
 *
 * <p>Three steps, in one place, deciding the next one explicitly:
 *
 * <ol>
 *   <li>ask sportsbook what the selection is priced at, and refuse if it moved
 *   <li>ask wallet to hold the stake
 *   <li>confirm both the hold and the bet
 * </ol>
 *
 * <p><b>Orchestrated rather than choreographed</b> because this flow has the three properties
 * that ask for it: someone has to know at any moment which step a bet is on, the failures need
 * compensating in a definite order, and the steps are few and stable. Choreography would scatter
 * "what to undo, and in what order" across services reacting to each other, with nobody holding
 * the whole picture. The same system will choose choreography elsewhere, for consumers that only
 * need to find out something happened — the question is never which is better, it is whether
 * anyone needs to be in charge.
 *
 * <p><b>This class has no transaction, and must not have one.</b> Its writes are separate local
 * transactions, applied through {@link BetSagaStore}, with remote calls in between. That is the
 * definition of a saga: there is no rollback spanning the three services, so the record of how
 * far it got has to survive each step independently.
 *
 * <p><b>Only one failure is handled today</b> — a price that moved. It is the one that needs no
 * compensation, because nothing has been reserved when it is discovered. Everything that can fail
 * after the money is held is left for the day that can undo it, rather than half-handled here.
 */
@Service
public class BettingSagaOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(BettingSagaOrchestrator.class);

    private final BetSagaStore store;
    private final SportsbookClient sportsbook;
    private final WalletClient wallet;

    BettingSagaOrchestrator(BetSagaStore store, SportsbookClient sportsbook, WalletClient wallet) {
        this.store = store;
        this.sportsbook = sportsbook;
        this.wallet = wallet;
    }

    public Bet placeBet(BetRequest request) {
        Bet bet = store.start(request);
        log.info("Saga started for bet {} (user {}, stake {})",
                bet.getId(), request.userId(), request.amount());

        try {
            BigDecimal odds = validateCurrentOdds(bet.getId(), request);
            store.recordAcceptedOdds(bet.getId(), odds);

            store.enter(bet.getId(), SagaStep.RESERVING_FUNDS);
            UUID reservationId = reserveFunds(bet.getId(), request);

            store.enter(bet.getId(), SagaStep.CONFIRMING);
            wallet.confirmFunds(reservationId);

            Bet confirmed = store.complete(bet.getId());
            log.info("Saga completed for bet {} at odds {}",
                    confirmed.getId(), confirmed.getAppliedOdds());
            return confirmed;

        } catch (StaleOddsException | SelectionNotFoundException | InsufficientFundsException refusal) {
            // Every refusal gets the same treatment, which is why one catch covers all three.
            // What they have in common is that an answer arrived: nothing is left half-done, and
            // asking again would be told the same thing. Leaving the bet PENDING instead would be
            // abandoning a row on purpose.
            //
            // Deliberately not catching anything else. A timeout or a 5xx leaves the question
            // open — the reservation may exist — and closing the saga there would be recording a
            // decision nobody made.
            store.fail(bet.getId());
            log.info("Saga failed for bet {}: {}", bet.getId(), refusal.getMessage());
            throw refusal;
        }
    }

    /**
     * Wallet's "no" arrives as a transport-level refusal and leaves here as this context's own.
     * The saga never sees a status code, which is what lets the same branch survive day 9 moving
     * this call onto a queue.
     */
    private UUID reserveFunds(UUID betId, BetRequest request) {
        try {
            return wallet.reserveFunds(request.walletId(), betId, request.amount()).id();
        } catch (FundsRefusedException refused) {
            // Logged here, where what wallet said is still at hand, rather than carried up to be
            // logged later. It stays out of the exception for the same reason it stays out of the
            // response: another service's wording is not this one's to pass on.
            log.info("Wallet refused bet {}: {}", betId, refused.getMessage());
            throw new InsufficientFundsException(betId);
        }
    }

    /**
     * Asks the service that owns the price, not the copy sitting in this service's own database.
     * The projection is allowed to be seconds out of date; a bet is not.
     *
     * <p>The comparison is exact. Accepting a drift, or accepting a price that moved in the
     * bettor's favour, is a product rule nobody has stated — and guessing at one here would be
     * deciding how much money the house is willing to give away.
     */
    private BigDecimal validateCurrentOdds(UUID betId, BetRequest request) {
        CurrentOdds current = sportsbook.currentOdds(request.marketId(), request.selectionId())
                .orElseThrow(() -> new SelectionNotFoundException(
                        betId, request.marketId(), request.selectionId()));

        if (current.odds().compareTo(request.expectedOdds()) != 0) {
            throw new StaleOddsException(
                    betId, request.selectionId(), request.expectedOdds(), current.odds());
        }
        return current.odds();
    }
}
