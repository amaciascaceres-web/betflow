package com.alejandromacias.betflow.betting.bet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.alejandromacias.betflow.betting.remote.SportsbookClient;
import com.alejandromacias.betflow.betting.remote.WalletClient;
import com.alejandromacias.betflow.betting.support.PostgresBackedTest;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

/**
 * The saga's sequence and the state it leaves behind, with the two remote services stubbed.
 *
 * <p>Stubbed rather than running: what is under test is the orchestration — which step runs, in
 * what order, and what is written between them. Whether wallet subtracts correctly is settled by
 * wallet's own tests, and whether the three services agree end to end is the day's manual check,
 * because those assertions live in two other schemas that this test cannot see.
 *
 * <p>The transaction per test is off, as in wallet: the saga's whole point is that its writes
 * commit separately, and a surrounding transaction would hide exactly that.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(BetSagaStore.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BettingSagaOrchestratorTest extends PostgresBackedTest {

    private static final BigDecimal STAKE = new BigDecimal("20.00");
    private static final BigDecimal SHOWN_ODDS = new BigDecimal("2.500");

    @Autowired
    private BetSagaStore store;

    @Autowired
    private BetSagaStateRepository sagas;

    @Autowired
    private BetRepository bets;

    private MockRestServiceServer sportsbookServer;
    private MockRestServiceServer walletServer;
    private BettingSagaOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        RestClient.Builder sportsbookBuilder = RestClient.builder().baseUrl("http://sportsbook");
        sportsbookServer = MockRestServiceServer.bindTo(sportsbookBuilder).build();

        RestClient.Builder walletBuilder = RestClient.builder().baseUrl("http://wallet");
        walletServer = MockRestServiceServer.bindTo(walletBuilder).build();

        orchestrator = new BettingSagaOrchestrator(store,
                new SportsbookClient(sportsbookBuilder.build()),
                new WalletClient(walletBuilder.build()));
    }

    @Test
    void placesTheBetWhenThePriceHeldAndTheFundsWereThere() {
        BetRequest request = aBetRequest();
        UUID reservationId = UUID.randomUUID();
        expectOddsLookup(request, SHOWN_ODDS);
        expectReservation(reservationId);
        expectConfirmation(reservationId);

        Bet placed = orchestrator.placeBet(request);

        assertThat(placed.getStatus()).isEqualTo(BetStatus.CONFIRMED);
        assertThat(placed.getAppliedOdds()).isEqualByComparingTo(SHOWN_ODDS);

        BetSagaState saga = sagas.findByBetId(placed.getId()).orElseThrow();
        assertThat(saga.getStatus()).isEqualTo(SagaStatus.COMPLETED);
        assertThat(saga.getCurrentStep()).isEqualTo(SagaStep.CONFIRMING);
        sportsbookServer.verify();
        walletServer.verify();
    }

    /**
     * The failure this day can handle on its own: the price moved between the user seeing it and
     * pressing the button. Nothing has been reserved, so refusing costs nothing — and wallet is
     * never called at all, which is the part worth asserting.
     */
    @Test
    void refusesAndClosesTheSagaWhenThePriceMoved() {
        BetRequest request = aBetRequest();
        expectOddsLookup(request, new BigDecimal("2.100"));

        StaleOddsException refusal = catchThrowableOfType(
                StaleOddsException.class, () -> orchestrator.placeBet(request));

        Bet bet = bets.findById(refusal.getBetId()).orElseThrow();
        assertThat(bet.getStatus()).isEqualTo(BetStatus.FAILED);
        assertThat(bet.getAppliedOdds()).isNull();
        assertThat(sagas.findByBetId(bet.getId()).orElseThrow().getStatus())
                .isEqualTo(SagaStatus.FAILED);

        // No expectation was set on wallet, so any call at all would have failed this test.
        walletServer.verify();
    }

    @Test
    void refusesWhenSportsbookHasNoSuchSelection() {
        BetRequest request = aBetRequest();
        sportsbookServer.expect(requestTo(oddsUrl(request)))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        SelectionNotFoundException refusal = catchThrowableOfType(
                SelectionNotFoundException.class, () -> orchestrator.placeBet(request));

        assertThat(bets.findById(refusal.getBetId()).orElseThrow().getStatus())
                .isEqualTo(BetStatus.FAILED);
        walletServer.verify();
    }

    /**
     * Wallet considered the stake and said no. A business answer like a moved price, and handled
     * the same way for the same reason: nothing was reserved, so there is nothing to undo — and
     * leaving the bet PENDING would be abandoning a row on purpose.
     *
     * <p>Note what is <em>not</em> asserted: that confirmation was never attempted. It cannot
     * have been, because the mock server was given no expectation for it and would have failed
     * the call.
     */
    @Test
    void refusesAndClosesTheSagaWhenWalletWillNotHoldTheStake() {
        BetRequest request = aBetRequest();
        expectOddsLookup(request, SHOWN_ODDS);
        walletServer.expect(requestTo("http://wallet/reservations"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .body("{\"error\":\"wallet holds 5.00, cannot reserve 20.00\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        InsufficientFundsException refusal = catchThrowableOfType(
                InsufficientFundsException.class, () -> orchestrator.placeBet(request));

        Bet bet = bets.findById(refusal.getBetId()).orElseThrow();
        assertThat(bet.getStatus()).isEqualTo(BetStatus.FAILED);
        // The price had already been settled when the refusal arrived, and stays recorded: it is
        // what the bet would have been accepted at, and the reason it failed was not the price.
        assertThat(bet.getAppliedOdds()).isEqualByComparingTo(SHOWN_ODDS);

        BetSagaState saga = sagas.findByBetId(bet.getId()).orElseThrow();
        assertThat(saga.getStatus()).isEqualTo(SagaStatus.FAILED);
        assertThat(saga.getCurrentStep()).isEqualTo(SagaStep.RESERVING_FUNDS);
        walletServer.verify();
    }

    /**
     * Wallet is asked to hold funds under the bet's own id, not a freshly invented one. That id
     * is the unique key stopping one bet from reserving twice, so passing anything else would
     * quietly disable the protection.
     */
    @Test
    void reservesUnderTheBetsOwnIdentity() {
        BetRequest request = aBetRequest();
        UUID reservationId = UUID.randomUUID();
        expectOddsLookup(request, SHOWN_ODDS);
        expectReservation(reservationId);
        expectConfirmation(reservationId);

        Bet placed = orchestrator.placeBet(request);

        assertThat(lastReservationRequest).contains("\"betId\":\"" + placed.getId() + "\"");
    }

    private String lastReservationRequest;

    private BetRequest aBetRequest() {
        return new BetRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), STAKE, SHOWN_ODDS);
    }

    private String oddsUrl(BetRequest request) {
        return "http://sportsbook/markets/" + request.marketId()
                + "/selections/" + request.selectionId() + "/odds";
    }

    private void expectOddsLookup(BetRequest request, BigDecimal odds) {
        sportsbookServer.expect(requestTo(oddsUrl(request)))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"selectionId":"%s","marketId":"%s","odds":%s}
                        """.formatted(request.selectionId(), request.marketId(), odds),
                        MediaType.APPLICATION_JSON));
    }

    private void expectReservation(UUID reservationId) {
        walletServer.expect(requestTo("http://wallet/reservations"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> lastReservationRequest = request.getBody().toString())
                .andRespond(withSuccess("{\"id\":\"" + reservationId + "\"}",
                        MediaType.APPLICATION_JSON));
    }

    private void expectConfirmation(UUID reservationId) {
        walletServer.expect(requestTo("http://wallet/reservations/" + reservationId + "/confirm"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));
    }

}
