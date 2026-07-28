package com.edtice.crm.domain;

import java.time.Instant;

/**
 * A single fact about an entity, with provenance. The current view of an entity
 * is a projection over its active observations — there are no wide profile tables.
 */
public record Observation(
        long id,
        long entityId,
        String attribute,
        String value,
        double confidence,
        String evidence,
        Long sourceDocId,
        ObservationStatus status,
        Instant observedAt) {

    public String confidencePercent() {
        return Math.round(confidence * 100) + "%";
    }
}
