package com.alejandromacias.betflow.sportsbook.catalog;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The endpoint the bet-placing check calls. ADR-004 promised the money-critical question comes
 * here rather than to a consumer's copy of the price; this is that endpoint's contract.
 */
@WebMvcTest(controllers = SelectionController.class)
class SelectionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SelectionRepository selections;

    private final UUID marketId = UUID.randomUUID();
    private final UUID selectionId = UUID.randomUUID();

    @Test
    void answersWithThePriceItIsTheAuthorityOn() throws Exception {
        when(selections.findCurrentOdds(selectionId, marketId)).thenReturn(Optional.of(
                new CurrentOdds(selectionId, marketId, new BigDecimal("2.350"), Instant.now())));

        mockMvc.perform(get("/markets/{marketId}/selections/{selectionId}/odds", marketId, selectionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.odds").value(2.350))
                .andExpect(jsonPath("$.selectionId").value(selectionId.toString()));
    }

    /**
     * Absent covers two cases the caller cannot tell apart and does not need to: no such
     * selection, or one that exists in a different market. Both are the caller naming a pairing
     * that does not exist, and answering either would let a bet be placed against it.
     */
    @Test
    void answersNotFoundWhenThePairingDoesNotExist() throws Exception {
        when(selections.findCurrentOdds(selectionId, marketId)).thenReturn(Optional.empty());

        mockMvc.perform(get("/markets/{marketId}/selections/{selectionId}/odds", marketId, selectionId))
                .andExpect(status().isNotFound());
    }
}
