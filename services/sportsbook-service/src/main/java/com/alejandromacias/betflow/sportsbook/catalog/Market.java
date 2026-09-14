package com.alejandromacias.betflow.sportsbook.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Related entities are referenced by id rather than by object, matching how the domain model is
 * described: a market knows which event it belongs to, and nothing more. It also keeps lazy
 * loading out of a model that does not need it yet.
 */
@Entity
@Table(name = "market")
public class Market {

    @Id
    private UUID id;

    @Column(name = "sport_event_id", nullable = false)
    private UUID sportEventId;

    @Column(nullable = false)
    private String type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MarketStatus status;

    protected Market() { }

    public UUID getId() { return id; }
    public UUID getSportEventId() { return sportEventId; }
    public String getType() { return type; }
    public MarketStatus getStatus() { return status; }
}
