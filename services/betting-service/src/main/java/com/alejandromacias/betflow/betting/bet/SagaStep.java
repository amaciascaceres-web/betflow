package com.alejandromacias.betflow.betting.bet;

/** Where a saga got to. Recorded before each step starts, so a crash leaves it pointing at the
 * step that was in flight rather than at the last one that finished. */
public enum SagaStep {
    VALIDATING_ODDS,
    RESERVING_FUNDS,
    CONFIRMING
}
