package com.alejandromacias.betflow.betting.remote;

import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.web.client.RestClient;

/**
 * Wallet over HTTP. Day 9 replaces reserving with a command on a queue; confirming stays
 * synchronous for now because the saga has nothing useful to do until it knows the answer.
 */
public class WalletClient {

    private final RestClient restClient;

    public WalletClient(RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * The {@code betId} is not a label. Wallet keys its uniqueness on it, so this bet can only
     * ever hold funds once however many times the request arrives — which is why the caller must
     * pass the bet's own id and never a fresh one per attempt.
     */
    public ReservedFunds reserveFunds(UUID walletId, UUID betId, BigDecimal amount) {
        return restClient.post()
                .uri("/reservations")
                .body(new ReserveFundsRequest(walletId, betId, amount))
                .retrieve()
                // A refusal, not a fault: wallet considered the request and said no. Translating
                // it here keeps status codes out of the saga, which is what will let day 9 move
                // this onto a queue without the orchestrator's branches changing.
                //
                // 409 on this endpoint can only mean insufficient funds today, since that is the
                // one business refusal reserving can produce. It is an inference, and it stops
                // being safe the day wallet gains a second reason to say 409 — at which point the
                // response needs to carry a code and not only a status.
                .onStatus(status -> status.value() == 409, (request, response) -> {
                    throw new FundsRefusedException(bodyOf(response));
                })
                .body(ReservedFunds.class);
    }

    private static String bodyOf(org.springframework.http.client.ClientHttpResponse response) {
        try {
            return new String(response.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException unreadable) {
            return "no reason given";
        }
    }

    public void confirmFunds(UUID reservationId) {
        restClient.post()
                .uri("/reservations/{reservationId}/confirm", reservationId)
                .retrieve()
                .toBodilessEntity();
    }
}
