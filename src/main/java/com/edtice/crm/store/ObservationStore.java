package com.edtice.crm.store;

import com.edtice.crm.domain.Observation;
import com.edtice.crm.domain.ObservationStatus;

import java.util.List;
import java.util.Optional;

public interface ObservationStore {

    Observation insert(long entityId, String attribute, String value, double confidence,
                       String evidence, Long sourceDocId, ObservationStatus status);

    Optional<Observation> byId(long id);

    /** All observations for an entity, newest first (any status). */
    List<Observation> forEntity(long entityId);

    List<Observation> pendingReview();

    /** Find the entity that owns an active/pending observation with this attribute+value (used for entity resolution by email). */
    Optional<Long> entityIdByAttributeValue(String attribute, String value);

    /** True if a live (active or pending) observation with this exact attribute+value already exists. */
    boolean duplicateExists(long entityId, String attribute, String value);

    void setStatus(long id, ObservationStatus status);

    /** When this entity last gained an observation — used to decide whether a settled housekeeping question has new evidence. */
    java.util.Optional<java.time.Instant> latestObservedAt(long entityId);
}
