package com.alejandromacias.betflow.sportsbook.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sport_event")
public class SportEvent {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(name = "start_date", nullable = false)
    private Instant startDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SportEventStatus status;

    protected SportEvent() { }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public Instant getStartDate() { return startDate; }
    public SportEventStatus getStatus() { return status; }
}
