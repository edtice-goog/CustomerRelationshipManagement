package com.edtice.crm.store;

import com.edtice.crm.domain.Opportunity;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface OpportunityStore {

    /** Assigns a fresh GUID. externalSystem/externalRef null for local opportunities. */
    Opportunity create(String name, String externalSystem, String externalRef);

    Optional<Opportunity> byId(long id);

    Optional<Opportunity> byGuid(String guid);

    List<Opportunity> listAll();

    /** Local key-value bag — upsert. */
    void setAttribute(long opportunityId, String key, String value);

    /** Insertion-order-independent; returned sorted by key. */
    Map<String, String> attributes(long opportunityId);
}
