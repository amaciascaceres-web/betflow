package com.alejandromacias.betflow.betting.remote;

import java.util.Optional;
import java.util.UUID;
import org.springframework.web.client.RestClient;

/**
 * The synchronous call that protects a bet.
 *
 * <p>Betting keeps its own copy of every price in {@code selection_odds}, updated by the event
 * stream and sitting right here. It is not used for this: ADR-004 recorded that the check which
 * decides whether money moves asks sportsbook directly, because a projection is allowed to be
 * seconds out of date and a bet is not.
 */
public class SportsbookClient {

    private final RestClient restClient;

    public SportsbookClient(RestClient restClient) {
        this.restClient = restClient;
    }

    /** Empty when the selection does not exist, or does not belong to the market named. */
    public Optional<CurrentOdds> currentOdds(UUID marketId, UUID selectionId) {
        return Optional.ofNullable(restClient.get()
                .uri("/markets/{marketId}/selections/{selectionId}/odds", marketId, selectionId)
                .retrieve()
                .onStatus(status -> status.value() == 404, (request, response) -> { })
                .body(CurrentOdds.class));
    }
}
