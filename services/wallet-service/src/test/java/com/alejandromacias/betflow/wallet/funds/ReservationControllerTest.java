package com.alejandromacias.betflow.wallet.funds;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The HTTP contract, and only that: which status code each outcome becomes.
 *
 * <p>It matters more than a mapping usually does, because the caller is a saga. Telling apart
 * "the answer is no" from "ask me again" is what decides whether it compensates or retries, and
 * a wrong code here would make that decision for it.
 */
@WebMvcTest(controllers = ReservationController.class)
@org.springframework.context.annotation.Import(WalletExceptionHandler.class)
class ReservationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WalletOperations operations;

    private final UUID walletId = UUID.randomUUID();
    private final UUID betId = UUID.randomUUID();

    @Test
    void reservingReturnsTheReservationItCreated() throws Exception {
        UUID reservationId = UUID.randomUUID();
        when(operations.reserveFunds(any(), any(), any())).thenReturn(new FundsReservationDto(
                reservationId, walletId, betId, new BigDecimal("20.00"),
                FundsReservationStatus.PENDING));

        mockMvc.perform(post("/reservations").contentType(MediaType.APPLICATION_JSON)
                        .content(body("20.00")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(reservationId.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void confirmingReturnsNoContent() throws Exception {
        UUID reservationId = UUID.randomUUID();

        mockMvc.perform(post("/reservations/{id}/confirm", reservationId))
                .andExpect(status().isNoContent());

        verify(operations).confirmFunds(reservationId);
    }

    /** A final answer: the saga must not read this as "try again". */
    @Test
    void refusingForWantOfFundsIsAConflict() throws Exception {
        when(operations.reserveFunds(any(), any(), any())).thenThrow(
                new InsufficientFundsException(walletId, new BigDecimal("5.00"), new BigDecimal("20.00")));

        mockMvc.perform(post("/reservations").contentType(MediaType.APPLICATION_JSON)
                        .content(body("20.00")))
                .andExpect(status().isConflict());
    }

    /** Nothing was decided, so coming back later is reasonable — and distinguishable. */
    @Test
    void givingUpUnderContentionIsUnavailable() throws Exception {
        when(operations.reserveFunds(any(), any(), any())).thenThrow(
                new WalletBusyException("wallet " + walletId, 3, new RuntimeException()));

        mockMvc.perform(post("/reservations").contentType(MediaType.APPLICATION_JSON)
                        .content(body("20.00")))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void confirmingAReservationThatWentTheOtherWayIsAConflict() throws Exception {
        UUID reservationId = UUID.randomUUID();
        doThrow(new IllegalReservationStateException(reservationId, betId,
                FundsReservationStatus.RELEASED, FundsReservationStatus.PENDING))
                .when(operations).confirmFunds(reservationId);

        mockMvc.perform(post("/reservations/{id}/confirm", reservationId))
                .andExpect(status().isConflict());
    }

    private String body(String amount) {
        return """
                {"walletId":"%s","betId":"%s","amount":%s}
                """.formatted(walletId, betId, amount);
    }
}
