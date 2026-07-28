package com.edtice.crm.store;

import com.edtice.crm.domain.Relationship;

import java.util.List;

public interface RelationshipStore {

    /** Create the edge unless an identical one already exists. */
    void ensure(long fromEntity, long toEntity, String kind, Long sourceDocId);

    List<Relationship> from(long entityId);
}
