package com.edtice.crm.domain;

import java.time.Instant;

/**
 * A single fact about an entity, with provenance. The current view of an entity
 * is a projection over its active observations — there are no wide profile tables.
 * Commitments extracted from an activity's documents also carry the activity id,
 * so activities know whether they have a committed next step.
 */
public record Observation(
        long id,
        long entityId,
        String attribute,
        String value,
        double confidence,
        String evidence,
        Long sourceDocId,
        Long activityId,
        ObservationStatus status,
        Instant observedAt) {

    public static final String COMMITMENT = "commitment";

    public String confidencePercent() {
        return Math.round(confidence * 100) + "%";
    }
}
