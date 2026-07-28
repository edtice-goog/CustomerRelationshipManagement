package com.edtice.crm.domain;

import java.time.Instant;

/**
 * A person or organization we know about. Everything else we know is an Observation.
 * When housekeeping merges duplicates, the losing entity keeps a {@code mergedInto}
 * pointer — its name becomes an alias that future resolution follows to the winner.
 */
public record Entity(long id, String kind, String displayName, Instant createdAt, Long mergedInto) {

    public static final String PERSON = "person";
    public static final String ORGANIZATION = "organization";

    public boolean isPerson() {
        return PERSON.equals(kind);
    }

    public boolean isMerged() {
        return mergedInto != null;
    }
}
