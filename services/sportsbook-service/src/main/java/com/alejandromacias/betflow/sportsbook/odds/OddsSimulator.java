package com.alejandromacias.betflow.sportsbook.odds;

import com.alejandromacias.betflow.sportsbook.catalog.Selection;
import com.alejandromacias.betflow.sportsbook.catalog.SelectionRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

/**
 * Stands in for the external pricing feed, so there is a continuous stream of real changes to
 * consume without depending on a UI.
 *
 * <p>Prices are walked from their stored value rather than drawn fresh each time. A price that
 * jumps at random is noise, not a market, and it makes the ordering guarantee impossible to
 * reason about when reading the output.
 */
@Component
public class OddsSimulator {

    private static final BigDecimal MIN_ODDS = new BigDecimal("1.01");
    private static final BigDecimal MAX_ODDS = new BigDecimal("50.00");
    private static final BigDecimal STEP = new BigDecimal("0.05");

    private final SelectionRepository selections;
    private final OddsService oddsService;

    OddsSimulator(SelectionRepository selections, OddsService oddsService) {
        this.selections = selections;
        this.oddsService = oddsService;
    }

    public int changeRandomOdds(int howMany) {
        List<Selection> all = selections.findAll();
        if (all.isEmpty()) {
            return 0;
        }
        for (int i = 0; i < howMany; i++) {
            Selection selection = all.get(ThreadLocalRandom.current().nextInt(all.size()));
            oddsService.changeOdds(selection.getId(), drift(selection.getCurrentOdds()));
        }
        return howMany;
    }

    private static BigDecimal drift(BigDecimal previous) {
        BigDecimal moved = ThreadLocalRandom.current().nextBoolean()
                ? previous.add(STEP)
                : previous.subtract(STEP);
        return moved.max(MIN_ODDS).min(MAX_ODDS);
    }
}
