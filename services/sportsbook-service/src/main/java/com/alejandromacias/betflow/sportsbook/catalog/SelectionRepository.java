package com.alejandromacias.betflow.sportsbook.catalog;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SelectionRepository extends JpaRepository<Selection, UUID> {

    List<Selection> findByMarketId(UUID marketId);
}
