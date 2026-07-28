package com.edtice.crm.store;

import com.edtice.crm.domain.HousekeepingRecord;
import com.edtice.crm.domain.HousekeepingStatus;

import java.util.List;
import java.util.Optional;

public interface HousekeepingStore {

    /** Create a record linked to the given entities. decidedAt is set when status is not OPEN. */
    HousekeepingRecord create(String kind, List<Long> entityIds, String evidence, String reasoning,
                              String decidedBy, double confidence, HousekeepingStatus status,
                              Long priorRecordId);

    Optional<HousekeepingRecord> byId(long id);

    /** Newest first. */
    List<HousekeepingRecord> listAll();

    List<HousekeepingRecord> byStatus(HousekeepingStatus status);

    /** All records linked to this entity, newest first. */
    List<HousekeepingRecord> forEntity(long entityId);

    /** All records linked to BOTH entities, newest first — the pair's deliberation history. */
    List<HousekeepingRecord> forPair(long entityA, long entityB);

    void decide(long id, HousekeepingStatus status, String decidedBy, String reasoning, double confidence);
}
