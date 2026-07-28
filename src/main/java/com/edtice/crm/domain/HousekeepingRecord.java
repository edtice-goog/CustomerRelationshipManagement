package com.edtice.crm.domain;

import java.time.Instant;
import java.util.List;

/**
 * A durable record of a data-housekeeping deliberation, linked to the entities
 * involved. Whether the decision was merge or keep-separate, the evidence and
 * reasoning stay attached — so future reconsiderations start from the history
 * instead of from scratch. This is the documentation that normally lives outside
 * the CRM (or nowhere) while the data never gets cleaned.
 */
public record HousekeepingRecord(
        long id,
        String kind,
        HousekeepingStatus status,
        String evidence,
        String reasoning,
        String decidedBy,
        double confidence,
        Long priorRecordId,
        Instant createdAt,
        Instant decidedAt,
        List<Long> entityIds) {

    public static final String ENTITY_MERGE = "entity_merge";

    public String confidencePercent() {
        return Math.round(confidence * 100) + "%";
    }
}
